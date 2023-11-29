package actors.persistent

import actors.DateRange
import actors.PartitionedPortStateActor._
import actors.persistent.ManifestRouterActor.{GetForArrival, ManifestFound, ManifestNotFound}
import actors.persistent.QueueLikeActor.UpdatedMillis
import actors.persistent.staffing.GetFeedStatuses
import actors.routing.minutes.MinutesActorLike.{ManifestLookup, ManifestsUpdate, ProcessNextUpdateRequest}
import akka.NotUsed
import akka.actor.ActorRef
import akka.pattern.StatusReply
import akka.persistence.{SaveSnapshotFailure, SaveSnapshotSuccess}
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.Timeout
import drt.server.feeds.{DqManifests, ManifestsFeedFailure, ManifestsFeedSuccess}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.parsing.VoyageManifestParser.{VoyageManifest, VoyageManifests}
import uk.gov.homeoffice.drt.actor.RecoveryActorLike
import uk.gov.homeoffice.drt.actor.commands.Commands.{AddUpdatesSubscriber, GetState}
import uk.gov.homeoffice.drt.arrivals.UniqueArrival
import uk.gov.homeoffice.drt.feeds._
import uk.gov.homeoffice.drt.ports.{ApiFeedSource, FeedSource}
import uk.gov.homeoffice.drt.protobuf.messages.FlightsMessage.FeedStatusMessage
import uk.gov.homeoffice.drt.protobuf.messages.VoyageManifest.{VoyageManifestLatestFileNameMessage, VoyageManifestStateSnapshotMessage}
import uk.gov.homeoffice.drt.protobuf.serialisation.FlightMessageConversion.{feedStatusFromFeedStatusMessage, feedStatusToMessage, feedStatusesFromFeedStatusesMessage, feedStatusesToMessage}
import uk.gov.homeoffice.drt.time.{SDate, SDateLike, UtcDate}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success}

object ManifestRouterActor extends StreamingFeedStatusUpdates {
  override val sourceType: FeedSource = ApiFeedSource
  override val persistenceId: String = "arrival-manifests"

  case class GetForArrival(arrival: ArrivalKey)

  sealed trait ManifestResult

  case class ManifestFound(manifest: VoyageManifest) extends ManifestResult

  case object ManifestNotFound extends ManifestResult

  private def manifestsByDaySource(manifestsByDayLookup: ManifestLookup)
                                  (start: SDateLike,
                                   end: SDateLike,
                                   maybePit: Option[MillisSinceEpoch],
                                  )
                                  (implicit ec: ExecutionContext): Source[(UtcDate, VoyageManifests), NotUsed] =
    DateRange
      .utcDateRangeSource(start, end)
      .mapAsync(1)(d => manifestsByDayLookup(d, maybePit).map(m => (d, m)))

}

case class ApiFeedState(lastProcessedMarker: MillisSinceEpoch, maybeSourceStatuses: Option[FeedSourceStatuses]) extends FeedStateLike {
  override def feedSource: FeedSource = ApiFeedSource
}

