package actors

import actors.DrtStaticParameters.liveDaysAhead
import actors.acking.AckingReceiver.{Ack, StreamCompleted, StreamFailure, StreamInitialized}
import actors.daily.{RequestAndTerminate, RequestAndTerminateActor}
import actors.pointInTime.CrunchStateReadActor
import actors.queues.CrunchQueueActor.UpdatedMillis
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import drt.shared.CrunchApi._
import drt.shared.FlightsApi.{FlightsWithSplits, FlightsWithSplitsDiff}
import drt.shared.Queues.Queue
import drt.shared.Terminals.Terminal
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import services.SDate
import services.crunch.deskrecs.GetFlights
import services.graphstages.Crunch.{LoadMinute, Loads}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.language.postfixOps


object PortStateActor {
  def apply(now: () => SDateLike,
            liveCrunchStateProps: Props,
            forecastCrunchStateProps: Props,
            queues: Map[Terminal, Seq[Queue]])
           (implicit system: ActorSystem): ActorRef = {
    system.actorOf(Props(new PortStateActor(liveCrunchStateProps, forecastCrunchStateProps, now, liveDaysAhead, queues)), name = "port-state-actor")
  }
}

class PortStateActor(liveCrunchStateProps: Props,
                     forecastCrunchStateProps: Props,
                     now: () => SDateLike,
                     liveDaysAhead: Int,
                     queuesByTerminal: Map[Terminal, Seq[Queue]]) extends Actor {
  val log: Logger = LoggerFactory.getLogger(getClass)

  val portStateSnapshotInterval: Int = 1000

  implicit val ec: ExecutionContextExecutor = ExecutionContext.global

  implicit val timeout: Timeout = new Timeout(30 seconds)

  val liveCrunchStateActor: ActorRef = context.actorOf(liveCrunchStateProps, name = "crunch-live-state-actor")
  val forecastCrunchStateActor: ActorRef = context.actorOf(forecastCrunchStateProps, name = "crunch-forecast-state-actor")

  val state: PortStateMutable = PortStateMutable.empty

  val killActor: ActorRef = context.system.actorOf(Props(new RequestAndTerminateActor()))

  var maybeCrunchQueueActor: Option[ActorRef] = None
  var crunchSourceIsReady: Boolean = true
  var maybeSimActor: Option[ActorRef] = None
  var simulationActorIsReady: Boolean = true

  override def preStart(): Unit = {
    val eventualLiveState = liveCrunchStateActor.ask(GetState).mapTo[Option[PortState]]
    val eventualFcstState = forecastCrunchStateActor.ask(GetState).mapTo[Option[PortState]]
    for {
      liveState <- eventualLiveState
      fcstState <- eventualFcstState
    } yield mergePortStates(fcstState, liveState).foreach { initialPortState =>
      log.info(s"Setting initial PortState from live & forecast")
      state.crunchMinutes ++= initialPortState.crunchMinutes
      state.staffMinutes ++= initialPortState.staffMinutes
      state.flights ++= initialPortState.flights
    }
  }

  def mergePortStates(maybeForecastPs: Option[PortState],
                      maybeLivePs: Option[PortState]): Option[PortState] = (maybeForecastPs, maybeLivePs) match {
    case (None, None) => None
    case (Some(fps), None) =>
      log.info(s"We only have initial forecast port state")
      Option(fps)
    case (None, Some(lps)) =>
      log.info(s"We only have initial live port state")
      Option(lps)
    case (Some(fps), Some(lps)) =>
      log.info(s"Merging initial live & forecast port states. ${lps.flights.size} live flights, ${fps.flights.size} forecast flights")
      Option(PortState(
        fps.flights ++ lps.flights,
        fps.crunchMinutes ++ lps.crunchMinutes,
        fps.staffMinutes ++ lps.staffMinutes))
  }

  override def receive: Receive = {

    case SetCrunchQueueActor(crunchActor) =>
      log.info(s"Received crunchSourceActor")
      if (maybeCrunchQueueActor.isEmpty) maybeCrunchQueueActor = Option(crunchActor)

    case SetSimulationActor(simActor) =>
      log.info(s"Received simulationSourceActor")
      maybeSimActor = Option(simActor)

    case ps: PortState =>
      log.info(s"Received initial PortState")
      state.crunchMinutes ++= ps.crunchMinutes
      state.staffMinutes ++= ps.staffMinutes
      state.flights ++= ps.flights
      log.info(s"Finished setting state (${state.crunchMinutes.all.size} crunch minutes, ${state.staffMinutes.all.size} staff minutes, ${state.flights.all.size} flights)")
      sender() ! Ack

    case StreamInitialized => sender() ! Ack

    case StreamCompleted => log.info(s"Stream completed")

    case StreamFailure(t) => log.error(s"Stream failed", t)

    case flightsWithSplits: FlightsWithSplitsDiff =>
      log.info(s"Processing incoming FlightsWithSplits")

      val diff = flightsWithSplits.applyTo(state, nowMillis)

      if (diff.flightMinuteUpdates.nonEmpty) flightMinutesBuffer ++= diff.flightMinuteUpdates

      handleCrunchRequest()
      handleSimulationRequest()

      splitDiffAndSend(diff)

    case updates: PortStateMinutes[_, _] =>
      log.debug(s"Processing incoming PortStateMinutes ${updates.getClass}")

      val diff = updates.applyTo(state, nowMillis)

      if (diff.crunchMinuteUpdates.nonEmpty) loadMinutesBuffer = loadMinutesBuffer ++ crunchMinutesToLoads(diff)

      handleCrunchRequest()
      handleSimulationRequest()

      splitDiffAndSend(diff)

    case SetCrunchSourceReady =>
      crunchSourceIsReady = true
      context.self ! HandleCrunchRequest

    case SetSimulationSourceReady =>
      simulationActorIsReady = true
      context.self ! HandleSimulationRequest

    case HandleCrunchRequest =>
      handleCrunchRequest()

    case HandleSimulationRequest =>
      handleSimulationRequest()

    case GetState =>
      log.debug(s"Received GetState request. Replying with PortState containing ${state.crunchMinutes.count} crunch minutes")
      sender() ! Option(state.immutable)

    case PointInTimeQuery(millis, query) =>
      replyWithPointInTimeQuery(SDate(millis), query)

    case message: DateRangeLike if SDate(message.from).isHistoricDate(now()) =>
      replyWithDayViewQuery(message)

    case GetPortState(start, end) =>
      log.debug(s"Received GetPortState Request from ${SDate(start).toISOString()} to ${SDate(end).toISOString()}")
      sender() ! stateForPeriod(start, end)

    case GetPortStateForTerminal(start, end, terminal) =>
      log.debug(s"Received GetPortStateForTerminal Request from ${SDate(start).toISOString()} to ${SDate(end).toISOString()} for $terminal")
      sender() ! stateForPeriodForTerminal(start, end, terminal)

    case GetFlightsForTerminal(start, end, terminal) =>
      log.debug(s"Received GetFlightsForTerminal Request from ${SDate(start).toISOString()} to ${SDate(end).toISOString()} for $terminal")
      sender() ! FlightsWithSplits(stateForPeriodForTerminal(start, end, terminal).flights)

    case GetUpdatesSince(millis, start, end) =>
      val updates: Option[PortStateUpdates] = state.updates(millis, start, end)
      sender() ! updates

    case GetFlights(startMillis, endMillis) =>
      val start = SDate(startMillis)
      val end = SDate(endMillis)
      log.info(s"Got request for flights between ${start.toISOString()} - ${end.toISOString()}")
      sender() ! FlightsWithSplits(state.flights.range(start, end))

    case unexpected => log.warn(s"Got unexpected: $unexpected")
  }

  def replyWithDayViewQuery(message: DateRangeLike): Unit = {
    val pointInTime = SDate(message.to).addHours(4)
    replyWithPointInTimeQuery(pointInTime, message)
  }

  def replyWithPointInTimeQuery(pointInTime: SDateLike, message: DateRangeLike): Unit = {
    val tempPitActor = crunchReadActor(pointInTime, SDate(message.from), SDate(message.to))
    killActor
      .ask(RequestAndTerminate(tempPitActor, message))
      .pipeTo(sender())
  }

  def crunchReadActor(pointInTime: SDateLike,
                      start: SDateLike,
                      end: SDateLike): ActorRef = context.actorOf(Props(new CrunchStateReadActor(
    portStateSnapshotInterval,
    pointInTime,
    DrtStaticParameters.expireAfterMillis,
    queuesByTerminal,
    start.millisSinceEpoch,
    end.millisSinceEpoch)))

  def stateForPeriod(start: MillisSinceEpoch,
                     end: MillisSinceEpoch): Option[PortState] = Option(state.window(SDate(start), SDate(end)))

  def stateForPeriodForTerminal(start: MillisSinceEpoch,
                                end: MillisSinceEpoch,
                                terminal: Terminal): PortState =
    state.windowWithTerminalFilter(SDate(start), SDate(end), Seq(terminal))

  var flightMinutesBuffer: Set[MillisSinceEpoch] = Set[MillisSinceEpoch]()
  var loadMinutesBuffer: Map[TQM, LoadMinute] = Map[TQM, LoadMinute]()

  def splitDiffAndSend(diff: PortStateDiff): Unit = {
    val replyTo = sender()

    splitDiff(diff) match {
      case (live, forecast) =>
        Future
          .sequence(Seq(
            askAndLogOnFailure(liveCrunchStateActor, live, "live crunch persistence request failed"),
            askAndLogOnFailure(forecastCrunchStateActor, forecast, "forecast crunch persistence request failed")))
          .recover { case t => log.error("A future failed", t) }
          .onComplete { _ =>
            log.debug(s"Sending Ack")
            replyTo ! Ack
          }
    }
  }

  private def handleCrunchRequest(): Unit = (maybeCrunchQueueActor, flightMinutesBuffer.nonEmpty, crunchSourceIsReady) match {
    case (Some(crunchActor), true, true) =>
      crunchSourceIsReady = false
      val updatedMillisToSend = flightMinutesBuffer
      flightMinutesBuffer = Set()
      log.info(s"Sending ${updatedMillisToSend.size} UpdatedMillis to the crunch queue actor")
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

  private def handleSimulationRequest(): Unit = (maybeSimActor, loadMinutesBuffer.nonEmpty, simulationActorIsReady) match {
    case (Some(simActor), true, true) =>
      simulationActorIsReady = false
      val loadsToSend = loadMinutesBuffer.values.toList
      loadMinutesBuffer = Map()
      simActor
        .ask(Loads(loadsToSend))(new Timeout(15 seconds))
        .recover {
          case t =>
            log.error("Error sending loads to simulate. Putting loads back in the buffer to send later", t)
            loadMinutesBuffer = loadMinutesBuffer ++ loadsToSend.map(lm => (lm.uniqueId, lm))
        }
        .onComplete { _ =>
          context.self ! SetSimulationSourceReady
        }
    case _ =>
  }

  private def askAndLogOnFailure[A](actor: ActorRef, question: Any, msg: String): Future[Any] = actor
    .ask(question)
    .recover {
      case t => log.error(msg, t)
    }

  private def crunchMinutesToLoads(diff: PortStateDiff): Iterable[(TQM, LoadMinute)] = diff.crunchMinuteUpdates.map {
    case (tqm, cm) => (tqm, LoadMinute(cm))
  }

  private def splitDiff(diff: PortStateDiff): (PortStateDiff, PortStateDiff) = {
    val liveDiff = diff.window(liveStart(now).millisSinceEpoch, liveEnd(now, liveDaysAhead).millisSinceEpoch)
    val forecastDiff = diff.window(forecastStart(now).millisSinceEpoch, forecastEnd(now).millisSinceEpoch)
    (liveDiff, forecastDiff)
  }

  private def nowMillis: MillisSinceEpoch = now().millisSinceEpoch

  def liveStart(now: () => SDateLike): SDateLike = now().getLocalLastMidnight.addDays(-1)

  def liveEnd(now: () => SDateLike,
              liveStateDaysAhead: Int): SDateLike = now().getLocalNextMidnight.addDays(liveStateDaysAhead)

  def forecastEnd(now: () => SDateLike): SDateLike = now().getLocalNextMidnight.addDays(360)

  def forecastStart(now: () => SDateLike): SDateLike = now().getLocalNextMidnight.addDays(1)
}

case object HandleCrunchRequest

case object HandleSimulationRequest

case object SetCrunchSourceReady

case object SetSimulationSourceReady
