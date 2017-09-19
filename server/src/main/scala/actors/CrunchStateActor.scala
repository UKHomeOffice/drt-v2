package actors

import actors.SplitsConversion.splitMessageToApiSplits
import akka.actor._
import akka.persistence._
import controllers.GetTerminalCrunch
import drt.shared.FlightsApi._
import drt.shared._
import server.protobuf.messages.CrunchState._
import services.Crunch._
import services.SDate
import services.workloadcalculator.PaxLoadCalculator.MillisSinceEpoch

import scala.collection.immutable._
import scala.language.postfixOps

case object GetFlights

class CrunchStateActor(portQueues: Map[TerminalName, Seq[QueueName]]) extends PersistentActor with ActorLogging {
  override def persistenceId: String = "crunch-state"

  var state: Option[CrunchState] = None

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
        case Some(s) => log.info(s"Recovery: state contains ${s.flights.size} flights and ${s.crunchMinutes.size} crunch minutes")
      }
      state = newState

    case RecoveryCompleted =>
      log.info("Finished restoring crunch state")

    case u =>
      log.info(s"Recovery: received unexpected ${u.getClass}")
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

  def stateFromDiff(cdm: CrunchDiffMessage, existingState: Option[CrunchState]): Option[CrunchState] = {
    val diff = crunchDiffFromMessage(cdm)
    val newState = existingState match {
      case None =>
        log.info(s"Creating an empty CrunchState to apply CrunchDiff")
        Option(CrunchState(
          flights = applyFlightsWithSplitsDiff(diff, Set()),
          crunchMinutes = applyCrunchDiff(diff, Set()),
          crunchFirstMinuteMillis = cdm.crunchStart.getOrElse(0L),
          numberOfMinutes = oneDayMinutes
        ))
      case Some(cs) =>
        log.info(s"Applying CrunchDiff to CrunchState")
        Option(cs.copy(
          flights = applyFlightsWithSplitsDiff(diff, cs.flights),
          crunchMinutes = applyCrunchDiff(diff, cs.crunchMinutes)))
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

  def updateStateFromCrunchState(newState: CrunchState): Unit = {
    val existingState = state match {
      case None =>
        log.info(s"updating from no existing state")
        CrunchState(newState.crunchFirstMinuteMillis, newState.numberOfMinutes, Set(), Set())
      case Some(s) => s
    }
    val (crunchesToRemove, crunchesToUpdate) = crunchMinutesDiff(existingState.crunchMinutes, newState.crunchMinutes)
    val (flightsToRemove, flightsToUpdate) = flightsDiff(existingState.flights, newState.flights)
    val diff = CrunchDiff(flightsToRemove, flightsToUpdate, crunchesToRemove, crunchesToUpdate)

    val cmsFromDiff = applyCrunchDiff(diff, existingState.crunchMinutes)
    if (cmsFromDiff != newState.crunchMinutes) {
      log.error(s"new CrunchMinutes do not match update from diff")
    }
    val flightsFromDiff = applyFlightsWithSplitsDiff(diff, existingState.flights)
    if (flightsFromDiff != newState.flights) {
      log.error(s"new Flights do not match update from diff")
    }

    val diffToPersist = CrunchDiffMessage(
      createdAt = Option(SDate.now().millisSinceEpoch),
      crunchStart = Option(newState.crunchFirstMinuteMillis),
      flightIdsToRemove = diff.flightRemovals.map(rf => rf.flightId).toList,
      flightsToUpdate = diff.flightUpdates.map(FlightMessageConversion.flightWithSplitsToMessage).toList,
      crunchMinutesToRemove = diff.crunchMinuteRemovals.map(rc => RemoveCrunchMinuteMessage(Option(rc.terminalName), Option(rc.queueName), Option(rc.minute))).toList,
      crunchMinutesToUpdate = diff.crunchMinuteUpdates.map(cm => CrunchMinuteMessage(Option(cm.terminalName), Option(cm.queueName), Option(cm.minute), Option(cm.paxLoad), Option(cm.workLoad), Option(cm.deskRec), Option(cm.waitTime))).toList
    )

    persist(diffToPersist) { (diff: CrunchDiffMessage) =>
      log.info(s"Persisting ${diff.getClass}")
      context.system.eventStream.publish(diff)
    }

    val updatedState = existingState.copy(
      crunchFirstMinuteMillis = newState.crunchFirstMinuteMillis,
      crunchMinutes = cmsFromDiff,
      flights = flightsFromDiff)

    state = Option(updatedState)
  }

  override def receiveCommand: Receive = {
    case cs@CrunchState(_, _, _, _) =>
      log.info(s"Received CrunchState. storing")
      updateStateFromCrunchState(cs)
      saveSnapshotAtInterval(cs)

    case GetFlights =>
      state match {
        case Some(CrunchState(_, _, flights, _)) =>
          sender() ! FlightsWithSplits(flights.toList)
        case None => FlightsNotReady
      }

    case GetPortWorkload =>
      state match {
        case Some(CrunchState(_, _, _, crunchMinutes)) =>
          sender() ! portWorkload(crunchMinutes)
        case None =>
          sender() ! WorkloadsNotReady()
      }

    case GetTerminalCrunch(terminalName) =>
      state match {
        case Some(CrunchState(startMillis, _, _, crunchMinutes)) =>
          sender() ! TerminalCrunchResult(queueCrunchResults(terminalName, startMillis, crunchMinutes).toList)
        case _ =>
          sender() ! TerminalCrunchResult(List[(QueueName, Either[NoCrunchAvailable, CrunchResult])]())
      }

    case SaveSnapshotSuccess(md) =>
      log.info(s"Snapshot success $md")

    case SaveSnapshotFailure(md, cause) =>
      log.info(s"Snapshot failed $md\n$cause")

    case u =>
      log.warning(s"unexpected message $u")
  }

  def saveSnapshotAtInterval(cs: CrunchState): Unit = {
    if (lastSequenceNr % snapshotInterval == 0 && lastSequenceNr != 0) {
      val snapshotMessage: CrunchStateSnapshotMessage = crunchStateToSnapshotMessage(cs)
      log.info("Saving CrunchState snapshot")
      saveSnapshot(snapshotMessage)
    }
  }

  def portWorkload(crunchMinutes: Set[CrunchMinute]): Map[TerminalName, Map[QueueName, (List[WL], IndexedSeq[Pax])]] = crunchMinutes
    .groupBy(_.terminalName)
    .map {
      case (tn, tms) =>
        val terminalLoads = tms
          .groupBy(_.queueName)
          .map {
            case (qn, qms) =>
              val sortedCms = qms.toList.sortBy(_.minute)
              val paxLoad = sortedCms.map {
                case CrunchMinute(_, _, m, pl, _, _, _) => Pax(m, pl)
              }.toIndexedSeq
              val workLoad = sortedCms.map {
                case CrunchMinute(_, _, m, _, wl, _, _) => WL(m, wl)
              }
              (qn, (workLoad, paxLoad))
          }
        (tn, terminalLoads)
    }

  def queueCrunchResults(terminalName: TerminalName,
                         startMillis: MillisSinceEpoch,
                         crunchMinutes: Set[CrunchMinute]): Seq[(QueueName, Either[NoCrunchAvailable, CrunchResult])] = crunchMinutes
    .groupBy(_.terminalName).getOrElse(terminalName, Set[CrunchMinute]())
    .groupBy(_.queueName)
    .map {
      case (qn, qms) =>
        if (qms.nonEmpty) {
          val sortedCms = qms.toList.sortBy(_.minute)
          val desks = sortedCms.map {
            case CrunchMinute(_, _, _, _, _, dr, _) => dr
          }.toIndexedSeq
          val waits = sortedCms.map {
            case CrunchMinute(_, _, _, _, _, _, wt) => wt
          }
          (qn, Right(CrunchResult(startMillis, oneMinute, desks, waits)))
        } else {
          (qn, Left(NoCrunchAvailable()))
        }
    }.toList

  def oneDayOfMinutes: Range = 0 until 1440

  def crunchStateToSnapshotMessage(crunchState: CrunchState) = CrunchStateSnapshotMessage(
    Option(crunchState.crunchFirstMinuteMillis),
    Option(crunchState.numberOfMinutes),
    crunchState.flights.toList.map(flight => FlightMessageConversion.flightWithSplitsToMessage(flight)),
    crunchState.crunchMinutes.toList.map(cm => CrunchMinuteMessage(
      Option(cm.terminalName),
      Option(cm.queueName),
      Option(cm.minute),
      Option(cm.paxLoad),
      Option(cm.workLoad),
      Option(cm.deskRec),
      Option(cm.waitTime)
    ))
  )

  def snapshotMessageToState(sm: CrunchStateSnapshotMessage) = CrunchState(
    sm.crunchStart.getOrElse(0L),
    sm.numberOfMinutes.getOrElse(0),
    sm.flightWithSplits.map(flightWithSplitsFromMessage).toSet,
    sm.crunchMinutes.map(crunchMinuteFromMessage).toSet
  )

  def crunchMinuteFromMessage(cmm: CrunchMinuteMessage): CrunchMinute = {
    CrunchMinute(
      cmm.terminalName.getOrElse(""),
      cmm.queueName.getOrElse(""),
      cmm.minute.getOrElse(0L),
      cmm.paxLoad.getOrElse(0d),
      cmm.workLoad.getOrElse(0d),
      cmm.deskRec.getOrElse(0),
      cmm.waitTime.getOrElse(0)
    )
  }

  def flightWithSplitsFromMessage(fm: FlightWithSplitsMessage): ApiFlightWithSplits = {
    ApiFlightWithSplits(
      FlightMessageConversion.flightMessageToApiFlight(fm.flight.get),
      fm.splits.map(sm => splitMessageToApiSplits(sm)).toList
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
      )).toList,
      sm.source.getOrElse(""),
      SplitStyle(sm.style.getOrElse(""))
    )
  }
}

case object GetPortWorkload
