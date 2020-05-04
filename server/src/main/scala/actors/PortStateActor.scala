package actors

import actors.DrtStaticParameters.liveDaysAhead
import actors.acking.AckingReceiver.{Ack, StreamCompleted, StreamFailure, StreamInitialized}
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import drt.shared.CrunchApi._
import drt.shared.FlightsApi.{FlightsWithSplits, FlightsWithSplitsDiff}
import drt.shared.Terminals.Terminal
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import services.SDate
import services.crunch.deskrecs.GetFlights
import services.graphstages.Crunch.{LoadMinute, Loads}

import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.language.postfixOps


object PortStateActor {
  def apply(now: () => SDateLike, liveCrunchStateActor: ActorRef, forecastCrunchStateActor: ActorRef)
           (implicit system: ActorSystem): ActorRef = {
    system.actorOf(PortStateActor.props(liveCrunchStateActor, forecastCrunchStateActor, now, liveDaysAhead), name = "port-state-actor")
  }

  def props(liveStateActor: ActorRef,
            forecastStateActor: ActorRef,
            now: () => SDateLike,
            liveDaysAhead: Int): Props =
    Props(new PortStateActor(liveStateActor, forecastStateActor, now, liveDaysAhead, exitOnQueueException = true))
}

class PortStateActor(liveStateActor: ActorRef, forecastStateActor: ActorRef, now: () => SDateLike, liveDaysAhead: Int, exitOnQueueException: Boolean) extends Actor {
  val log: Logger = LoggerFactory.getLogger(getClass)

  implicit val ec: ExecutionContextExecutor = ExecutionContext.global

  implicit val timeout: Timeout = new Timeout(1 minute)

  val state: PortStateMutable = PortStateMutable.empty

  var maybeCrunchActor: Option[ActorRef] = None
  var crunchSourceIsReady: Boolean = true
  var maybeSimActor: Option[ActorRef] = None
  var simulationActorIsReady: Boolean = true

  override def receive: Receive = {

    case SetCrunchActor(crunchActor) =>
      log.info(s"Received crunchSourceActor")
      maybeCrunchActor = Option(crunchActor)

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

      if (diff.crunchMinuteUpdates.nonEmpty) loadMinutesBuffer ++= crunchMinutesToLoads(diff)

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

    case GetPortState(start, end) =>
      log.debug(s"Received GetPortState Request from ${SDate(start).toISOString()} to ${SDate(end).toISOString()}")
      sender() ! stateForPeriod(start, end)

    case GetPortStateForTerminal(start, end, terminal) =>
      log.debug(s"Received GetPortStateForTerminal Request from ${SDate(start).toISOString()} to ${SDate(end).toISOString()} for $terminal")
      sender() ! stateForPeriodForTerminal(start, end, terminal)

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

  def stateForPeriod(start: MillisSinceEpoch,
                     end: MillisSinceEpoch): Option[PortState] = Option(state.window(SDate(start), SDate(end)))

  def stateForPeriodForTerminal(start: MillisSinceEpoch,
                                end: MillisSinceEpoch,
                                terminal: Terminal): Option[PortState] = Option(state.windowWithTerminalFilter(SDate(start), SDate(end), Seq(terminal)))

  val flightMinutesBuffer: mutable.Set[MillisSinceEpoch] = mutable.Set[MillisSinceEpoch]()
  val loadMinutesBuffer: mutable.Map[TQM, LoadMinute] = mutable.Map[TQM, LoadMinute]()

  def splitDiffAndSend(diff: PortStateDiff): Unit = {
    val replyTo = sender()

    splitDiff(diff) match {
      case (live, forecast) =>
        Future
          .sequence(Seq(
            askAndLogOnFailure(liveStateActor, live, "live crunch persistence request failed"),
            askAndLogOnFailure(forecastStateActor, forecast, "forecast crunch persistence request failed"))
                    )
          .recover { case t => log.error("A future failed", t) }
          .onComplete { _ =>
            log.debug(s"Sending Ack")
            replyTo ! Ack
          }
    }
  }

  private def handleCrunchRequest(): Unit = (maybeCrunchActor, flightMinutesBuffer.nonEmpty, crunchSourceIsReady) match {
    case (Some(crunchActor), true, true) =>
      crunchSourceIsReady = false
      crunchActor
        .ask(flightMinutesBuffer.toList)(new Timeout(15 seconds))
        .recover {
          case e =>
            log.error("Error sending minutes to crunch - non recoverable error", e)
            if (exitOnQueueException) {
              log.info("Terminating App")
              System.exit(1)
            }
        }
        .onComplete { _ =>
          context.self ! SetCrunchSourceReady
        }
      flightMinutesBuffer.clear()
    case _ =>
  }

  private def handleSimulationRequest(): Unit = (maybeSimActor, loadMinutesBuffer.nonEmpty, simulationActorIsReady) match {
    case (Some(simActor), true, true) =>
      simulationActorIsReady = false
      simActor
        .ask(Loads(loadMinutesBuffer.values.toList))(new Timeout(10 minutes))
        .recover {
          case t => log.error("Error sending loads to simulate", t)
        }
        .onComplete { _ =>
          context.self ! SetSimulationSourceReady
        }
      loadMinutesBuffer.clear()
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
