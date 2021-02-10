package actors.queues

import actors.DrtStaticParameters.expireAfterMillis
import actors.PartitionedPortStateActor._
import actors.acking.AckingReceiver.Ack
import actors.minutes.MinutesActorLike.{ManifestLookup, ManifestsUpdate, ProcessNextUpdateRequest}
import actors.serializers.FlightMessageConversion
import actors.serializers.FlightMessageConversion.{feedStatusFromFeedStatusMessage, feedStatusToMessage, feedStatusesFromFeedStatusesMessage}
import actors.{FeedStateLike, GetFeedStatuses, GetState, RecoveryActorLike}
import akka.NotUsed
import akka.actor.{ActorRef, Props}
import akka.persistence.{SaveSnapshotFailure, SaveSnapshotSuccess}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.Timeout
import drt.server.feeds.api.S3ApiProvider
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared._
import drt.shared.dates.UtcDate
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.parsing.VoyageManifestParser.VoyageManifests
import server.feeds.{DqManifests, ManifestsFeedFailure, ManifestsFeedSuccess}
import server.protobuf.messages.FlightsMessage.FeedStatusMessage
import server.protobuf.messages.VoyageManifest.{VoyageManifestLatestFileNameMessage, VoyageManifestStateSnapshotMessage}
import services.SDate

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.language.postfixOps

object ManifestRouterActor {


  def manifestsByDaySource(manifestsByDayLookup: ManifestLookup)
                          (start: SDateLike,
                           end: SDateLike,
                           maybePit: Option[MillisSinceEpoch]): Source[VoyageManifests, NotUsed] = {
    DateRange.utcDateRangeSource(start, end)
      .mapAsync(1) {
        date =>
          manifestsByDayLookup(date, maybePit)
      }
  }

  def runAndCombine(source: Future[Source[VoyageManifests, NotUsed]])(implicit mat: ActorMaterializer, ec: ExecutionContext): Future[VoyageManifests] = source
    .flatMap(
      _.log(getClass.getName).runWith(Sink.reduce[VoyageManifests](_ ++ _))
    )

  def props(manifestLookup: ManifestLookup, manifestsUpdate: ManifestsUpdate): Props = Props(
    new ManifestRouterActor(manifestLookup, manifestsUpdate)
  )
}

case class ApiFeedState(latestZipFilename: String, maybeSourceStatuses: Option[FeedSourceStatuses]) extends FeedStateLike {
  override def feedSource: FeedSource = ApiFeedSource
}

case class GetManifestForFlight(arrivalKey: ArrivalKey)

class ManifestRouterActor(manifestLookup: ManifestLookup, manifestsUpdate: ManifestsUpdate) extends RecoveryActorLike {

  override def persistenceId: String = "arrival-manifests"

  implicit val dispatcher: ExecutionContextExecutor = context.dispatcher
  implicit val mat: ActorMaterializer = ActorMaterializer.create(context)
  implicit val timeout: Timeout = new Timeout(60 seconds)

  var updateRequestsQueue: List[(ActorRef, VoyageManifests)] = List()
  var processingRequest: Boolean = false

  val initialState: ApiFeedState = ApiFeedState(
    S3ApiProvider.defaultApiLatestZipFilename(() => SDate.now(), expireAfterMillis),
    None
  )

  var state: ApiFeedState = initialState

  override val log: Logger = LoggerFactory.getLogger(getClass)

  override def now: () => SDateLike = () => SDate.now()

  override val recoveryStartMillis: MillisSinceEpoch = now().millisSinceEpoch

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case recoveredLZF: String =>
      state = state.copy(latestZipFilename = recoveredLZF)

    case VoyageManifestLatestFileNameMessage(_, Some(latestFilename)) =>
      state = state.copy(latestZipFilename = latestFilename)

    case feedStatusMessage: FeedStatusMessage =>
      val status = feedStatusFromFeedStatusMessage(feedStatusMessage)
      state = state.copy(maybeSourceStatuses = Option(state.addStatus(status)))
  }

  def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case VoyageManifestStateSnapshotMessage(Some(latestFilename), _, maybeStatusMessages) =>
      val maybeStatuses = maybeStatusMessages
        .map(feedStatusesFromFeedStatusesMessage)
        .map(fs => FeedSourceStatuses(ApiFeedSource, fs))

      state = state.copy(latestZipFilename = latestFilename, maybeSourceStatuses = maybeStatuses)

    case lzf: String =>
      log.debug(s"Ignoring old snapshot message $lzf")
  }

  override def stateToMessage: VoyageManifestStateSnapshotMessage = VoyageManifestStateSnapshotMessage(
    Option(state.latestZipFilename),
    Seq(),
    state.maybeSourceStatuses.flatMap(mss => FlightMessageConversion.feedStatusesToMessage(mss.feedStatuses))
  )

  override def receiveCommand: Receive = {

    case ManifestsFeedSuccess(DqManifests(updatedLZF, newManifests), createdAt) =>
      updateRequestsQueue = (sender(), VoyageManifests(newManifests)) :: updateRequestsQueue

      val newStatus = FeedStatusSuccess(createdAt.millisSinceEpoch, newManifests.size)
      state = state.copy(
        latestZipFilename = updatedLZF,
        maybeSourceStatuses = Option(state.addStatus(newStatus))
      )

      persistFeedStatus(newStatus)
      persistLastSeenFileName(updatedLZF)

      self ! ProcessNextUpdateRequest

    case ManifestsFeedFailure(message, failedAt) =>
      log.error(s"Failed to connect to AWS S3 for API data at ${failedAt.toISOString()}. $message")
      val newStatus = FeedStatusFailure(failedAt.millisSinceEpoch, message)
      state = state.copy(maybeSourceStatuses = Option(state.addStatus(newStatus)))

      persistFeedStatus(newStatus)
      sender() ! Ack

    case PointInTimeQuery(pit, GetStateForDateRange(startMillis, endMillis)) =>
      sender() ! ManifestRouterActor.manifestsByDaySource(manifestLookup)(SDate(startMillis), SDate(endMillis), Option(pit))

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

  def persistLastSeenFileName(lastSeenFileName: String): Unit =
    persistAndMaybeSnapshot(latestFilenameToMessage(lastSeenFileName))


  def persistFeedStatus(feedStatus: FeedStatus): Unit = persistAndMaybeSnapshot(feedStatusToMessage(feedStatus))

  def latestFilenameToMessage(filename: String): VoyageManifestLatestFileNameMessage = {
    VoyageManifestLatestFileNameMessage(
      createdAt = Option(SDate.now().millisSinceEpoch),
      latestFilename = Option(filename))
  }

  def manifestsByDay(vms: VoyageManifests): Map[UtcDate, VoyageManifests] = vms
    .manifests
    .groupBy(_.scheduleArrivalDateTime.map(_.toUtcDate))
    .collect {
      case (Some(scheduled), vm) =>
        scheduled -> VoyageManifests(vm)
    }
}
