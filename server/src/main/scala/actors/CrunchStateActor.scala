package actors

import actors.PortStateMessageConversion._
import akka.actor._
import akka.persistence._
import scalapb.GeneratedMessage
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
                       purgePreviousSnapshots: Boolean) extends PersistentActor with RecoveryActorLike with PersistentDrtActor[PortStateMutable] {
  override def persistenceId: String = name

  override val maybeSnapshotInterval: Option[Int] = initialMaybeSnapshotInterval
  override val snapshotBytesThreshold: Int = initialSnapshotBytesThreshold

  val log: Logger = LoggerFactory.getLogger(s"$name-$getClass")

  def logInfo(msg: String, level: String = "info"): Unit = if (name.isEmpty) log.info(msg) else log.info(s"$name $msg")

  def logDebug(msg: String, level: String = "info"): Unit = if (name.isEmpty) log.debug(msg) else log.debug(s"$name $msg")

  var state: PortStateMutable = initialState

  def initialState: PortStateMutable = PortStateMutable.empty

  def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case snapshot: CrunchStateSnapshotMessage => setStateFromSnapshot(snapshot)
  }

  def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case diff: CrunchDiffMessage =>
      applyDiff(diff)
      logRecoveryState()
      bytesSinceSnapshotCounter += diff.serializedSize
      messagesPersistedSinceSnapshotCounter += 1
  }

  def logRecoveryState(): Unit = {
    val apiCount = state.flights.count {
      case (_, f) => f.splits.exists {
        case Splits(_, SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages, _, _) => true
        case _ => false
      }
    }
    logDebug(s"Recovery: state contains ${state.flights.size} flights " +
      s"with $apiCount Api splits " +
      s", ${state.crunchMinutes.size} crunch minutes " +
      s", ${state.staffMinutes.size} staff minutes ")
  }

  override def postSaveSnapshot(): Unit = if (purgePreviousSnapshots) {
    val maxSequenceNr = lastSequenceNr
    logInfo(s"Purging snapshots with sequence number < $maxSequenceNr")
    deleteSnapshots(SnapshotSelectionCriteria(maxSequenceNr = maxSequenceNr))
  }

  override def stateToMessage: GeneratedMessage = portStateToSnapshotMessage(state)

  override def receiveCommand: Receive = {
    case PortStateWithDiff(_, _, CrunchDiffMessage(_, _, fr, fu, cu, su, _)) if fr.isEmpty && fu.isEmpty && cu.isEmpty && su.isEmpty =>
      log.info(s"Received port state with empty diff")

    case PortStateWithDiff(_, _, diffMsg) =>
      logInfo(s"Received port state with diff")
      applyDiff(diffMsg)
      persistAndMaybeSnapshot(diffMsg)

    case GetState =>
      logDebug(s"Received GetState request. Replying with PortState containing ${state.crunchMinutes.size} crunch minutes")
      sender() ! Option(state.immutable)

    case GetPortState(start, end) =>
      logInfo(s"Received GetPortState Request from ${SDate(start).toISOString()} to ${SDate(end).toISOString()}")
      sender() ! stateForPeriod(start, end)

    case GetPortStateForTerminal(start, end, terminalName) =>
      logInfo(s"Received GetPortState Request from ${SDate(start).toISOString()} to ${SDate(end).toISOString()}")
      sender() ! stateForPeriodForTerminal(start, end, terminalName)

    case GetUpdatesSince(millis, start, end) =>
      val updates = state.window(SDate(start), SDate(end), portQueues).updates(millis)
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

  def stateForPeriod(start: MillisSinceEpoch, end: MillisSinceEpoch): Option[PortState] = Option(state.window(SDate(start), SDate(end), portQueues))

  def stateForPeriodForTerminal(start: MillisSinceEpoch, end: MillisSinceEpoch, terminalName: TerminalName): Option[PortState] = Option(state.windowWithTerminalFilter(SDate(start), SDate(end), portQueues.filterKeys(_ == terminalName)))

  def setStateFromSnapshot(snapshot: CrunchStateSnapshotMessage, timeWindowEnd: Option[SDateLike] = None): Unit = {
    val newState = snapshotMessageToState(snapshot, timeWindowEnd)
    state.flights.empty ++= newState.flights
    state.staffMinutes.empty ++= newState.staffMinutes
    state.crunchMinutes.empty ++= newState.crunchMinutes
  }

  def applyDiff(cdm: CrunchDiffMessage): Unit = {
    val (flightRemovals, flightUpdates, crunchMinuteUpdates, staffMinuteUpdates): (Set[Int], Set[ApiFlightWithSplits], Set[CrunchMinute], Set[StaffMinute]) = crunchDiffFromMessage(cdm)
    //    println(s"flight updates:\n${flightUpdates.mkString("\n")}")
    state.applyFlightsWithSplitsDiff(flightRemovals, flightUpdates, now().millisSinceEpoch)
    state.applyCrunchDiff(crunchMinuteUpdates, now().millisSinceEpoch)
    state.applyStaffDiff(staffMinuteUpdates, now().millisSinceEpoch)
  }

  //  def applyDiff(cdm: CrunchDiffMessage, existingState: Option[PortStateMutable]): Unit = {
  //    val (flightRemovals, flightUpdates, crunchMinuteUpdates, staffMinuteUpdates): (Set[Int], Set[ApiFlightWithSplits], Set[CrunchMinute], Set[StaffMinute]) = crunchDiffFromMessage(cdm)
  //    println(s"flight updates:\n${flightUpdates.mkString("\n")}")
  //    existingState.foreach { ps =>
  //      ps.applyFlightsWithSplitsDiff(flightRemovals, flightUpdates, SDate.now().millisSinceEpoch)
  //      ps.applyCrunchDiff(crunchMinuteUpdates, SDate.now().millisSinceEpoch)
  //      ps.applyStaffDiff(staffMinuteUpdates, SDate.now().millisSinceEpoch)
  //      state = Option(ps)
  //    }
  //  }

  def crunchDiffFromMessage(diffMessage: CrunchDiffMessage): (Set[Int], Set[ApiFlightWithSplits], Set[CrunchMinute], Set[StaffMinute]) = (
    diffMessage.flightIdsToRemove.toSet,
    diffMessage.flightsToUpdate.map(flightWithSplitsFromMessage).toSet,
    diffMessage.crunchMinutesToUpdate.map(crunchMinuteFromMessage).toSet,
    diffMessage.staffMinutesToUpdate.map(staffMinuteFromMessage).toSet
  )
}
