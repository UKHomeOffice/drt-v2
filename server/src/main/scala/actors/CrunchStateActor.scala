package actors

import actors.FlightMessageConversion.flightWithSplitsFromMessage
import actors.PortStateMessageConversion._
import actors.acking.AckingReceiver.StreamCompleted
import actors.restore.RestorerWithLegacy
import akka.actor._
import akka.persistence._
import drt.shared.CrunchApi._
import drt.shared.Queues.Queue
import drt.shared.Terminals.Terminal
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import server.protobuf.messages.CrunchState._
import server.protobuf.messages.FlightsMessage.UniqueArrivalMessage
import services.SDate


class CrunchStateActor(initialMaybeSnapshotInterval: Option[Int],
                       initialSnapshotBytesThreshold: Int,
                       name: String,
                       portQueues: Map[Terminal, Seq[Queue]],
                       val now: () => SDateLike,
                       expireAfterMillis: Int,
                       purgePreviousSnapshots: Boolean,
                       forecastMaxMillis: () => MillisSinceEpoch) extends PersistentActor with RecoveryActorLike with PersistentDrtActor[PortState] {
  override def persistenceId: String = name

  override val maybeSnapshotInterval: Option[Int] = initialMaybeSnapshotInterval
  override val snapshotBytesThreshold: Int = initialSnapshotBytesThreshold
  override val recoveryStartMillis: MillisSinceEpoch = now().millisSinceEpoch

  val log: Logger = LoggerFactory.getLogger(s"$name-$getClass")

  def logInfo(msg: String): Unit = if (name.isEmpty) log.info(msg) else log.info(s"$name $msg")

  def logDebug(msg: String): Unit = if (name.isEmpty) log.debug(msg) else log.debug(s"$name $msg")

  val restorer = new RestorerWithLegacy[Int, UniqueArrival, ApiFlightWithSplits]

  var state: PortState = initialState

  def initialState: PortState = PortState.empty

  def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case snapshot: CrunchStateSnapshotMessage => setStateFromSnapshot(snapshot, timeWindowEnd = Option(SDate(forecastMaxMillis())))
  }

  def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case diff: CrunchDiffMessage =>
      applyRecoveryDiff(diff, forecastMaxMillis())
      logRecoveryState()
  }

  override def postRecoveryComplete(): Unit = {
    restorer.finish()
    state = state.copy(flights = state.flights ++ restorer.items)
    restorer.clear()

    state.purgeOlderThanDate(now().millisSinceEpoch - expireAfterMillis)

    super.postRecoveryComplete()
  }

  def logRecoveryState(): Unit = {
    logDebug(s"Recovery: state contains ${state.flights.size} flights " +
      s", ${state.crunchMinutes.size} crunch minutes " +
      s", ${state.staffMinutes.size} staff minutes ")
  }

  override def stateToMessage: GeneratedMessage = portStateToSnapshotMessage(state)

  override def receiveCommand: Receive = {

    case GetState =>

      log.error(s"Received Legacy Query")
      sender() ! Option(state)

    case SaveSnapshotSuccess(SnapshotMetadata(_, seqNr, _)) =>
      logInfo("Snapshot success")
      if (purgePreviousSnapshots) {
        logInfo(s"Purging previous snapshots (with sequence number < $seqNr)")
        deleteSnapshots(SnapshotSelectionCriteria(maxSequenceNr = seqNr - 1))
      }

    case SaveSnapshotFailure(md, cause) =>
      log.error(s"Save snapshot failure: $md", cause)

    case DeleteSnapshotsSuccess(_) =>
      logInfo(s"Purged snapshots")

    case StreamCompleted => log.warn("Received shutdown")

    case unexpected => log.error(s"Received unexpected message $unexpected")
  }

  def stateForPeriod(start: MillisSinceEpoch, end: MillisSinceEpoch): PortState = state.window(SDate(start), SDate(end))

  def setStateFromSnapshot(snapshot: CrunchStateSnapshotMessage, timeWindowEnd: Option[SDateLike] = None): Unit = {
    state = snapshotMessageToState(snapshot, timeWindowEnd)
  }

  def applyRecoveryDiff(cdm: CrunchDiffMessage, maxMillis: MillisSinceEpoch): Unit = {
    val (flightRemovals, flightUpdates, crunchMinuteUpdates, staffMinuteUpdates) = crunchDiffFromMessage(cdm, maxMillis)
    val nowMillis = now().millisSinceEpoch
    restorer.update(flightUpdates)
    restorer.removeLegacies(cdm.flightIdsToRemoveOLD)
    restorer.remove(flightRemovals)
    state = state.applyCrunchDiff(crunchMinuteUpdates, nowMillis)
    state = state.applyStaffDiff(staffMinuteUpdates, nowMillis)
  }

  def uniqueArrivalFromMessage(uam: UniqueArrivalMessage): UniqueArrival = {
    UniqueArrival(uam.getNumber, uam.getTerminalName, uam.getScheduled)
  }

  def crunchDiffFromMessage(diffMessage: CrunchDiffMessage, maxMillis: MillisSinceEpoch): (Seq[UniqueArrival], Seq[ApiFlightWithSplits], Seq[CrunchMinute], Seq[StaffMinute]) = (
    diffMessage.flightsToRemove.collect {
      case m if portQueues.contains(Terminal(m.getTerminalName)) => uniqueArrivalFromMessage(m)
    },
    diffMessage.flightsToUpdate.collect {
      case m if portQueues.contains(Terminal(m.getFlight.getTerminal)) && m.getFlight.getScheduled < maxMillis => flightWithSplitsFromMessage(m)
    },
    diffMessage.crunchMinutesToUpdate.collect {
      case m if portQueues.contains(Terminal(m.getTerminalName)) && m.getMinute < maxMillis => crunchMinuteFromMessage(m)
    },
    diffMessage.staffMinutesToUpdate.collect {
      case m if portQueues.contains(Terminal(m.getTerminalName)) && m.getMinute < maxMillis => staffMinuteFromMessage(m)
    }
  )
}
