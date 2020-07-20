package actors

import actors.DrtStaticParameters.liveDaysAhead
import actors.PortStateActor.Start
import actors.acking.AckingReceiver.{Ack, StreamCompleted, StreamFailure, StreamInitialized}
import actors.daily.{RequestAndTerminate, RequestAndTerminateActor}
import actors.pointInTime.CrunchStateReadActor
import akka.actor.{Actor, ActorRef, ActorSystem, Cancellable, Props, Stash}
import akka.pattern.{ask, pipe}
import akka.stream.ActorMaterializer
import akka.util.Timeout
import drt.shared.CrunchApi._
import drt.shared.FlightsApi.{FlightsWithSplits, FlightsWithSplitsDiff}
import drt.shared.Queues.Queue
import drt.shared.Terminals.Terminal
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import services.crunch.deskrecs.{GetFlights, GetStateForDateRange, GetStateForTerminalDateRange}
import services.graphstages.Crunch.LoadMinute
import services.{RecalculationRequester, SDate}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.language.postfixOps


object PortStateActor {
  def apply(now: () => SDateLike,
            liveCrunchStateProps: Props,
            forecastCrunchStateProps: Props,
            queues: Map[Terminal, Seq[Queue]],
            replayMaxCrunchStateMessages: Int)(implicit system: ActorSystem): ActorRef =
    system.actorOf(Props(new PortStateActor(liveCrunchStateProps, forecastCrunchStateProps, now, liveDaysAhead, queues, replayMaxCrunchStateMessages)), name = "port-state-actor")

  case object Start

}

