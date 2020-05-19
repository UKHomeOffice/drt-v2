package actors

import actors.PortStateMessageConversion._
import actors.acking.AckingReceiver.{Ack, StreamCompleted, StreamFailure, StreamInitialized}
import actors.queues.CrunchQueueActor.UpdatedMillis
import akka.actor._
import akka.pattern.ask
import akka.persistence._
import akka.util.Timeout
import drt.shared.CrunchApi._
import drt.shared.FlightsApi.{FlightsWithSplits, FlightsWithSplitsDiff}
import drt.shared._
import drt.shared.api.Arrival
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import server.protobuf.messages.CrunchState._
import server.protobuf.messages.FlightsMessage.UniqueArrivalMessage
import services.SDate
import services.crunch.deskrecs.GetFlights

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._
import scala.language.postfixOps


class FlightsStateActor(initialMaybeSnapshotInterval: Option[Int],
                        initialSnapshotBytesThreshold: Int,
                        name: String,
                        val now: () => SDateLike,
                        expireAfterMillis: Int) extends PersistentActor with RecoveryActorLike with PersistentDrtActor[FlightsWithSplits] {
  override def persistenceId: String = name

  override val maybeSnapshotInterval: Option[Int] = initialMaybeSnapshotInterval
  override val snapshotBytesThreshold: Int = initialSnapshotBytesThreshold
  override val recoveryStartMillis: MillisSinceEpoch = now().millisSinceEpoch

  implicit val ec: ExecutionContextExecutor = context.dispatcher

  val log: Logger = LoggerFactory.getLogger(s"$name-$getClass")

  var state: FlightsWithSplits = FlightsWithSplits.empty

  var flightMinutesBuffer: Set[MillisSinceEpoch] = Set[MillisSinceEpoch]()
  var crunchSourceIsReady: Boolean = true
  var maybeCrunchActor: Option[ActorRef] = None

  def initialState: FlightsWithSplits = FlightsWithSplits.empty

  def purgeExpired(): Unit = state = state.scheduledSince(expiryTimeMillis)

  def expiryTimeMillis: MillisSinceEpoch = now().addMillis(-1 * expireAfterMillis).millisSinceEpoch

  override def postRecoveryComplete(): Unit = {
    purgeExpired()
    log.info(s"Recovery complete. ${state.flights.size} flights")
  }

  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case FlightsWithSplitsMessage(flightMessages) => state = FlightsWithSplits(flightsFromMessages(flightMessages))
  }

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case diff@FlightsWithSplitsDiffMessage(_, removals, updates) =>
      state = state -- removals.map(uniqueArrivalFromMessage)
      state = state ++ flightsFromMessages(updates)
      logRecoveryState()

      bytesSinceSnapshotCounter += diff.serializedSize
      messagesPersistedSinceSnapshotCounter += 1
  }

  def logRecoveryState(): Unit = {
    log.debug(s"Recovery: state contains ${state.flights.size} flights")
  }

  override def stateToMessage: GeneratedMessage = FlightMessageConversion.flightsToMessage(state.flights.toMap.values)

  override def receiveCommand: Receive = {
    case SetCrunchQueueActor(crunchActor) =>
      log.info(s"Received crunchSourceActor")
      maybeCrunchActor = Option(crunchActor)

    case SetCrunchSourceReady =>
      crunchSourceIsReady = true
      context.self ! HandleCrunchRequest

    case HandleCrunchRequest => handlePaxMinuteChangeNotification()

    case StreamInitialized => sender() ! Ack

    case StreamCompleted => log.info(s"Stream completed")

    case StreamFailure(t) => log.error(s"Stream failed", t)

    case flightUpdates: FlightsWithSplitsDiff =>
      if (flightUpdates.nonEmpty)
        handleDiff(flightUpdates)
      else
        sender() ! Ack

    case GetFlightsForTerminal(startMillis, endMillis, terminal) =>
      log.debug(s"Received GetFlightsForTerminal Request from ${SDate(startMillis).toISOString()} to ${SDate(endMillis).toISOString()} for $terminal")
      sender() ! state.forTerminal(terminal).window(startMillis, endMillis)

    case GetUpdatesSince(sinceMillis, startMillis, endMillis) =>
      sender() ! state.window(startMillis, endMillis).updatedSince(sinceMillis)

    case GetFlights(startMillis, endMillis) =>
      log.debug(s"Received GetFlights request from ${SDate(startMillis).toISOString()} to ${SDate(endMillis).toISOString()}")
      sender() ! state.window(startMillis, endMillis)

    case SaveSnapshotSuccess(SnapshotMetadata(_, _, _)) =>
      log.info("Snapshot success")

    case SaveSnapshotFailure(md, cause) =>
      log.error(s"Save snapshot failure: $md", cause)

    case DeleteSnapshotsSuccess(_) =>
      log.info(s"Purged snapshots")

    case StreamCompleted => log.warn("Received shutdown")

    case unexpected => log.error(s"Received unexpected message $unexpected")
  }

  def handleDiff(flightUpdates: FlightsWithSplitsDiff): Unit = {
    val (updatedState, updatedMinutes) = flightUpdates.applyTo(state, now().millisSinceEpoch)
    state = updatedState
    purgeExpired()

    if (updatedMinutes.nonEmpty) {
      flightMinutesBuffer ++= updatedMinutes
      self ! HandleCrunchRequest
    }

    val diffMsg = diffMessageForFlights(flightUpdates.flightsToUpdate, flightUpdates.arrivalsToRemove)
    persistAndMaybeSnapshot(diffMsg, Option((sender(), Ack)))
  }

  def handlePaxMinuteChangeNotification(): Unit = (maybeCrunchActor, flightMinutesBuffer.nonEmpty, crunchSourceIsReady) match {
    case (Some(crunchActor), true, true) =>
      crunchSourceIsReady = false
      val updatedMillisToSend = flightMinutesBuffer
      flightMinutesBuffer = Set()
      crunchActor
        .ask(UpdatedMillis(updatedMillisToSend))(new Timeout(15 seconds))
        .recover {
          case e =>
            log.error("Error updated minutes to crunch queue actor. Putting the minutes back in the buffer to try again", e)
            flightMinutesBuffer = flightMinutesBuffer ++ updatedMillisToSend
        }
        .onComplete { _ =>
          context.self ! SetCrunchSourceReady
        }
    case _ =>
  }

  def diffMessageForFlights(updates: List[ApiFlightWithSplits],
                            removals: List[Arrival]): FlightsWithSplitsDiffMessage = FlightsWithSplitsDiffMessage(
    createdAt = Option(now().millisSinceEpoch),
    removals = removals.map { arrival =>
      val ua = arrival.unique
      UniqueArrivalMessage(Option(ua.number), Option(ua.terminal.toString), Option(ua.scheduled))
    },
    updates = updates.map(FlightMessageConversion.flightWithSplitsToMessage)
    )

  def uniqueArrivalFromMessage(uam: UniqueArrivalMessage): UniqueArrival =
    UniqueArrival(uam.getNumber, uam.getTerminalName, uam.getScheduled)
}
