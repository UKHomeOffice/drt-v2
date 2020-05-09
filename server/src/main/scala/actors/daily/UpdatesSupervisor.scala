package actors.daily

import actors.GetUpdatesSince
import actors.acking.AckingReceiver.Ack
import akka.actor.{Actor, ActorRef, Cancellable, PoisonPill, Props}
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.Timeout
import drt.shared.CrunchApi.{MillisSinceEpoch, MinutesContainer}
import drt.shared.Terminals.Terminal
import drt.shared.{MilliTimes, SDateLike, WithTimeAccessor}
import org.slf4j.{Logger, LoggerFactory}
import services.SDate

import scala.language.postfixOps
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}


case object PurgeExpired

case object PurgeAll

case class GetAllUpdatesSince(sinceMillis: MillisSinceEpoch)

case class StartUpdatesStream(terminal: Terminal, day: SDateLike, startingSequenceNr: Long)

class UpdatesSupervisor[A, B <: WithTimeAccessor](now: () => SDateLike,
                                                  terminals: List[Terminal],
                                                  updatesActorFactory: (Terminal, SDateLike, MillisSinceEpoch) => Props) extends Actor {
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
                         day: SDateLike,
                         startingSequenceNr: Long): Unit = streamingUpdateActors.get((terminal, day.millisSinceEpoch)) match {
    case Some(_) => Unit
    case None =>
      log.info(s"Starting supervised updates stream for $terminal / ${day.toISODateOnly} from seqNr: $startingSequenceNr")
      val actor = context.system.actorOf(updatesActorFactory(terminal, day, startingSequenceNr))
      streamingUpdateActors = streamingUpdateActors + ((terminal, day.millisSinceEpoch) -> actor)
      lastRequests = lastRequests + ((terminal, day.millisSinceEpoch) -> now().millisSinceEpoch)
  }

  override def receive: Receive = {
    case PurgeAll =>
      val replyTo = sender()
      log.info(s"Received PurgeAll")
      Future.sequence(streamingUpdateActors.values.map(actor => killActor.ask(Terminate(actor)))).foreach { _ =>
        streamingUpdateActors = Map()
        lastRequests = Map()
        replyTo ! Ack
      }

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

    case StartUpdatesStream(terminal, day, seqNr) =>
      startUpdatesStream(terminal, day, seqNr)
      sender() ! Ack

    case GetUpdatesSince(since, from, to) =>
      val replyTo = sender()
      val daysMillis: Seq[MillisSinceEpoch] = (from to to by MilliTimes.oneHourMillis)
        .map(m => SDate(m).getUtcLastMidnight.millisSinceEpoch)
        .distinct

      val terminalDays = for {
        terminal <- terminals
        day <- daysMillis
      } yield (terminal, day)

      Source(terminalDays)
        .mapAsync(1) {
          case (t, d) =>
            streamingUpdateActors.get((t, d)) match {
              case Some(actor) =>
                lastRequests = lastRequests + ((t, d) -> now().millisSinceEpoch)
                actor
                  .ask(GetAllUpdatesSince(since)).mapTo[MinutesContainer[A, B]]
                  .recoverWith {
                    case t =>
                      log.error("Failed to get a response from the updates actor", t)
                      Future(MinutesContainer.empty[A, B])
                  }
              case None => Future(MinutesContainer.empty[A, B])
            }
        }
        .runWith(Sink.fold(MinutesContainer.empty[A, B])(_ ++ _))
        .onComplete {
          case Success(container) => replyTo ! container
          case Failure(t) =>
            log.error("Failed to get updates", t)
            replyTo ! MinutesContainer.empty[A, B]
        }
  }
}
