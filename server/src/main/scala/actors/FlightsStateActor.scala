package actors

import actors.PortStateMessageConversion._
import actors.acking.AckingReceiver.{Ack, StreamCompleted, StreamFailure, StreamInitialized}
import actors.daily.{RequestAndTerminate, RequestAndTerminateActor}
import actors.pointInTime.{CrunchStateReadActor, FlightsStateReadActor}
import actors.queues.CrunchQueueActor.UpdatedMillis
import akka.actor._
import akka.pattern.{ask, pipe}
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
import services.crunch.deskrecs.{FlightsRequest, GetFlights, GetStateForDateRange, GetStateForTerminalDateRange}

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._
import scala.language.postfixOps


object FlightsStateActor {
  def isNonLegacyRequest(pointInTime: SDateLike, legacyDataCutoff: SDateLike): Boolean =
    pointInTime.millisSinceEpoch >= legacyDataCutoff.millisSinceEpoch

  def tempPitActorProps(pointInTime: SDateLike,
                        message: DateRangeLike,
                        now: () => SDateLike,
                        queues: Map[Terminal, Seq[Queue]],
                        expireAfterMillis: Int,
                        legacyDataCutoff: SDateLike): Props = {
    if (isNonLegacyRequest(pointInTime, legacyDataCutoff))
      Props(new FlightsStateReadActor(now, expireAfterMillis, pointInTime.millisSinceEpoch, queues, legacyDataCutoff))
    else
      Props(new CrunchStateReadActor(1000, pointInTime, expireAfterMillis, queues, message.from, message.to))
  }
}

class FlightsStateActor(val now: () => SDateLike, expireAfterMillis: Int, queues: Map[Terminal, Seq[Queue]], legacyDataCutoff: SDateLike)
  extends PersistentActor with RecoveryActorLike with PersistentDrtActor[FlightsWithSplits] {

  import FlightsStateActor._

  override def persistenceId: String = "flights-state-actor"

  override val maybeSnapshotInterval: Option[Int] = Option(5000)
  override val snapshotBytesThreshold: Int = Sizes.oneMegaByte
  override val recoveryStartMillis: MillisSinceEpoch = now().millisSinceEpoch

  implicit val ec: ExecutionContextExecutor = context.dispatcher

  val log: Logger = LoggerFactory.getLogger(getClass)

  var state: FlightsWithSplits = FlightsWithSplits.empty

  var flightMinutesBuffer: Set[MillisSinceEpoch] = Set[MillisSinceEpoch]()
  var crunchSourceIsReady: Boolean = true
  var maybeCrunchQueueActor: Option[ActorRef] = None

  implicit val timeout: Timeout = new Timeout(30 seconds)
  val killActor: ActorRef = context.system.actorOf(Props(new RequestAndTerminateActor()))//, "flights-state-kill-actor")

  def initialState: FlightsWithSplits = FlightsWithSplits.empty

  def purgeExpired(): Unit = state = state.scheduledSince(expiryTimeMillis)

  def expiryTimeMillis: MillisSinceEpoch = now().addMillis(-1 * expireAfterMillis).millisSinceEpoch

  override def postRecoveryComplete(): Unit = {
    purgeExpired()
    log.info(s"Recovery complete. ${state.flights.size} flights")
  }

  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case FlightsWithSplitsMessage(flightMessages) =>
      log.info(s"Processing snapshot message")
      setStateFromSnapshot(flightMessages)
  }

  def setStateFromSnapshot(flightMessages: Seq[FlightWithSplitsMessage]): Unit = {
    state = FlightsWithSplits(flightsFromMessages(flightMessages))
  }

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case diff: FlightsWithSplitsDiffMessage => handleDiffMessage(diff)
  }

  def handleDiffMessage(diff: FlightsWithSplitsDiffMessage): Unit = {
    state = state -- diff.removals.map(uniqueArrivalFromMessage)
    state = state ++ flightsFromMessages(diff.updates)
    logRecoveryState()
  }

  def logRecoveryState(): Unit = {
    log.debug(s"Recovery: state contains ${state.flights.size} flights")
  }

  override def stateToMessage: GeneratedMessage = FlightMessageConversion.flightsToMessage(state.flights.toMap.values)

  override def receiveCommand: Receive = {
    case SetCrunchQueueActor(crunchActor) =>
      log.info(s"Received crunchSourceActor")
      maybeCrunchQueueActor = Option(crunchActor)

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

    case PointInTimeQuery(pitMillis, request) =>
      replyWithPointInTimeQuery(SDate(pitMillis), request)

    case request: DateRangeLike if SDate(request.to).isHistoricDate(now()) =>
      replyWithDayViewQuery(request)

    case GetStateForDateRange(startMillis, endMillis) =>
      sender() ! state.window(startMillis, endMillis)

    case GetStateForTerminalDateRange(startMillis, endMillis, terminal) =>
      sender() ! state.forTerminal(terminal).window(startMillis, endMillis)

    case GetUpdatesSince(sinceMillis, startMillis, endMillis) =>
      sender() ! state.window(startMillis, endMillis).updatedSince(sinceMillis)

    case SaveSnapshotSuccess(SnapshotMetadata(_, _, _)) =>
      log.info("Snapshot success")

    case SaveSnapshotFailure(md, cause) =>
      log.error(s"Save snapshot failure: $md", cause)

    case unexpected => log.error(s"Received unexpected message $unexpected")
  }

  def replyWithDayViewQuery(message: DateRangeLike): Unit = {
    val pointInTime = SDate(message.to).addHours(4)
    replyWithPointInTimeQuery(pointInTime, message)
  }

  def replyWithPointInTimeQuery(pointInTime: SDateLike, message: DateRangeLike): Unit = {
    val finalMessage = if (isNonLegacyRequest(pointInTime, legacyDataCutoff)) message else toLegacyMessage(message)
    val tempActor = tempPointInTimeActor(pointInTime, finalMessage)
    killActor
      .ask(RequestAndTerminate(tempActor, finalMessage))
      .pipeTo(sender())
  }

  def toLegacyMessage(message: DateRangeLike): DateRangeLike with FlightsRequest = message match {
    case GetStateForDateRange(from, to) => GetFlights(from, to)
    case GetStateForTerminalDateRange(from, to, terminal) => GetFlightsForTerminal(from, to, terminal)
  }

  def tempPointInTimeActor(pointInTime: SDateLike, message: DateRangeLike): ActorRef =
    context.actorOf(tempPitActorProps(pointInTime, message, now, queues, expireAfterMillis, legacyDataCutoff))

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

  def handlePaxMinuteChangeNotification(): Unit = (maybeCrunchQueueActor, flightMinutesBuffer.nonEmpty, crunchSourceIsReady) match {
    case (Some(crunchQueueActor), true, true) =>
      crunchSourceIsReady = false
      val updatedMillisToSend = flightMinutesBuffer
      flightMinutesBuffer = Set()
      crunchQueueActor
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
