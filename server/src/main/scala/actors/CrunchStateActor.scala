package actors

import actors.PortStateMessageConversion._
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

import scala.collection.immutable.SortedMap
import scala.language.postfixOps

class CrunchStateActor(initialMaybeSnapshotInterval: Option[Int],
                       initialSnapshotBytesThreshold: Int,
                       name: String,
                       portQueues: Map[TerminalName, Seq[QueueName]],
                       now: () => SDateLike,
                       expireAfterMillis: MillisSinceEpoch,
                       purgePreviousSnapshots: Boolean) extends PersistentActor with RecoveryActorLike with PersistentDrtActor[Option[PortState]] {
  override def persistenceId: String = name

  override val maybeSnapshotInterval: Option[Int] = initialMaybeSnapshotInterval
  override val snapshotBytesThreshold: Int = initialSnapshotBytesThreshold

  val log: Logger = LoggerFactory.getLogger(s"$name-$getClass")

  def logInfo(msg: String, level: String = "info"): Unit = if (name.isEmpty) log.info(msg) else log.info(s"$name $msg")

  def logDebug(msg: String, level: String = "info"): Unit = if (name.isEmpty) log.debug(msg) else log.debug(s"$name $msg")

  var recoveryFlights: Map[Int, ApiFlightWithSplits] = Map()
  var recoveryCrunchMinutes: List[(TQM, CrunchMinute)] = List()
  var recoveryStaffMinutes: List[(TM, StaffMinute)] = List()
  var state: Option[PortState] = initialState

  def initialState: Option[PortState] = None

  def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case snapshot: CrunchStateSnapshotMessage =>
      setRecoveryStateFromSnapshot(snapshot)
  }

  def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case diff: CrunchDiffMessage =>
      recoveryFlights = recoveryFlights -- diff.flightIdsToRemove ++ flightsFromMessages(diff.flightsToUpdate, None)
      recoveryCrunchMinutes = recoveryCrunchMinutes ++ crunchMinutesFromMessages(diff.crunchMinutesToUpdate)
      recoveryStaffMinutes = recoveryStaffMinutes ++ staffMinutesFromMessages(diff.staffMinutesToUpdate)
      bytesSinceSnapshotCounter += diff.serializedSize
      messagesPersistedSinceSnapshotCounter += 1
  }

  override def postRecoveryComplete(): Unit = {
    val newState = PortState(recoveryFlights, SortedMap[TQM, CrunchMinute]() ++ recoveryCrunchMinutes, SortedMap[TM, StaffMinute]() ++ recoveryStaffMinutes)
    state = Option(newState)
  }

  def logRecoveryState(optionalState: Option[PortState]): Unit = optionalState match {
    case None => logDebug(s"Recovery: state is None")
    case Some(s) =>
      val apiCount = s.flights.count {
        case (_, f) => f.splits.exists {
          case Splits(_, SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages, _, _) => true
          case _ => false
        }
      }
      logDebug(s"Recovery: state contains ${s.flights.size} flights " +
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

    case PortStateWithDiff(_, diff, diffMsg) =>
      logInfo(s"Received port state diff with diffMsg. ${diff.flightRemovals.size} flight removals, ${diff.flightUpdates.size} flight updates, ${diff.crunchMinuteUpdates.size} crunch updates, ${diff.staffMinuteUpdates.size} staff updates")
      updatePortStateFromDiff(diff)
      persistAndMaybeSnapshot(diffMsg)

    case GetState =>
      logDebug(s"Received GetState request. Replying with ${state.map(s => s"PortState containing ${s.crunchMinutes.size} crunch minutes")}")
      sender() ! state

    case GetPortState(start, end) =>
      logInfo(s"Received GetPortState Request from ${SDate(start).toISOString()} to ${SDate(end).toISOString()}")
      sender() ! stateForPeriod(start, end)

    case GetPortStateForTerminal(start, end, terminalName) =>
      logInfo(s"Received GetPortState Request from ${SDate(start).toISOString()} to ${SDate(end).toISOString()}")
      sender() ! stateForPeriodForTerminal(start, end, terminalName)

    case GetUpdatesSince(millis, start, end) =>
      val updates = state match {
        case Some(fullPortState) => fullPortState.window(SDate(start), SDate(end), portQueues).updates(millis)
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

  def updatePortStateFromDiff(diff: PortStateDiff): Unit = {
    state = state match {
      case None => Option(PortState(diff.flightUpdates, SortedMap[TQM, CrunchMinute]() ++ diff.crunchMinuteUpdates, SortedMap[TM, StaffMinute]() ++ diff.staffMinuteUpdates))
      case Some(PortState(fts, cms, sms)) =>
        Option(PortState(fts -- diff.flightRemovals.map(_.flightKey.uniqueId) ++ diff.flightUpdates, cms ++ diff.crunchMinuteUpdates, sms ++ diff.staffMinuteUpdates))
    }
  }

  def stateForPeriod(start: MillisSinceEpoch, end: MillisSinceEpoch): Option[PortState] = state
    .map(_.window(SDate(start), SDate(end), portQueues))

  def stateForPeriodForTerminal(start: MillisSinceEpoch, end: MillisSinceEpoch, terminalName: TerminalName): Option[PortState] = state
    .map(_.windowWithTerminalFilter(SDate(start), SDate(end), portQueues.filterKeys(_ == terminalName)))

  def setRecoveryStateFromSnapshot(snapshot: CrunchStateSnapshotMessage, timeWindowEnd: Option[SDateLike] = None): Unit = {
    recoveryFlights = flightsFromMessages(snapshot.flightWithSplits, timeWindowEnd).toMap
    recoveryCrunchMinutes = crunchMinutesFromMessages(snapshot.crunchMinutes)
    recoveryStaffMinutes = staffMinutesFromMessages(snapshot.staffMinutes)
  }

  def crunchDiffFromMessage(diffMessage: CrunchDiffMessage): (Set[Int], Set[ApiFlightWithSplits], Set[CrunchMinute], Set[StaffMinute]) = (
    diffMessage.flightIdsToRemove.toSet,
    diffMessage.flightsToUpdate.map(flightWithSplitsFromMessage).toSet,
    diffMessage.crunchMinutesToUpdate.map(crunchMinuteFromMessage).toSet,
    diffMessage.staffMinutesToUpdate.map(staffMinuteFromMessage).toSet
  )
}
