package actors

import actors.FlightMessageConversion.flightWithSplitsFromMessage
import actors.PortStateMessageConversion._
import actors.acking.AckingReceiver.{Ack, StreamCompleted, StreamFailure, StreamInitialized}
import actors.restore.RestorerWithLegacy
import akka.actor._
import akka.pattern.AskableActorRef
import akka.persistence._
import akka.util.Timeout
import drt.shared.CrunchApi._
import drt.shared.FlightsApi.{FlightsWithSplits, FlightsWithSplitsDiff}
import drt.shared.Queues.Queue
import drt.shared.Terminals.Terminal
import drt.shared._
import drt.shared.api.Arrival
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import server.protobuf.messages.CrunchState._
import server.protobuf.messages.FlightsMessage.UniqueArrivalMessage
import services.SDate
import services.crunch.deskrecs.GetFlights

import scala.collection.mutable
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._
import scala.language.postfixOps


class FlightsStateActor(initialMaybeSnapshotInterval: Option[Int],
                        initialSnapshotBytesThreshold: Int,
                        name: String,
                        portQueues: Map[Terminal, Seq[Queue]],
                        val now: () => SDateLike,
                        expireAfterMillis: Int) extends PersistentActor with RecoveryActorLike with PersistentDrtActor[PortStateMutable] {
  override def persistenceId: String = name
  override val maybeSnapshotInterval: Option[Int] = initialMaybeSnapshotInterval
  override val snapshotBytesThreshold: Int = initialSnapshotBytesThreshold
  override val recoveryStartMillis: MillisSinceEpoch = now().millisSinceEpoch

  implicit val ec: ExecutionContextExecutor = context.dispatcher

  val log: Logger = LoggerFactory.getLogger(s"$name-$getClass")

  val restorer = new RestorerWithLegacy[Int, UniqueArrival, ApiFlightWithSplits]

  var state: PortStateMutable = initialState

  val flightMinutesBuffer: mutable.Set[MillisSinceEpoch] = mutable.Set[MillisSinceEpoch]()
  var crunchSourceIsReady: Boolean = true
  var maybeCrunchActor: Option[AskableActorRef] = None

  def initialState: PortStateMutable = PortStateMutable.empty

  def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case snapshot: CrunchStateSnapshotMessage => setStateFromSnapshot(snapshot)
  }

  def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case diff: CrunchDiffMessage =>
      applyRecoveryDiff(diff)
      logRecoveryState()
      bytesSinceSnapshotCounter += diff.serializedSize
      messagesPersistedSinceSnapshotCounter += 1
  }

  override def postRecoveryComplete(): Unit = {
    restorer.finish()
    state.flights ++= restorer.items
    restorer.clear()

    state.purgeOlderThanDate(now().millisSinceEpoch - expireAfterMillis)

    super.postRecoveryComplete()
  }

  def logRecoveryState(): Unit = {
    log.debug(s"Recovery: state contains ${state.flights.count} flights")
  }

  override def stateToMessage: GeneratedMessage = portStateToSnapshotMessage(state)

  override def receiveCommand: Receive = {
    case SetCrunchActor(crunchActor) =>
      log.info(s"Received crunchSourceActor")
      maybeCrunchActor = Option(crunchActor)

    case SetCrunchSourceReady =>
      crunchSourceIsReady = true
      context.self ! HandleCrunchRequest

    case HandleCrunchRequest =>
      handlePaxMinuteChangeNotification()

    case StreamInitialized => sender() ! Ack

    case StreamCompleted => log.info(s"Stream completed")

    case StreamFailure(t) => log.error(s"Stream failed", t)

    case flightUpdates@FlightsWithSplitsDiff(updates, removals) =>
      if (updates.nonEmpty || removals.nonEmpty) {
        applyDiff(updates, removals)

        val diff: PortStateDiff = flightUpdates.applyTo(state, now().millisSinceEpoch)

        if (diff.flightMinuteUpdates.nonEmpty) flightMinutesBuffer ++= diff.flightMinuteUpdates

        val diffMsg = diffMessageForFlights(updates, removals)
        persistAndMaybeSnapshot(diffMsg)

        handlePaxMinuteChangeNotification()
      }

      sender() ! Ack

    case GetState =>
      log.debug(s"Received GetState request. Replying with PortState containing ${state.crunchMinutes.count} crunch minutes")
      sender() ! Option(state.immutable)

    case GetPortState(0L, Long.MaxValue) =>
      log.debug(s"Received GetPortState request for all flights")
      sender() ! Option(FlightsWithSplits(state.flights.all))

    case GetPortState(startMillis, endMillis) =>
      log.debug(s"Received GetPortState request from ${SDate(startMillis).toISOString()} to ${SDate(endMillis).toISOString()}")
      sender() ! Option(flightsForPeriod(startMillis, endMillis))

    case GetPortStateForTerminal(start, end, terminal) =>
      log.debug(s"Received GetPortStateForTerminal Request from ${SDate(start).toISOString()} to ${SDate(end).toISOString()} for $terminal")
      sender() ! Option(flightsForPeriodForTerminal(start, end, terminal))

    case GetUpdatesSince(sinceMillis, start, end) =>
      val updates = state.flights.range(SDate(start), SDate(end)).filter(_._2.lastUpdated.getOrElse(0L) > sinceMillis)
      sender() ! FlightsWithSplits(updates)

    case GetFlights(startMillis, endMillis) =>
      log.debug(s"Received GetFlights request from ${SDate(startMillis).toISOString()} to ${SDate(endMillis).toISOString()}")
      sender() ! flightsForPeriod(startMillis, endMillis)

    case SaveSnapshotSuccess(SnapshotMetadata(_, _, _)) =>
      log.info("Snapshot success")

    case SaveSnapshotFailure(md, cause) =>
      log.error(s"Save snapshot failure: $md", cause)

    case DeleteSnapshotsSuccess(_) =>
      log.info(s"Purged snapshots")

    case StreamCompleted => log.warn("Received shutdown")

    case unexpected => log.error(s"Received unexpected message $unexpected")
  }

  def flightsForPeriod(start: MillisSinceEpoch, end: MillisSinceEpoch): FlightsWithSplits =
    FlightsWithSplits(state.window(SDate(start), SDate(end)).flights)

  private def handlePaxMinuteChangeNotification(): Unit = (maybeCrunchActor, flightMinutesBuffer.nonEmpty, crunchSourceIsReady) match {
    case (Some(crunchActor), true, true) =>
      crunchSourceIsReady = false
      crunchActor
        .ask(flightMinutesBuffer.toList)(new Timeout(10 minutes))
        .recover {
          case e =>
            log.error("Error sending minutes to crunch - non recoverable error. Terminating App.", e)
            System.exit(1)
        }
        .onComplete { _ =>
          context.self ! SetCrunchSourceReady
        }
      flightMinutesBuffer.clear()
    case _ =>
  }

  def diffMessageForFlights(updates: List[ApiFlightWithSplits],
                            removals: List[Arrival]): CrunchDiffMessage = CrunchDiffMessage(
    createdAt = Option(now().millisSinceEpoch),
    crunchStart = Option(0),
    flightsToRemove = removals.map { arrival =>
      val ua = arrival.unique
      UniqueArrivalMessage(Option(ua.number), Option(ua.terminal.toString), Option(ua.scheduled))
    },
    flightsToUpdate = updates.map(FlightMessageConversion.flightWithSplitsToMessage))

  def flightsForPeriodForTerminal(start: MillisSinceEpoch,
                                  end: MillisSinceEpoch,
                                  terminalName: Terminal): FlightsWithSplits =
    FlightsWithSplits(state
      .windowWithTerminalFilter(SDate(start), SDate(end), portQueues.keys.filter(_ == terminalName).toSeq)
      .flights)

  def setStateFromSnapshot(snapshot: CrunchStateSnapshotMessage): Unit = snapshotMessageToFlightsState(snapshot, state)

  def applyRecoveryDiff(cdm: CrunchDiffMessage): Unit = {
    val (flightRemovals, flightUpdates) = flightsDiffFromMessage(cdm)
    restorer.update(flightUpdates)
    restorer.removeLegacies(cdm.flightIdsToRemoveOLD)
    restorer.remove(flightRemovals)
  }

  def uniqueArrivalFromMessage(uam: UniqueArrivalMessage): UniqueArrival = {
    UniqueArrival(uam.getNumber, uam.getTerminalName, uam.getScheduled)
  }

  def applyDiff(updates: List[ApiFlightWithSplits], removals: List[Arrival]): Unit = {
    val nowMillis = now().millisSinceEpoch
    state.applyFlightsWithSplitsDiff(removals.map(_.unique), updates.map(fws => (fws.unique, fws)), nowMillis)

    state.purgeOlderThanDate(nowMillis - expireAfterMillis)
    state.purgeRecentUpdates(nowMillis - MilliTimes.oneMinuteMillis * 5)
  }

  def flightsDiffFromMessage(diffMessage: CrunchDiffMessage): (Seq[UniqueArrival], Seq[ApiFlightWithSplits]) = (
    flightsToRemoveFromMessages(diffMessage.flightsToRemove),
    flightsWithSplitsFromMessages(diffMessage.flightsToUpdate)
  )

  def flightsToRemoveFromMessages(uniqueArrivalMessages: Seq[UniqueArrivalMessage]): Seq[UniqueArrival] = uniqueArrivalMessages.collect {
    case m if portQueues.contains(Terminal(m.getTerminalName)) => uniqueArrivalFromMessage(m)
  }

  def flightsWithSplitsFromMessages(flightMessages: Seq[FlightWithSplitsMessage]): Seq[ApiFlightWithSplits] = flightMessages.collect {
    case m if portQueues.contains(Terminal(m.getFlight.getTerminal)) => flightWithSplitsFromMessage(m)
  }
}
