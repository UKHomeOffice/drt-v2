package actors

import actors.SplitsConversion.splitMessageToApiSplits
import akka.actor._
import akka.persistence._
import drt.shared.Crunch._
import drt.shared.FlightsApi._
import drt.shared.SplitRatiosNs.SplitSources
import drt.shared._
import server.protobuf.messages.CrunchState._
import services.SDate
import services.graphstages.Crunch._

import scala.collection.immutable._
import scala.language.postfixOps

class CrunchStateActor(name: String, portQueues: Map[TerminalName, Seq[QueueName]]) extends PersistentActor with ActorLogging {
  override def persistenceId: String = s"$name-crunch-state"

  var state: Option[PortState] = None

  val snapshotInterval = 100

  val oneDayMinutes = 1440

  override def receiveRecover: Receive = {
    case SnapshotOffer(metadata, snapshot) =>
      log.info(s"Recovery: received SnapshotOffer ${metadata.timestamp} with ${snapshot.getClass}")
      setStateFromSnapshot(snapshot)

    case cdm: CrunchDiffMessage =>
      log.info(s"Recovery: received CrunchDiffMessage")
      val newState = stateFromDiff(cdm, state)
      newState match {
        case None => log.info(s"Recovery: state is None")
        case Some(s) =>
          val apiCount = s.flights.count {
            case (_, f) => f.splits.exists {
              case ApiSplits(_, SplitSources.ApiSplitsWithCsvPercentage, _, _) => true
              case _ => false
            }
          }
          log.info(s"Recovery: state contains ${s.flights.size} flights " +
            s"with ${apiCount} Api splits " +
            s"and ${s.crunchMinutes.size} crunch minutes")
      }
      state = newState

    case RecoveryCompleted =>
      log.info("Finished restoring crunch state")

    case u =>
      log.info(s"Recovery: received unexpected ${u.getClass}")
  }

  override def receiveCommand: Receive = {
    case cs@PortState(_, _) =>
      log.info(s"Received PortState. storing")
      updateStateFromPortState(cs)
      saveSnapshotAtInterval(cs)

    case GetState =>
      sender() ! state

    case GetPortState(start: MillisSinceEpoch, end: MillisSinceEpoch) =>
      sender() ! stateForPeriod(start, end)

    case GetUpdatesSince(millis, start, end) =>
      val updates = state match {
        case Some(cs) =>
          val updatedFlights = cs.flights.filter {
            case (_, f) => f.lastUpdated.getOrElse(1L) > millis && start <= f.apiFlight.PcpTime && f.apiFlight.PcpTime < end
          }.values.toSet
          val updatedMinutes = cs.crunchMinutes.filter {
            case (_, cm) => cm.lastUpdated.getOrElse(1L) > millis && start <= cm.minute && cm.minute < end
          }.values.toSet
          if (updatedFlights.nonEmpty || updatedMinutes.nonEmpty) {
            val flightsLatest = if (updatedFlights.nonEmpty) updatedFlights.map(_.lastUpdated.getOrElse(1L)).max else 0L
            val minutesLatest = if (updatedMinutes.nonEmpty) updatedMinutes.map(_.lastUpdated.getOrElse(1L)).max else 0L
            val latestUpdate = Math.max(flightsLatest, minutesLatest)
            log.info(s"latestUpdate: ${SDate(latestUpdate).toLocalDateTimeString()}")
            Option(CrunchUpdates(latestUpdate, updatedFlights, updatedMinutes))
          } else None
        case None => None
      }
      sender() ! updates

    case SaveSnapshotSuccess(md) =>
      log.info(s"Snapshot success $md")

    case SaveSnapshotFailure(md, cause) =>
      log.info(s"Snapshot failed $md\n$cause")

    case u =>
      log.warning(s"Received unexpected message $u")
  }