class ManifestRouterActor(manifestLookup: ManifestLookup,
                          manifestsUpdate: ManifestsUpdate) extends RecoveryActorLike {
  override def persistenceId: String = ManifestRouterActor.persistenceId

  implicit val dispatcher: ExecutionContextExecutor = context.dispatcher
  implicit val mat: Materializer = Materializer.createMaterializer(context)
  implicit val timeout: Timeout = new Timeout(60 seconds)

  var updateRequestsQueue: List[(ActorRef, VoyageManifests)] = List()
  var processingRequest: Boolean = false

  val initialState: ApiFeedState = ApiFeedState(
    SDate.now().addDays(-2).millisSinceEpoch,
    None
  )

  var state: ApiFeedState = initialState
  private var maybeUpdatesSubscriber: Option[ActorRef] = None

  override val log: Logger = LoggerFactory.getLogger(getClass)

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case _: String => log.debug(s"Ignoring redundant zip file name")

    case VoyageManifestLatestFileNameMessage(_, _, Some(lastProcessedMarker)) =>
      state = state.copy(lastProcessedMarker = lastProcessedMarker)

    case feedStatusMessage: FeedStatusMessage =>
      val status = feedStatusFromFeedStatusMessage(feedStatusMessage)
      state = state.copy(maybeSourceStatuses = Option(state.addStatus(status)))
  }

  def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case VoyageManifestStateSnapshotMessage(_, _, maybeStatusMessages, Some(lastProcessedMarker)) =>
      val maybeStatuses = maybeStatusMessages
        .map(feedStatusesFromFeedStatusesMessage)
        .map(fs => FeedSourceStatuses(ApiFeedSource, fs))

      state = state.copy(lastProcessedMarker = lastProcessedMarker, maybeSourceStatuses = maybeStatuses)

    case _ => log.debug(s"Ignoring redundant snapshot message")
  }

  override def stateToMessage: VoyageManifestStateSnapshotMessage = VoyageManifestStateSnapshotMessage(
    None,
    Seq(),
    state.maybeSourceStatuses.flatMap(mss => feedStatusesToMessage(mss.feedStatuses)),
    Option(state.lastProcessedMarker)
  )

  override def receiveCommand: Receive = {
    case AddUpdatesSubscriber(queueActor) =>
      log.info("Received subscriber")
      maybeUpdatesSubscriber = Option(queueActor)

    case ManifestsFeedSuccess(DqManifests(updatedLZF, newManifests), createdAt) =>
      updateRequestsQueue = (sender(), VoyageManifests(newManifests)) :: updateRequestsQueue

      val newStatus = FeedStatusSuccess(createdAt.millisSinceEpoch, newManifests.size)
      state = state.copy(
        lastProcessedMarker = updatedLZF,
        maybeSourceStatuses = Option(state.addStatus(newStatus))
      )

      persistFeedStatus(newStatus)
      persistLastSeenFileName(updatedLZF)

      self ! ProcessNextUpdateRequest

    case ManifestsFeedFailure(message, failedAt) =>
      log.error(s"Failed to connect to AWS S3 for API data at ${failedAt.toISOString}. $message")
      val newStatus = FeedStatusFailure(failedAt.millisSinceEpoch, message)
      state = state.copy(maybeSourceStatuses = Option(state.addStatus(newStatus)))

      persistFeedStatus(newStatus)
      sender() ! StatusReply.Ack

    case PointInTimeQuery(pit, GetStateForDateRange(startMillis, endMillis)) =>
      sender() ! ManifestRouterActor.manifestsByDaySource(manifestLookup)(SDate(startMillis), SDate(endMillis), Option(pit))

    case GetForArrival(arrival) =>
      val scheduled = SDate(arrival.scheduled)
      val replyTo = sender()
      ManifestRouterActor
        .manifestsByDaySource(manifestLookup)(scheduled, scheduled, None)
        .map(manifests => manifests._2.manifests.find {
          _.maybeKey.exists(_ == arrival)
        }.toList)
        .runWith(Sink.seq)
        .map(_.flatten)
        .onComplete {
          case Success(manifests) =>
            manifests.headOption match {
              case Some(manifest) => replyTo ! ManifestFound(manifest)
              case None => replyTo ! ManifestNotFound
            }
          case Failure(throwable) =>
            log.error(s"Failed to look up manifest for $arrival: ${throwable.getMessage}")
            replyTo ! ManifestNotFound
        }

    case GetStateForDateRange(startMillis, endMillis) =>
      sender() ! ManifestRouterActor.manifestsByDaySource(manifestLookup)(SDate(startMillis), SDate(endMillis), None)

    case GetState =>
      sender() ! state

    case SaveSnapshotSuccess(md) =>
      log.info(s"Save snapshot success: $md")

    case SaveSnapshotFailure(md, cause) =>
      log.error(s"Save snapshot failure: $md", cause)

    case GetFeedStatuses =>
      log.debug(s"Received GetFeedStatuses request")
      sender() ! state.maybeSourceStatuses

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
    case _: UniqueArrival =>
      sender() ! None

    case unexpected => log.warn(s"Got an unexpected message: $unexpected")
  }

  def handleUpdatesAndAck(updates: VoyageManifests,
                          replyTo: ActorRef): Future[UpdatedMillis] = {
    processingRequest = true
    val eventualEffects = sendUpdates(updates)
    eventualEffects
      .map(updatedMillis => maybeUpdatesSubscriber.foreach(_ ! updatedMillis))
      .onComplete { _ =>
        processingRequest = false
        replyTo ! StatusReply.Ack
        self ! ProcessNextUpdateRequest
      }
    eventualEffects
  }

  private def sendUpdates(updates: VoyageManifests): Future[UpdatedMillis] = {
    val eventualUpdatedMinutesDiff: Source[UpdatedMillis, NotUsed] =
      Source(partitionUpdates(updates)).mapAsync(1) {
        case (partition, updates) => manifestsUpdate(partition, updates)
      }
    combineUpdateEffectsStream(eventualUpdatedMinutesDiff)
  }

  private def combineUpdateEffectsStream(effects: Source[UpdatedMillis, NotUsed]): Future[UpdatedMillis] =
    effects
      .fold[UpdatedMillis](UpdatedMillis.empty)(_ ++ _)
      .log(getClass.getName)
      .runWith(Sink.seq)
      .map(_.foldLeft[UpdatedMillis](UpdatedMillis.empty)(_ ++ _))
      .recover { case t =>
        log.error("Failed to combine update effects", t)
        UpdatedMillis.empty
      }

  private def persistLastSeenFileName(lastProcessedMarker: MillisSinceEpoch): Unit =
    persistAndMaybeSnapshot(lastProcessedMarkerToMessage(lastProcessedMarker))

  def persistFeedStatus(feedStatus: FeedStatus): Unit = persistAndMaybeSnapshot(feedStatusToMessage(feedStatus))

  private def lastProcessedMarkerToMessage(lastProcessedMarker: MillisSinceEpoch): VoyageManifestLatestFileNameMessage =
    VoyageManifestLatestFileNameMessage(
      createdAt = Option(SDate.now().millisSinceEpoch),
      lastProcessedMarker = Option(lastProcessedMarker)
    )

  def partitionUpdates(vms: VoyageManifests): Map[UtcDate, VoyageManifests] = vms
    .manifests
    .groupBy(_.scheduleArrivalDateTime.map(_.toUtcDate))
    .collect {
      case (Some(scheduled), vm) =>
        scheduled -> VoyageManifests(vm)
    }

  override val maybeSnapshotInterval: Option[Int] = Option(1000)
}
