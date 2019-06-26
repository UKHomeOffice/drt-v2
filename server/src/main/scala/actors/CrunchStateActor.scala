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

    case PortStateWithDiff(portState, _, diff) =>
      logInfo(s"Received port state with diff")
      updateStateFromPortState(portState)
      persistAndMaybeSnapshot(diff)

    case GetState =>
      logDebug(s"Received GetState request. Replying with ${state.map(s => s"PortState containing ${s.crunchMinutes.size} crunch minutes")}")
      sender() ! state

    case GetPortState(start, end, maybeTerminalName) =>
      logInfo(s"Received GetPortState Request from ${SDate(start).toISOString()} to ${SDate(end).toISOString()}")
      sender() ! stateForPeriod(start, end, maybeTerminalName)

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

  def stateForPeriod(start: MillisSinceEpoch, end: MillisSinceEpoch, maybeTerminalName: Option[TerminalName]): Option[PortState] = {
    val queues = maybeTerminalName match {
      case None => portQueues
      case Some(tn) => portQueues.filterKeys(_ == tn)
    }
    state.map(_.window(SDate(start), SDate(end), queues))
  }

  def setStateFromSnapshot(snapshot: CrunchStateSnapshotMessage, timeWindowEnd: Option[SDateLike] = None): Unit = {
    state = Option(snapshotMessageToState(snapshot, timeWindowEnd))
  }

  def stateFromDiff(cdm: CrunchDiffMessage, existingState: Option[PortState]): Option[PortState] = {
    val (flightRemovals, flightUpdates, crunchMinuteUpdates, staffMinuteUpdates): (Set[Int], Set[ApiFlightWithSplits], Set[CrunchMinute], Set[StaffMinute]) = crunchDiffFromMessage(cdm)
    val newState = existingState match {
      case None =>
        logDebug(s"Creating an empty PortState to apply CrunchDiff")
        Option(PortState(
          flights = applyFlightsWithSplitsDiff(flightRemovals, flightUpdates, Map(), SDate.now().millisSinceEpoch),
          crunchMinutes = applyCrunchDiff(crunchMinuteUpdates, Map(), SDate.now().millisSinceEpoch),
          staffMinutes = applyStaffDiff(staffMinuteUpdates, Map(), SDate.now().millisSinceEpoch)
        ))
      case Some(ps) =>
        val newPortState = PortState(
          flights = applyFlightsWithSplitsDiff(flightRemovals, flightUpdates, ps.flights, SDate.now().millisSinceEpoch),
          crunchMinutes = applyCrunchDiff(crunchMinuteUpdates, ps.crunchMinutes, SDate.now().millisSinceEpoch),
          staffMinutes = applyStaffDiff(staffMinuteUpdates, ps.staffMinutes, SDate.now().millisSinceEpoch))
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
}