  def stateForPeriod(start: MillisSinceEpoch, end: MillisSinceEpoch) = {
    state.map {
      case PortState(fs, ms) => PortState(
        flights = fs.filter {
          case (_, f) => start <= f.apiFlight.PcpTime && f.apiFlight.PcpTime < end
        },
        crunchMinutes = ms.filter {
          case (_, m) =>
            start <= m.minute && m.minute < end
        }
      )
    }
  }

  def setStateFromSnapshot(snapshot: Any): Unit = {
    snapshot match {
      case sm@CrunchStateSnapshotMessage(_, _, _, _) =>
        log.info(s"Using snapshot to restore")
        state = Option(snapshotMessageToState(sm))
      case somethingElse =>
        log.info(s"Ignoring unexpected snapshot ${somethingElse.getClass}")
    }
  }

  def stateFromDiff(cdm: CrunchDiffMessage, existingState: Option[PortState]): Option[PortState] = {
    log.info(s"Unpacking CrunchDiffMessage")
    val diff = crunchDiffFromMessage(cdm)
    log.info(s"Unpacked CrunchDiffMessage - ${diff.crunchMinuteRemovals.size} crunch minute removals, " +
      s"${diff.crunchMinuteUpdates.size} crunch minute updates, " +
      s"${diff.flightRemovals.size} flight removals, " +
      s"${diff.flightUpdates.size} flight updates")
    val newState = existingState match {
      case None =>
        log.info(s"Creating an empty PortState to apply CrunchDiff")
        Option(PortState(
          flights = applyFlightsWithSplitsDiff(diff, Map()),
          crunchMinutes = applyCrunchDiff(diff, Map())
        ))
      case Some(ps) =>
        log.info(s"Applying CrunchDiff to PortState")
        val stateWithDiffsApplied = Option(PortState(
          flights = applyFlightsWithSplitsDiff(diff, ps.flights),
          crunchMinutes = applyCrunchDiff(diff, ps.crunchMinutes)))
        log.info(s"Finished applying CrunchDiff to PortState")
        stateWithDiffsApplied
    }
    newState
  }

  def crunchDiffFromMessage(diffMessage: CrunchDiffMessage): CrunchDiff = {
    CrunchDiff(
      flightRemovals = diffMessage.flightIdsToRemove.map(RemoveFlight).toSet,
      flightUpdates = diffMessage.flightsToUpdate.map(flightWithSplitsFromMessage).toSet,
      crunchMinuteRemovals = diffMessage.crunchMinutesToRemove.map(m => RemoveCrunchMinute(m.terminalName.getOrElse(""), m.queueName.getOrElse(""), m.minute.getOrElse(0L))).toSet,
      crunchMinuteUpdates = diffMessage.crunchMinutesToUpdate.map(crunchMinuteFromMessage).toSet
    )
  }

  def updateStateFromPortState(newState: PortState): Unit = {
    val existingState = state match {
      case None =>
        log.info(s"updating from no existing state")
        PortState(Map(), Map())
      case Some(s) => s
    }
    val (_, crunchesToUpdate) = crunchMinutesDiff(existingState.crunchMinutes, newState.crunchMinutes)
    val (flightsToRemove, flightsToUpdate) = flightsDiff(existingState.flights, newState.flights)
    val diff = CrunchDiff(flightsToRemove, flightsToUpdate, Set(), crunchesToUpdate)

    val cmsFromDiff = applyCrunchDiff(diff, existingState.crunchMinutes)
    val flightsFromDiff = applyFlightsWithSplitsDiff(diff, existingState.flights)

    val diffToPersist = CrunchDiffMessage(
      createdAt = Option(SDate.now().millisSinceEpoch),
      crunchStart = Option(0),
      flightIdsToRemove = diff.flightRemovals.map(rf => rf.flightId).toList,
      flightsToUpdate = diff.flightUpdates.map(FlightMessageConversion.flightWithSplitsToMessage).toList,
      crunchMinutesToRemove = diff.crunchMinuteRemovals.map(removeCrunchMinuteToMessage).toList,
      crunchMinutesToUpdate = diff.crunchMinuteUpdates.map(crunchMinuteToMessage).toList
    )

    persist(diffToPersist) { (diff: CrunchDiffMessage) =>
      log.info(s"Persisting ${diff.getClass}")
      context.system.eventStream.publish(diff)
    }

    val updatedState = existingState.copy(
      flights = flightsFromDiff,
      crunchMinutes = cmsFromDiff)

    state = Option(updatedState)
  }

