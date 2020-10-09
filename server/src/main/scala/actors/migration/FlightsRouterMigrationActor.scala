package actors.migration

import java.util.UUID

import actors.acking.AckingReceiver.{Ack, StreamCompleted, StreamFailure, StreamInitialized}
import actors.daily.RequestAndTerminate
import actors.minutes.MinutesActorLike.{CrunchMinutesMigrationUpdate, FlightsMigrationUpdate, ProcessNextUpdateRequest}
import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.Timeout
import drt.shared._
import server.protobuf.messages.CrunchState.{CrunchDiffMessage, CrunchMinutesMessage, FlightWithSplitsMessage, FlightsWithSplitsDiffMessage}
import server.protobuf.messages.FlightsMessage.UniqueArrivalMessage
import services.SDate

import scala.collection.immutable
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.language.postfixOps


object FlightsRouterMigrationActor {
  def updateFlights(requestAndTerminateActor: ActorRef,
                    propsForTerminalDateFn: (String, UtcDate) => Props)
                   (implicit system: ActorSystem, timeout: Timeout): FlightsMigrationUpdate =
    (terminal: String, date: UtcDate, diff: FlightsWithSplitsDiffMessage) => {
      val actor = system.actorOf(propsForTerminalDateFn(terminal, date), s"migration-flights-$terminal-$date-${UUID.randomUUID().toString}")
      system.log.info(s"About to update $terminal $date with ${diff.updates.size} flights")
      requestAndTerminateActor.ask(RequestAndTerminate(actor, diff))
    }
  def updateCrunchMinutes(requestAndTerminateActor: ActorRef,
                    propsForTerminalDateFn: (String, SDateLike) => Props)
                   (implicit system: ActorSystem, timeout: Timeout): CrunchMinutesMigrationUpdate =
    (terminal: String, date: SDateLike, cms: CrunchMinutesMessage) => {
      val actor = system.actorOf(propsForTerminalDateFn(terminal, date), s"migration-crunch-minutes-$terminal-$date-${UUID.randomUUID().toString}")
      system.log.info(s"About to update $terminal $date with ${cms.minutes.size} minutes")
      requestAndTerminateActor.ask(RequestAndTerminate(actor, cms))
    }
}

class FlightsRouterMigrationActor(updateFlights: FlightsMigrationUpdate) extends Actor with ActorLogging {
  implicit val dispatcher: ExecutionContextExecutor = context.dispatcher
  implicit val mat: ActorMaterializer = ActorMaterializer.create(context)
  implicit val timeout: Timeout = new Timeout(60 seconds)

  var updateRequestsQueue: List[(ActorRef, FlightMessageMigration)] = List()
  var processingRequest: Boolean = false

  override def receive: Receive = {
    case StreamInitialized => sender() ! Ack

    case StreamCompleted => log.info(s"Stream completed")

    case StreamFailure(t) => log.error(s"Stream failed", t)

    case messageMigration: FlightMessageMigration =>
      updateRequestsQueue = (sender(), messageMigration) :: updateRequestsQueue
      self ! ProcessNextUpdateRequest

    case ProcessNextUpdateRequest =>
      if (!processingRequest) {
        updateRequestsQueue match {
          case (replyTo, flightMessageMigration) :: tail =>
            handleUpdatesAndAck(flightMessageMigration, replyTo)
            updateRequestsQueue = tail
            log.info(s"Processing latest migration ${updateRequestsQueue.size} left to process.")
          case Nil =>
            log.debug("Update requests queue is empty. Nothing to do")
        }
      }

    case unexpected => log.warning(s"Got an unexpected message: $unexpected")
  }

  def handleUpdatesAndAck(flightMessageMigration: FlightMessageMigration, replyTo: ActorRef): Unit = {
    processingRequest = true
    updateByTerminalDayAndGetAck(flightMessageMigration)
      .onComplete { _ =>
        processingRequest = false
        log.info(s"** acking back to flights migration actor ($replyTo)")
        replyTo ! Ack
        self ! ProcessNextUpdateRequest
      }
  }

  def updateByTerminalDayAndGetAck(container: FlightMessageMigration): Future[immutable.Seq[Any]] =
    Source(groupByTerminalAndDay(container))
      .mapAsync(1) {
        case ((terminal, day), splitsDiffMessage) =>
          updateFlights(terminal, day, splitsDiffMessage)
      }
      .runWith(Sink.seq)

  def groupByTerminalAndDay(flightMessageMigration: FlightMessageMigration): Map[(String, UtcDate), FlightsWithSplitsDiffMessage] = {
    val updates: Map[(String, UtcDate), Seq[FlightWithSplitsMessage]] = flightMessageMigration.flightsUpdateMessages
      .groupBy(flightWithSplitsMessage =>
        (flightWithSplitsMessage.getFlight.getTerminal, SDate(flightWithSplitsMessage.getFlight.getScheduled).toUtcDate))
    val removals: Map[(String, UtcDate), Seq[UniqueArrivalMessage]] = flightMessageMigration.flightRemovalsMessage
      .groupBy(ua => (ua.getTerminalName, SDate(ua.getScheduled).toUtcDate))

    val keys = updates.keys ++ removals.keys
    keys
      .map {
        terminalDay =>
          val diff = FlightsWithSplitsDiffMessage(
            Option(flightMessageMigration.createdAt),
            removals.getOrElse(terminalDay, List()),
            updates.getOrElse(terminalDay, List())
          )
          (terminalDay, diff)
      }
      .toMap
  }

}
