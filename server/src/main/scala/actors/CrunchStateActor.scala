package actors

import actors.SplitsConversion.splitMessageToApiSplits
import akka.actor._
import akka.persistence._
import com.trueaccord.scalapb.GeneratedMessage
import drt.shared.CrunchApi._
import drt.shared.FlightsApi._
import drt.shared.SplitRatiosNs.SplitSources
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import server.protobuf.messages.CrunchState._
import services.SDate
import services.graphstages.Crunch._
import services.graphstages.PortStateWithDiff

import scala.language.postfixOps

class CrunchStateActor(override val maybeSnapshotInterval: Option[Int],
                       val snapshotBytesThreshold: Int,
                       name: String,
                       portQueues: Map[TerminalName, Seq[QueueName]],
                       now: () => SDateLike,
                       expireAfterMillis: Long,
                       purgePreviousSnapshots: Boolean) extends PersistentActor with RecoveryActorLike with PersistentDrtActor[Option[PortState]] {
  override def persistenceId: String = name

  val log: Logger = LoggerFactory.getLogger(s"$name-$getClass")

  def logInfo(msg: String): Unit = if (name.isEmpty) log.info(msg) else log.info(s"$name $msg")

  var state: Option[PortState] = initialState

  def initialState: Option[PortState] = None

  def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case snapshot: CrunchStateSnapshotMessage => setStateFromSnapshot(snapshot)
  }

  def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case diff: CrunchDiffMessage =>
      val newState = stateFromDiff(diff, state)
      logRecoveryState(newState)
      state = newState
      bytesSinceSnapshotCounter += diff.serializedSize
      messagesPersistedSinceSnapshotCounter += 1
  }

  def logRecoveryState(optionalState: Option[PortState]): Unit = optionalState match {
    case None => logInfo(s"Recovery: state is None")
    case Some(s) =>
      val apiCount = s.flights.count {
        case (_, f) => f.splits.exists {
          case Splits(_, SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages, _, _) => true
          case _ => false
        }
      }
      logInfo(s"Recovery: state contains ${s.flights.size} flights " +
        s"with $apiCount Api splits " +
        s", ${s.crunchMinutes.size} crunch minutes " +
        s", ${s.staffMinutes.size} staff minutes ")
  }

  override def postSaveSnapshot(): Unit = if (purgePreviousSnapshots) {
    val maxSequenceNr = lastSequenceNr
    logInfo(s"Purging snapshots with sequence number < $maxSequenceNr")
    deleteSnapshots(SnapshotSelectionCriteria(maxSequenceNr = maxSequenceNr))
  }

  override def stateToMessage: GeneratedMessage = state match {
    case Some(ps) => portStateToSnapshotMessage(ps)
    case None => CrunchStateSnapshotMessage(Option(0L), Option(0), List(), List(), List())
  }

  override def receiveCommand: Receive = {
    case PortStateWithDiff(_, _, CrunchDiffMessage(_, _, fr, fu, cu, su, _)) if fr.isEmpty && fu.isEmpty && cu.isEmpty && su.isEmpty =>
      log.info(s"Received port state with empty diff")

    case PortStateWithDiff(portState, _, diff) =>
      logInfo(s"Received port state with diff")
      updateStateFromPortState(portState)
      persistAndMaybeSnapshot(diff)

    case GetState =>
      logInfo(s"Received GetState request. Replying with ${state.map(s => s"PortState containing ${s.crunchMinutes.size} crunch minutes")}")
      sender() ! state

    case GetPortState(start: MillisSinceEpoch, end: MillisSinceEpoch) =>
      logInfo(s"Received GetPortState Request from ${SDate(start).toISOString()} to ${SDate(end).toISOString()}")
      sender() ! stateForPeriod(start, end)

    case GetUpdatesSince(millis, start, end) =>
      val updates = state match {
        case Some(cs) =>
          val updatedFlights = cs.flights.filter(_._2.apiFlight.PcpTime.isDefined).filter {
            case (_, f) => f.lastUpdated.getOrElse(1L) > millis && start <= f.apiFlight.PcpTime.getOrElse(0L) && f.apiFlight.PcpTime.getOrElse(0L) < end
          }.values.toSet
          val updatedCrunch = cs.crunchMinutes.filter {
            case (_, cm) => cm.lastUpdated.getOrElse(1L) > millis && start <= cm.minute && cm.minute < end
          }.values.toSet
          val updatedStaff = cs.staffMinutes.filter {
            case (_, sm) => sm.lastUpdated.getOrElse(1L) > millis && start <= sm.minute && sm.minute < end
          }.values.toSet
          if (updatedFlights.nonEmpty || updatedCrunch.nonEmpty || updatedStaff.nonEmpty) {
            val flightsLatest = if (updatedFlights.nonEmpty) updatedFlights.map(_.lastUpdated.getOrElse(1L)).max else 0L
            val crunchLatest = if (updatedCrunch.nonEmpty) updatedCrunch.map(_.lastUpdated.getOrElse(1L)).max else 0L
            val staffLatest = if (updatedStaff.nonEmpty) updatedStaff.map(_.lastUpdated.getOrElse(1L)).max else 0L
            val latestUpdate = List(flightsLatest, crunchLatest, staffLatest).max
            logInfo(s"latestUpdate: ${SDate(latestUpdate).toLocalDateTimeString()}")
            Option(CrunchUpdates(latestUpdate, updatedFlights, updatedCrunch, updatedStaff))
          } else None
        case None => None
      }
      sender() ! updates

    case SaveSnapshotSuccess(md) =>
      logInfo(s"Snapshot success $md")

    case SaveSnapshotFailure(md, cause) =>
      logInfo(s"Snapshot failed $md\n$cause")

    case DeleteSnapshotsSuccess(_) =>
      logInfo(s"Purged snapshots")

    case u =>
      log.error(s"Received unexpected message $u")
  }

  def stateForPeriod(start: MillisSinceEpoch, end: MillisSinceEpoch): Option[PortState] = {
    logInfo(s"PortState contains: (cms, fs, sms) ${state.map(s => (s.crunchMinutes.size, s.flights.size, s.staffMinutes.size).toString()).getOrElse("Nothing")}")
    state.map {
      case PortState(fs, ms, ss) => PortState(
        flights = fs.filter(_._2.apiFlight.PcpTime.isDefined).filter {
          case (_, f) => start <= f.apiFlight.PcpTime.getOrElse(0L) && f.apiFlight.PcpTime.getOrElse(0L) < end
        },
        crunchMinutes = ms.filter {
          case (_, m) => start <= m.minute && m.minute < end
        },
        staffMinutes = ss.filter {
          case (_, m) => start <= m.minute && m.minute < end
        }
      )
    }
  }

  def setStateFromSnapshot(snapshot: CrunchStateSnapshotMessage, timeWindowEnd: Option[SDateLike] = None): Unit = {
    state = Option(snapshotMessageToState(snapshot, timeWindowEnd))
  }

  def stateFromDiff(cdm: CrunchDiffMessage, existingState: Option[PortState]): Option[PortState] = {
    logInfo(s"Unpacking CrunchDiffMessage")
    val (flightRemovals, flightUpdates, crunchMinuteUpdates, staffMinuteUpdates): (Set[Int], Set[ApiFlightWithSplits], Set[CrunchMinute], Set[StaffMinute]) = crunchDiffFromMessage(cdm)
    logInfo(s"Unpacked CrunchDiffMessage - " +
      s"${crunchMinuteUpdates.size} crunch minute updates, " +
      s"${staffMinuteUpdates.size} staff minute updates, " +
      s"${flightRemovals.size} flight removals, " +
      s"${flightUpdates.size} flight updates")
    val newState = existingState match {
      case None =>
        logInfo(s"Creating an empty PortState to apply CrunchDiff")
        Option(PortState(
          flights = applyFlightsWithSplitsDiff(flightRemovals, flightUpdates, Map()),
          crunchMinutes = applyCrunchDiff(crunchMinuteUpdates, Map()),
          staffMinutes = applyStaffDiff(staffMinuteUpdates, Map())
        ))
      case Some(ps) =>
        logInfo(s"Applying CrunchDiff to PortState")
        val newPortState = PortState(
          flights = applyFlightsWithSplitsDiff(flightRemovals, flightUpdates, ps.flights),
          crunchMinutes = applyCrunchDiff(crunchMinuteUpdates, ps.crunchMinutes),
          staffMinutes = applyStaffDiff(staffMinuteUpdates, ps.staffMinutes))
        logInfo(s"Finished applying CrunchDiff to PortState: ${newPortState.flights.size} flights, ${newPortState.staffMinutes.size} staff minutes, ${newPortState.crunchMinutes.size} crunch minutes")
        Option(newPortState)
    }
    newState
  }

  def crunchDiffFromMessage(diffMessage: CrunchDiffMessage): (Set[Int], Set[ApiFlightWithSplits], Set[CrunchMinute], Set[StaffMinute]) = (
    diffMessage.flightIdsToRemove.toSet,
    diffMessage.flightsToUpdate.map(flightWithSplitsFromMessage).toSet,
    diffMessage.crunchMinutesToUpdate.map(crunchMinuteFromMessage).toSet,
    diffMessage.staffMinutesToUpdate.map(staffMinuteFromMessage).toSet
  )

  def updateStateFromPortState(newState: PortState): Unit = state = Option(newState)

  def crunchMinuteToMessage(cm: CrunchMinute): CrunchMinuteMessage = CrunchMinuteMessage(
    terminalName = Option(cm.terminalName),
    queueName = Option(cm.queueName),
    minute = Option(cm.minute),
    paxLoad = Option(cm.paxLoad),
    workLoad = Option(cm.workLoad),
    deskRec = Option(cm.deskRec),
    waitTime = Option(cm.waitTime),
    simDesks = cm.deployedDesks,
    simWait = cm.deployedWait,
    actDesks = cm.actDesks,
    actWait = cm.actWait
  )

  def staffMinuteToMessage(sm: StaffMinute): StaffMinuteMessage = StaffMinuteMessage(
    terminalName = Option(sm.terminalName),
    minute = Option(sm.minute),
    shifts = Option(sm.shifts),
    fixedPoints = Option(sm.fixedPoints),
    movements = Option(sm.movements))

  def portStateToSnapshotMessage(portState: PortState) = CrunchStateSnapshotMessage(
    Option(0L),
    Option(0),
    portState.flights.values.toList.map(flight => FlightMessageConversion.flightWithSplitsToMessage(flight)),
    portState.crunchMinutes.values.toList.map(crunchMinuteToMessage),
    portState.staffMinutes.values.toList.map(staffMinuteToMessage)
  )

  def snapshotMessageToState(sm: CrunchStateSnapshotMessage, optionalTimeWindowEnd: Option[SDateLike]): PortState = {
    logInfo(s"Unwrapping flights messages")
    val flights = optionalTimeWindowEnd match {
      case None =>
        sm.flightWithSplits.map(fwsm => {
          val fws = flightWithSplitsFromMessage(fwsm)
          (fws.apiFlight.uniqueId, fws)
        }).toMap
      case Some(timeWindowEnd) =>
        val windowEndMillis = timeWindowEnd.millisSinceEpoch
        sm.flightWithSplits.collect {
          case fwsm if fwsm.flight.map(fm => fm.pcpTime.getOrElse(0L)).getOrElse(0L) <= windowEndMillis =>
            val fws = flightWithSplitsFromMessage(fwsm)
            (fws.apiFlight.uniqueId, fws)
        }.toMap
    }

    logInfo(s"Unwrapping minutes messages")
    val crunchMinutes = sm.crunchMinutes.map(cmm => {
      val cm = crunchMinuteFromMessage(cmm)
      (cm.key, cm)
    }).toMap
    val staffMinutes = sm.staffMinutes.map(cmm => {
      val sm = staffMinuteFromMessage(cmm)
      (sm.key, sm)
    }).toMap
    logInfo(s"Finished unwrapping messages")
    PortState(flights, crunchMinutes, staffMinutes)
  }

  def crunchMinuteFromMessage(cmm: CrunchMinuteMessage): CrunchMinute = CrunchMinute(
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

  def staffMinuteFromMessage(smm: StaffMinuteMessage): StaffMinute = StaffMinute(
    terminalName = smm.terminalName.getOrElse(""),
    minute = smm.minute.getOrElse(0L),
    shifts = smm.shifts.getOrElse(0),
    fixedPoints = smm.fixedPoints.getOrElse(0),
    movements = smm.movements.getOrElse(0)
  )

  def flightWithSplitsFromMessage(fm: FlightWithSplitsMessage): ApiFlightWithSplits = {
    ApiFlightWithSplits(
      FlightMessageConversion.flightMessageToApiFlight(fm.flight.get),
      fm.splits.map(sm => splitMessageToApiSplits(sm)).toSet,
      None
    )
  }
}

object SplitsConversion {
  def splitMessageToApiSplits(sm: SplitMessage): Splits = {
    val splitSource = sm.source.getOrElse("") match {
      case SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages_Old => SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages
      case s => s
    }

    Splits(
      sm.paxTypeAndQueueCount.map(ptqcm => ApiPaxTypeAndQueueCount(
        PaxType(ptqcm.paxType.getOrElse("")),
        ptqcm.queueType.getOrElse(""),
        ptqcm.paxValue.getOrElse(0d),
        None
      )).toSet,
      splitSource,
      sm.eventType,
      SplitStyle(sm.style.getOrElse(""))
    )
  }
}

case object GetPortWorkload
