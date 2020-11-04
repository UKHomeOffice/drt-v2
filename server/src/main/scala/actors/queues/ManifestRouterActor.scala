package actors.queues

import actors.PartitionedPortStateActor._
import actors.acking.AckingReceiver.{Ack, StreamCompleted, StreamFailure, StreamInitialized}
import actors.minutes.MinutesActorLike.{ManifestLookup, ManifestsUpdate, ProcessNextUpdateRequest}
import akka.NotUsed
import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.Timeout
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared._
import passengersplits.parsing.VoyageManifestParser.VoyageManifests
import services.SDate

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.language.postfixOps

object ManifestRouterActor {

  def utcDateRange(start: SDateLike, end: SDateLike): List[UtcDate] = {
    val lookupStartMillis = start.millisSinceEpoch
    val lookupEndMillis = end.millisSinceEpoch
    val daysRangeMillis = lookupStartMillis to lookupEndMillis by MilliTimes.oneDayMillis
    daysRangeMillis.map(SDate(_).toUtcDate).toList
  }

  def manifestsByDaySource(manifestsByDayLookup: ManifestLookup)
                          (start: SDateLike,
                           end: SDateLike,
                           maybePit: Option[MillisSinceEpoch]): Source[VoyageManifests, NotUsed] = {
    Source(utcDateRange(start, end))
      .mapAsync(1) {
        date =>
          manifestsByDayLookup(date, maybePit)
      }
  }

  def runAndCombine(source: Future[Source[VoyageManifests, NotUsed]])(implicit mat: ActorMaterializer, ec: ExecutionContext): Future[VoyageManifests] = source
    .flatMap(
      _.runWith(Sink.reduce[VoyageManifests](_ ++ _))
    )

  def props(manifestLookup: ManifestLookup, manifestsUpdate: ManifestsUpdate) = Props(
    new ManifestRouterActor(manifestLookup, manifestsUpdate)
  )
}

class ManifestRouterActor(manifestLookup: ManifestLookup, manifestsUpdate: ManifestsUpdate) extends Actor
  with ActorLogging {

  implicit val dispatcher: ExecutionContextExecutor = context.dispatcher
  implicit val mat: ActorMaterializer = ActorMaterializer.create(context)
  implicit val timeout: Timeout = new Timeout(60 seconds)

  var updateRequestsQueue: List[(ActorRef, VoyageManifests)] = List()
  var processingRequest: Boolean = false

  override def receive: Receive = {
    case StreamInitialized => sender() ! Ack

    case StreamCompleted => log.info(s"Stream completed")

    case StreamFailure(t) => log.error(s"Stream failed", t)

    case PointInTimeQuery(pit, GetStateForDateRange(startMillis, endMillis)) =>
      sender() ! ManifestRouterActor.manifestsByDaySource(manifestLookup)(SDate(startMillis), SDate(endMillis), Option(pit))

    case GetStateForDateRange(startMillis, endMillis) =>
      sender() ! ManifestRouterActor.manifestsByDaySource(manifestLookup)(SDate(startMillis), SDate(endMillis), None)

    case vms: VoyageManifests =>
      updateRequestsQueue = (sender(), vms) :: updateRequestsQueue
      self ! ProcessNextUpdateRequest

    case ProcessNextUpdateRequest =>
      if (!processingRequest) {
        updateRequestsQueue match {
          case (replyTo, vms) :: tail =>
            handleUpdatesAndAck(vms, replyTo)
            updateRequestsQueue = tail
          case Nil =>
            log.debug("Update requests queue is empty. Nothing to do")
        }
      }

    case unexpected => log.warning(s"Got an unexpected message: $unexpected")
  }

  def handleUpdatesAndAck(vms: VoyageManifests,
                          replyTo: ActorRef): Unit = {
    processingRequest = true
    Future.sequence(
      manifestsByDay(vms)
        .map {
          case (date, vms) => manifestsUpdate(date, vms)
        }
    )
      .onComplete { _ =>
        processingRequest = false
        replyTo ! Ack
        self ! ProcessNextUpdateRequest
      }

  }

  def manifestsByDay(vms: VoyageManifests): Map[UtcDate, VoyageManifests] = vms
    .manifests
    .groupBy(_.scheduleArrivalDateTime.map(_.toUtcDate))
    .collect {
      case (Some(scheduled), vm) =>
        scheduled -> VoyageManifests(vm)
    }
}
