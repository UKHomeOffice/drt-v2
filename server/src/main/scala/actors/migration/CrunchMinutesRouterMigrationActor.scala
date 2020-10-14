package actors.migration

import java.util.UUID

import actors.acking.AckingReceiver.{Ack, StreamCompleted, StreamFailure, StreamInitialized}
import actors.daily.RequestAndTerminate
import actors.minutes.MinutesActorLike.{CrunchMinutesMigrationUpdate, ProcessNextUpdateRequest}
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.Timeout
import drt.shared.UtcDate
import org.slf4j.{Logger, LoggerFactory}
import services.SDate

import scala.collection.immutable
import scala.concurrent.{ExecutionContextExecutor, Future}


class CrunchMinutesRouterMigrationActor(updateMinutes: CrunchMinutesMigrationUpdate) extends Actor {
  implicit val dispatcher: ExecutionContextExecutor = context.dispatcher
  implicit val mat: ActorMaterializer = ActorMaterializer.create(context)

  val log: Logger = LoggerFactory.getLogger(getClass)

  var updateRequestsQueue: List[(ActorRef, CrunchMinutesMessageMigration)] = List()
  var processingRequest: Boolean = false

  override def receive: Receive = {
    case StreamInitialized => sender() ! Ack

    case StreamCompleted => log.info(s"Stream completed")

    case StreamFailure(t) => log.error(s"Stream failed", t)

    case container: CrunchMinutesMessageMigration =>
      log.info(s"Adding ${container.minutesMessages.size} minutes to requests queue")
      updateRequestsQueue = (sender(), container) :: updateRequestsQueue
      self ! ProcessNextUpdateRequest

    case ProcessNextUpdateRequest =>
      if (!processingRequest) {
        updateRequestsQueue match {
          case (replyTo, container) :: tail =>
            handleUpdatesAndAck(container, replyTo)
            updateRequestsQueue = tail
          case Nil =>
            log.debug("Update requests queue is empty. Nothing to do")
        }
      }

    case u => log.warn(s"Got an unexpected message: $u")
  }

  def handleUpdatesAndAck(container: CrunchMinutesMessageMigration,
                          replyTo: ActorRef): Unit = {
    processingRequest = true
    updateByTerminalDayAndGetAck(container)
      .onComplete { _ =>
        processingRequest = false
        replyTo ! Ack
        self ! ProcessNextUpdateRequest
      }
  }

  def updateByTerminalDayAndGetAck(container: CrunchMinutesMessageMigration): Future[immutable.Seq[Any]] =
    Source(groupByTerminalAndDay(container)).mapAsync(1) {
      case ((terminal, day), terminalDayMinutes) => updateMinutes(terminal, day, terminalDayMinutes)
    }.runWith(Sink.seq)

  def groupByTerminalAndDay(migration: CrunchMinutesMessageMigration ): Map[(String, UtcDate), CrunchMinutesMessageMigration] =
    migration.minutesMessages
      .groupBy(msg => (msg.getTerminalName, SDate(msg.getMinute).toUtcDate))
      .map {
        case (terminalDay, msgs) => (terminalDay, CrunchMinutesMessageMigration(migration.createdAt, msgs))
      }
}