  def removeCrunchMinuteToMessage(rc: RemoveCrunchMinute): RemoveCrunchMinuteMessage = {
    RemoveCrunchMinuteMessage(Option(rc.terminalName), Option(rc.queueName), Option(rc.minute))
  }

  def crunchMinuteToMessage(cm: CrunchMinute): CrunchMinuteMessage = {
    CrunchMinuteMessage(Option(cm.terminalName), Option(cm.queueName), Option(cm.minute), Option(cm.paxLoad), Option(cm.workLoad), Option(cm.deskRec), Option(cm.waitTime))
  }

  def saveSnapshotAtInterval(cs: PortState): Unit = {
    if (lastSequenceNr % snapshotInterval == 0 && lastSequenceNr != 0) {
      val snapshotMessage: CrunchStateSnapshotMessage = portStateToSnapshotMessage(cs)
      log.info("Saving PortState snapshot")
      saveSnapshot(snapshotMessage)
    }
  }

  def oneDayOfMinutes: Range = 0 until 1440

  def portStateToSnapshotMessage(portState: PortState) = CrunchStateSnapshotMessage(
    Option(0L),
    Option(0),
    portState.flights.values.toList.map(flight => FlightMessageConversion.flightWithSplitsToMessage(flight)),
    portState.crunchMinutes.values.toList.map(crunchMinuteToMessage)
  )

  def snapshotMessageToState(sm: CrunchStateSnapshotMessage) = {
    log.info(s"Unwrapping flights messages")
    val flights = sm.flightWithSplits.map(fm => {
      val fws = flightWithSplitsFromMessage(fm)
      (fws.apiFlight.uniqueId, fws)
    }).toMap
    log.info(s"Unwrapping minutes messages")
    val minutes = sm.crunchMinutes.map(cmm => {
      val cm = crunchMinuteFromMessage(cmm)
      (cm.key, cm)
    }).toMap
    log.info(s"Finished unwrapping messages")
    PortState(flights, minutes)
  }

  def crunchMinuteFromMessage(cmm: CrunchMinuteMessage): CrunchMinute = {
    CrunchMinute(
      terminalName = cmm.terminalName.getOrElse(""),
      queueName = cmm.queueName.getOrElse(""),
      minute = cmm.minute.getOrElse(0L),
      paxLoad = cmm.paxLoad.getOrElse(0d),
      workLoad = cmm.workLoad.getOrElse(0d),
      deskRec = cmm.deskRec.getOrElse(0),
      waitTime = cmm.waitTime.getOrElse(0),
      deployedDesks = cmm.simDesks,
      deployedWait = cmm.simWait,
      actDesks = cmm.actDesks,
      actWait = cmm.actWait
    )
  }

  def flightWithSplitsFromMessage(fm: FlightWithSplitsMessage): ApiFlightWithSplits = {
    ApiFlightWithSplits(
      FlightMessageConversion.flightMessageToApiFlight(fm.flight.get),
      fm.splits.map(sm => splitMessageToApiSplits(sm)).toSet,
      None
    )
  }
}

object SplitsConversion {
  def splitMessageToApiSplits(sm: SplitMessage): ApiSplits = {
    ApiSplits(
      sm.paxTypeAndQueueCount.map(ptqcm => ApiPaxTypeAndQueueCount(
        PaxType(ptqcm.paxType.getOrElse("")),
        ptqcm.queueType.getOrElse(""),
        ptqcm.paxValue.getOrElse(0d)
      )).toSet,
      sm.source.getOrElse(""),
      sm.eventType,
      SplitStyle(sm.style.getOrElse(""))
    )
  }
}

case object GetPortWorkload