class PortStateActor(liveCrunchStateProps: Props,
                     forecastCrunchStateProps: Props,
                     now: () => SDateLike,
                     liveDaysAhead: Int,
                     queuesByTerminal: Map[Terminal, Seq[Queue]],
                     replayMaxCrunchStateMessages: Int) extends Actor with Stash {
  val log: Logger = LoggerFactory.getLogger(getClass)

  implicit val ec: ExecutionContextExecutor = context.dispatcher
  implicit val mat: ActorMaterializer = ActorMaterializer.create(context)
  implicit val timeout: Timeout = new Timeout(30 seconds)
  val cancellableTick: Cancellable = context.system.scheduler.schedule(10 seconds, 500 millisecond, self, HandleRecalculations)

  val liveCrunchStateActor: ActorRef = context.actorOf(liveCrunchStateProps, name = "crunch-live-state-actor")
  val forecastCrunchStateActor: ActorRef = context.actorOf(forecastCrunchStateProps, name = "crunch-forecast-state-actor")

  val state: PortStateMutable = PortStateMutable.empty

  val killActor: ActorRef = context.system.actorOf(Props(new RequestAndTerminateActor())) //, "port-state-kill-actor")

  val reCrunchHandler = new RecalculationRequester()
  val reDeployHandler = new RecalculationRequester()

  override def preStart(): Unit = {
    val eventualLiveState = liveCrunchStateActor.ask(GetState).mapTo[Option[PortState]]
    val eventualFcstState = forecastCrunchStateActor.ask(GetState).mapTo[Option[PortState]]
    val eventualStates = for {
      liveState <- eventualLiveState
      fcstState <- eventualFcstState
    } yield mergePortStates(fcstState, liveState)

    eventualStates.foreach { maybeInitialPortState =>
      maybeInitialPortState.foreach { initialPortState =>
        log.info(s"Setting initial PortState from live & forecast")
        state.crunchMinutes ++= initialPortState.crunchMinutes
        state.staffMinutes ++= initialPortState.staffMinutes
        state.flights ++= initialPortState.flights
      }
    }

    eventualStates.onComplete(_ => self ! Start)
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
    case Start =>
      unstashAll()
      context.become(readyBehaviour)

    case _ => stash()
  }

  def readyBehaviour: Receive = {
    case SetCrunchQueueActor(actor) =>
      log.info(s"Received crunch queue actor")
      reCrunchHandler.setQueueActor(actor)

    case SetDeploymentQueueActor(actor) =>
      log.info(s"Received deployment queue actor")
      reDeployHandler.setQueueActor(actor)

    case StreamInitialized => sender() ! Ack

    case StreamCompleted => log.info(s"Stream completed")

    case StreamFailure(t) => log.error(s"Stream failed", t)

    case flightsWithSplits: FlightsWithSplitsDiff =>
      log.info(s"Processing incoming FlightsWithSplits")

      val diff = flightsWithSplits.applyTo(state, nowMillis)

      if (diff.flightMinuteUpdates.nonEmpty)
        reCrunchHandler.addMillis(diff.flightMinuteUpdates)

      splitDiffAndSend(diff)

    case updates: PortStateMinutes[_, _] =>
      log.debug(s"Processing incoming PortStateMinutes ${updates.getClass}")

      val diff = updates.applyTo(state, nowMillis)

      if (diff.crunchMinuteUpdates.nonEmpty)
        reDeployHandler.addMillis(diff.crunchMinuteUpdates.keys.map(_.minute))

      splitDiffAndSend(diff)

    case HandleRecalculations =>
      reCrunchHandler.handleUpdatedMillis()
      reDeployHandler.handleUpdatedMillis()

    case PointInTimeQuery(millis, query) =>
      replyWithPointInTimeQuery(SDate(millis), query)

    case message: DateRangeLike if SDate(message.to).isHistoricDate(now()) =>
      replyWithDayViewQuery(message)

    case GetStateForDateRange(start, end) =>
      log.debug(s"Received GetPortState Request from ${SDate(start).toISOString()} to ${SDate(end).toISOString()}")
      sender() ! stateForPeriod(start, end)

    case GetStateForTerminalDateRange(start, end, terminal) =>
      log.debug(s"Received GetPortStateForTerminal Request from ${SDate(start).toISOString()} to ${SDate(end).toISOString()} for $terminal")
      sender() ! stateForPeriodForTerminal(start, end, terminal)

    case GetFlights(startMillis, endMillis) =>
      val start = SDate(startMillis)
      val end = SDate(endMillis)
      log.info(s"Got request for flights between ${start.toISOString()} - ${end.toISOString()}")
      sender() ! FlightsWithSplits(state.flights.range(start, end))

    case GetFlightsForTerminal(start, end, terminal) =>
      log.debug(s"Received GetFlightsForTerminal Request from ${SDate(start).toISOString()} to ${SDate(end).toISOString()} for $terminal")
      sender() ! FlightsWithSplits(stateForPeriodForTerminal(start, end, terminal).flights)

    case GetUpdatesSince(millis, start, end) =>
      val updates: Option[PortStateUpdates] = state.updates(millis, start, end)
      sender() ! updates

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
                      end: SDateLike): ActorRef =
    context.actorOf(Props(new CrunchStateReadActor(pointInTime, DrtStaticParameters.expireAfterMillis, queuesByTerminal, start.millisSinceEpoch, end.millisSinceEpoch, replayMaxCrunchStateMessages)))

  def stateForPeriod(start: MillisSinceEpoch, end: MillisSinceEpoch): PortState = state.window(SDate(start), SDate(end))

  def stateForPeriodForTerminal(start: MillisSinceEpoch,
                                end: MillisSinceEpoch,
                                terminal: Terminal): PortState =
    state.windowWithTerminalFilter(SDate(start), SDate(end), Seq(terminal))

  def splitDiffAndSend(diff: PortStateDiff): Unit = {
    val replyTo = sender()

    splitDiff(diff) match {
      case (live, forecast) =>
        Future
          .sequence(Seq(
            askAndLogOnFailure(liveCrunchStateActor, live, "live crunch persistence request failed"),
            askAndLogOnFailure(forecastCrunchStateActor, forecast, "forecast crunch persistence request failed")))
          .recover { case t =>
            log.error("A future failed on requesting persistence", t)
          }
          .onComplete { _ =>
            log.debug(s"Sending Ack")
            replyTo ! Ack
          }
    }
  }

  private def askAndLogOnFailure[A](actor: ActorRef, question: Any, msg: String): Future[Any] = actor
    .ask(question)
    .recover {
      case t => throw new Exception(msg, t)
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

case object HandleRecalculations

case object SetSimulationSourceReady
