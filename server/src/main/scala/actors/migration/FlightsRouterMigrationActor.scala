package actors.migration

import actors.PartitionedPortStateActor._
import actors.acking.AckingReceiver.{Ack, StreamCompleted, StreamFailure, StreamInitialized}
import actors.daily.RequestAndTerminateActor
import actors.minutes.MinutesActorLike.{FlightsMigrationUpdate, ProcessNextUpdateRequest}
import actors.queues.FlightsRouterActor
import akka.NotUsed
import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.Timeout
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.Terminals.Terminal
import drt.shared._
import server.protobuf.messages.CrunchState.{FlightWithSplitsMessage, FlightsWithSplitsDiffMessage}
import server.protobuf.messages.FlightsMessage.UniqueArrivalMessage
import services.SDate

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.language.postfixOps


class FlightsRouterMigrationActor(terminals: Iterable[Terminal], updateFlights: FlightsMigrationUpdate) extends Actor
  with ActorLogging {

  implicit val dispatcher: ExecutionContextExecutor = context.dispatcher
  implicit val mat: ActorMaterializer = ActorMaterializer.create(context)
  implicit val timeout: Timeout = new Timeout(60 seconds)

  var updateRequestsQueue: List[(ActorRef, FlightMessageMigration)] = List()
  var processingRequest: Boolean = false
  val killActor: ActorRef = context.system.actorOf(Props(new RequestAndTerminateActor()))
  val forwardRequestAndKillActor: (ActorRef, ActorRef, DateRangeLike) => Future[Source[FlightsWithSplits, NotUsed]] =
    FlightsRouterActor.forwardRequestAndKillActor(killActor)

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
        replyTo ! Ack
        self ! ProcessNextUpdateRequest
      }
  }

  def updateByTerminalDayAndGetAck(container: FlightMessageMigration): Future[Unit] =
    Source(groupByTerminalAndDay(container))
      .mapAsync(1) {
        case ((terminal, day), splitsDiffMessage) =>
          updateFlights(terminal, day, splitsDiffMessage)
      }
      .runWith(Sink.seq).map(_ => Unit)

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
