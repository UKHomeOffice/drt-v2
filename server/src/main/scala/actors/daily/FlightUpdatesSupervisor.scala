package actors.daily

import actors.PartitionedPortStateActor.GetFlightUpdatesSince
import actors.daily.StreamingUpdatesLike.StopUpdates
import akka.NotUsed
import akka.actor.{Actor, ActorRef, Cancellable, Props}
import akka.pattern.{AskTimeoutException, ask}
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.Timeout
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightUpdatesAndRemovals
import org.slf4j.{Logger, LoggerFactory}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{MilliTimes, SDate, SDateLike, UtcDate}

import java.util.UUID
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.language.postfixOps


object FlightUpdatesSupervisor {

  case class UpdateLastRequest(terminal: Terminal, day: UtcDate, lastRequestMillis: MillisSinceEpoch)

}

class FlightUpdatesSupervisor(now: () => SDateLike,
                              terminals: List[Terminal],
                              updatesActorFactory: (Terminal, UtcDate) => Props) extends Actor {
  val log: Logger = LoggerFactory.getLogger(getClass)

  import FlightUpdatesSupervisor._

  implicit val ex: ExecutionContextExecutor = context.dispatcher
  implicit val mat: Materializer = Materializer.createMaterializer(context)
  implicit val timeout: Timeout = new Timeout(30 seconds)

  val cancellableTick: Cancellable = context.system.scheduler.scheduleWithFixedDelay(10 seconds, 10 seconds, self, PurgeExpired)

  var streamingUpdateActors: Map[(Terminal, UtcDate), ActorRef] = Map[(Terminal, UtcDate), ActorRef]()
  var jsonUpdateSubscribers: Map[UtcDate, Set[ActorRef]] = Map[UtcDate, Set[ActorRef]]()
  var lastRequests: Map[(Terminal, UtcDate), MillisSinceEpoch] = Map[(Terminal, UtcDate), MillisSinceEpoch]()

  override def postStop(): Unit = {
    log.warn("Actor stopped. Cancelling scheduled tick")
    cancellableTick.cancel()
    super.postStop()
  }

  def startUpdatesStream(terminal: Terminal,
                         day: UtcDate): ActorRef = streamingUpdateActors.get((terminal, day)) match {
    case Some(existing) => existing
    case None =>
      log.debug(s"Starting supervised updates stream for $terminal / ${day.toISOString}")
      val actorId = s"flight-updates-actor-$terminal-${day.toISOString}-${UUID.randomUUID().toString}"
      val actor = context.system.actorOf(updatesActorFactory(terminal, day), actorId)
      streamingUpdateActors = streamingUpdateActors + ((terminal, day) -> actor)
      lastRequests = lastRequests + ((terminal, day) -> now().millisSinceEpoch)
      actor
  }

  override def receive: Receive = {
    case PurgeExpired =>
      val expiredToRemove = lastRequests.collect {
        case (tm, lastRequest) if now().millisSinceEpoch - lastRequest > MilliTimes.oneMinuteMillis =>
          (tm, streamingUpdateActors.get(tm))
      }
      streamingUpdateActors = streamingUpdateActors -- expiredToRemove.keys
      lastRequests = lastRequests -- expiredToRemove.keys
      expiredToRemove.foreach {
        case ((terminal, day), Some(actor)) =>
          log.info(s"Shutting down streaming updates for $terminal/${SDate(day).toISODateOnly}")
          actor ! StopUpdates
        case _ =>
      }

    case GetFlightUpdatesSince(sinceMillis, fromMillis, toMillis) =>
      val replyTo = sender()
      val terminalDays = terminalDaysForPeriod(fromMillis, toMillis)

      terminalsAndDaysUpdatesSource(terminalDays, sinceMillis)
        .log(getClass.getName)
        .runWith(Sink.fold(FlightUpdatesAndRemovals.empty)(_ ++ _))
        .foreach(replyTo ! _)

    case UpdateLastRequest(terminal, day, lastRequestMillis) =>
      lastRequests = lastRequests + ((terminal, day) -> lastRequestMillis)
  }

  def terminalDaysForPeriod(fromMillis: MillisSinceEpoch,
                            toMillis: MillisSinceEpoch): List[(Terminal, UtcDate)] = {
    val daysMillis: Seq[UtcDate] = (fromMillis to toMillis by MilliTimes.oneHourMillis)
      .map(m => SDate(m).toUtcDate)
      .distinct

    for {
      terminal <- terminals
      day <- daysMillis
    } yield (terminal, day)
  }

  def updatesActor(terminal: Terminal, day: UtcDate): ActorRef =
    streamingUpdateActors.get((terminal, day)) match {
      case Some(existingActor) => existingActor
      case None => startUpdatesStream(terminal, day)
    }

  def terminalsAndDaysUpdatesSource(terminalDays: List[(Terminal, UtcDate)],
                                    sinceMillis: MillisSinceEpoch): Source[FlightUpdatesAndRemovals, NotUsed] =
    Source(terminalDays)
      .mapAsync(1) {
        case (terminal, day) =>
          updatesActor(terminal, day)
            .ask(GetAllUpdatesSince(sinceMillis))
            .mapTo[FlightUpdatesAndRemovals]
            .map { updatesAndRemovals =>
              self ! UpdateLastRequest(terminal, day, now().millisSinceEpoch)
              updatesAndRemovals
            }
            .recoverWith {
              case t: AskTimeoutException =>
                log.warn(s"Timed out waiting for updates. Actor may have already been terminated", t)
                Future(FlightUpdatesAndRemovals.empty)
              case t =>
                log.error(s"Failed to fetch updates from streaming updates actor: ${SDate(day).toISOString}", t)
                Future(FlightUpdatesAndRemovals.empty)
            }
      }
}
