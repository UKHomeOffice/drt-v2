package actors.daily

import actors.GetUpdatesSince
import actors.acking.AckingReceiver.Ack
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

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success}


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
                         day: SDateLike): Unit = streamingUpdateActors.get((terminal, day.millisSinceEpoch)) match {
    case Some(_) => Unit
    case None =>
      log.info(s"Starting supervised updates stream for $terminal / ${day.toISODateOnly}")
      val actor = context.system.actorOf(updatesActorFactory(terminal, day))
      streamingUpdateActors = streamingUpdateActors + ((terminal, day.millisSinceEpoch) -> actor)
      lastRequests = lastRequests + ((terminal, day.millisSinceEpoch) -> now().millisSinceEpoch)
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

    case StartUpdatesStream(terminal, day) =>
      startUpdatesStream(terminal, day)
      sender() ! Ack

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

  def terminalsAndDaysUpdatesSource(terminalDays: List[(Terminal, MillisSinceEpoch)],
                                    sinceMillis: MillisSinceEpoch): Source[MinutesContainer[A, B], NotUsed] =
    Source(terminalDays)
      .mapAsync(1) {
        case (t, d) =>
          streamingUpdateActors.get((t, d)) match {
            case Some(actor) =>
              lastRequests = lastRequests + ((t, d) -> now().millisSinceEpoch)
              allUpdatesSince(sinceMillis, actor)
            case None =>
              log.error(s"Being asked for updates for $t@${SDate(d).toISOString()}, but we're missing the updates actor for that terminal and day")
              Future(MinutesContainer.empty[A, B])
          }
      }

  def allUpdatesSince(sinceMillis: MillisSinceEpoch, actor: ActorRef): Future[MinutesContainer[A, B]] =
    actor
      .ask(GetAllUpdatesSince(sinceMillis)).mapTo[MinutesContainer[A, B]]
      .recoverWith {
        case t =>
          log.error("Failed to get a response from the updates actor", t)
          Future(MinutesContainer.empty[A, B])
      }
}
