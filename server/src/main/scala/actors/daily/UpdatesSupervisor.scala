package actors.daily

import actors.GetUpdatesSince
import akka.NotUsed
import akka.actor.{Actor, ActorRef, Cancellable, PoisonPill, Props}
import akka.pattern.{ask, pipe}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.Timeout
import drt.shared.CrunchApi.{MillisSinceEpoch, MinuteLike, MinutesContainer}
import drt.shared.Terminals.Terminal
import drt.shared.{MilliTimes, SDateLike, WithTimeAccessor}
import org.slf4j.{Logger, LoggerFactory}
import services.SDate

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._
import scala.language.postfixOps


case object PurgeExpired

case object PurgeAll

case class GetAllUpdatesSince(sinceMillis: MillisSinceEpoch)

case class StartUpdatesStream(terminal: Terminal, day: SDateLike)

object UpdatesSupervisor {
  def props[A <: MinuteLike[A, B], B <: WithTimeAccessor](now: () => SDateLike,
                                                          terminals: List[Terminal],
                                                          updatesActorFactory: (Terminal, SDateLike) => Props): Props =
    Props(new UpdatesSupervisor[A, B](now, terminals, updatesActorFactory))
}

class UpdatesSupervisor[A, B <: WithTimeAccessor](now: () => SDateLike,
                                                  terminals: List[Terminal],
                                                  updatesActorFactory: (Terminal, SDateLike) => Props) extends Actor {
  val log: Logger = LoggerFactory.getLogger(getClass)
  implicit val ex: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global
  implicit val mat: ActorMaterializer = ActorMaterializer.create(context)
  implicit val timeout: Timeout = new Timeout(15 seconds)
  val cancellableTick: Cancellable = context.system.scheduler.schedule(10 seconds, 10 seconds, self, PurgeExpired)
  val killActor: ActorRef = context.system.actorOf(Props(new RequestAndTerminateActor()))

  var streamingUpdateActors: Map[(Terminal, MillisSinceEpoch), ActorRef] = Map[(Terminal, MillisSinceEpoch), ActorRef]()
  var lastRequests: Map[(Terminal, MillisSinceEpoch), MillisSinceEpoch] = Map[(Terminal, MillisSinceEpoch), MillisSinceEpoch]()

  override def postStop(): Unit = {
    log.warn("Actor stopped. Cancelling scheduled tick")
    cancellableTick.cancel()
    super.postStop()
  }

  def startUpdatesStream(terminal: Terminal,
                         day: SDateLike): ActorRef = streamingUpdateActors.get((terminal, day.millisSinceEpoch)) match {
    case Some(existing) => existing
    case None =>
      log.info(s"Starting supervised updates stream for $terminal / ${day.toISODateOnly}")
      val actor = context.system.actorOf(updatesActorFactory(terminal, day))
      streamingUpdateActors = streamingUpdateActors + ((terminal, day.millisSinceEpoch) -> actor)
      lastRequests = lastRequests + ((terminal, day.millisSinceEpoch) -> now().millisSinceEpoch)
      actor
  }

  override def receive: Receive = {
    case PurgeExpired =>
      log.info("Received PurgeExpired")
      val expiredToRemove = lastRequests.collect {
        case (tm, lastRequest) if now().millisSinceEpoch - lastRequest > MilliTimes.oneMinuteMillis =>
          log.info(s"Shutting down streaming updates for ${tm._1}/${SDate(tm._2).toISODateOnly}")
          streamingUpdateActors.get(tm).foreach(_ ! PoisonPill)
          tm
      }
      streamingUpdateActors = streamingUpdateActors -- expiredToRemove
      lastRequests = lastRequests -- expiredToRemove

    case GetUpdatesSince(sinceMillis, fromMillis, toMillis) =>
      val replyTo = sender()
      val terminalDays = terminalDaysForPeriod(fromMillis, toMillis)

      terminalsAndDaysUpdatesSource(terminalDays, sinceMillis)
        .runWith(Sink.fold(MinutesContainer.empty[A, B])(_ ++ _))
        .pipeTo(replyTo)
  }

  def terminalDaysForPeriod(fromMillis: MillisSinceEpoch,
                            toMillis: MillisSinceEpoch): List[(Terminal, MillisSinceEpoch)] = {
    val daysMillis: Seq[MillisSinceEpoch] = (fromMillis to toMillis by MilliTimes.oneHourMillis)
      .map(m => SDate(m).getUtcLastMidnight.millisSinceEpoch)
      .distinct

    for {
      terminal <- terminals
      day <- daysMillis
    } yield (terminal, day)
  }

  def updatesActor(terminal: Terminal, day: MillisSinceEpoch): ActorRef =
    streamingUpdateActors.get((terminal, day)) match {
      case Some(existingActor) => existingActor
      case None => startUpdatesStream(terminal, SDate(day))
    }

  def terminalsAndDaysUpdatesSource(terminalDays: List[(Terminal, MillisSinceEpoch)],
                                    sinceMillis: MillisSinceEpoch): Source[MinutesContainer[A, B], NotUsed] =
    Source(terminalDays)
      .mapAsync(1) {
        case (terminal, day) =>
          lastRequests = lastRequests + ((terminal, day) -> now().millisSinceEpoch)
          updatesActor(terminal, day)
              .ask(GetAllUpdatesSince(sinceMillis))
              .mapTo[MinutesContainer[A, B]]
      }
}
