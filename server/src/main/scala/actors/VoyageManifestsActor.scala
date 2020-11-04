package actors

import actors.FlightMessageConversion.{feedStatusFromFeedStatusMessage, feedStatusToMessage, feedStatusesFromFeedStatusesMessage}
import actors.acking.AckingReceiver.StreamCompleted
import akka.persistence._
import drt.server.feeds.api.S3ApiProvider
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.parsing.VoyageManifestParser._
import server.feeds.{BestManifestsFeedSuccess, DqManifests, ManifestsFeedFailure, ManifestsFeedSuccess}
import server.protobuf.messages.FlightsMessage.FeedStatusMessage
import server.protobuf.messages.VoyageManifest._
import services.SDate
import services.graphstages.Crunch

import scala.collection.immutable.SortedMap

case class VoyageManifestState(manifests: SortedMap[MilliDate, VoyageManifest],
                               latestZipFilename: String,
                               feedSource: FeedSource,
                               maybeSourceStatuses: Option[FeedSourceStatuses]) extends FeedStateLike

case object GetLatestZipFilename

class VoyageManifestsActor(val initialSnapshotBytesThreshold: Int,
                           val now: () => SDateLike,
                           expireAfterMillis: Int,
                           val initialMaybeSnapshotInterval: Option[Int]) extends RecoveryActorLike with PersistentDrtActor[VoyageManifestState] {


  val log: Logger = LoggerFactory.getLogger(getClass)

  val feedSource: FeedSource = ApiFeedSource

  var state: VoyageManifestState = initialState

  override val maybeSnapshotInterval: Option[Int] = initialMaybeSnapshotInterval
  override val snapshotBytesThreshold: Int = initialSnapshotBytesThreshold
  override val recoveryStartMillis: MillisSinceEpoch = now().millisSinceEpoch

  def initialState: VoyageManifestState = VoyageManifestState(
    manifests = SortedMap[MilliDate, VoyageManifest](),
    latestZipFilename = S3ApiProvider.defaultApiLatestZipFilename(now, expireAfterMillis),
    feedSource = feedSource,
    maybeSourceStatuses = None
  )

  override def persistenceId: String = "arrival-manifests"

  def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case VoyageManifestStateSnapshotMessage(Some(latestFilename), manifests, maybeStatusMessages) =>
      val newManifests = newStateManifests(state.manifests, manifests.map(ManifestMessageConversion.voyageManifestFromMessage))
      val maybeStatuses = maybeStatusMessages
        .map(feedStatusesFromFeedStatusesMessage)
        .map(fs => FeedSourceStatuses(feedSource, fs))

      state = state.copy(
        manifests = newManifests,
        latestZipFilename = latestFilename,
        maybeSourceStatuses = maybeStatuses)

    case lzf: String =>
      log.info(s"Updating state from latestZipFilename $lzf")
      state = state.copy(latestZipFilename = lzf)
  }

  def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case recoveredLZF: String =>
      state = state.copy(latestZipFilename = recoveredLZF)

    case VoyageManifestLatestFileNameMessage(_, Some(latestFilename)) =>
      state = state.copy(latestZipFilename = latestFilename)

    case VoyageManifestsMessage(_, manifestMessages) =>
      val updatedManifests = manifestMessages.map(ManifestMessageConversion.voyageManifestFromMessage)
      state = state.copy(manifests = newStateManifests(state.manifests, updatedManifests))

    case feedStatusMessage: FeedStatusMessage =>
      val status = feedStatusFromFeedStatusMessage(feedStatusMessage)
      state = state.copy(maybeSourceStatuses = Option(state.addStatus(status)))
  }

  def newStateManifests(existing: SortedMap[MilliDate, VoyageManifest], updates: Seq[VoyageManifest]): SortedMap[MilliDate, VoyageManifest] = {
    existing ++ updates.map(vm => (MilliDate(vm.scheduleArrivalDateTime.map(_.millisSinceEpoch).getOrElse(0L)), vm))
    Crunch.purgeExpired(existing, MilliDate.atTime, now, expireAfterMillis.toInt)
  }

  override def receiveCommand: Receive = {
    case ManifestsFeedSuccess(DqManifests(updatedLZF, newManifests), createdAt) =>
      log.info(s"Received ${newManifests.size} manifests, up to file $updatedLZF from connection at ${createdAt.toISOString()}")

      val updates = newManifests -- state.manifests.values.toSet
      val updatedManifests = newStateManifests(state.manifests, newManifests.toSeq)

      val newStatus = FeedStatusSuccess(createdAt.millisSinceEpoch, updates.size)

      if (updates.nonEmpty) persistManifests(updates) else log.info(s"No new manifests to persist")

      if (updatedLZF != state.latestZipFilename) persistLastSeenFileName(updatedLZF)

      state = state.copy(
        manifests = updatedManifests,
        latestZipFilename = updatedLZF,
        maybeSourceStatuses = Option(state.addStatus(newStatus)))

      persistFeedStatus(newStatus)

    case ManifestsFeedFailure(message, failedAt) =>
      log.error(s"Failed to connect to AWS S3 for API data at ${failedAt.toISOString()}. $message")
      val newStatus = FeedStatusFailure(failedAt.millisSinceEpoch, message)
      state = state.copy(maybeSourceStatuses = Option(state.addStatus(newStatus)))

      persistFeedStatus(newStatus)

    case _: BestManifestsFeedSuccess =>

    case GetFeedStatuses =>
      log.debug(s"Received GetFeedStatuses request")
      sender() ! state.maybeSourceStatuses

    case _: UniqueArrival =>
      sender() ! None

    case GetState =>
      log.info(s"Being asked for state. Sending ${state.manifests.size} manifests and latest filename: ${state.latestZipFilename}")
      sender() ! state

    case GetLatestZipFilename =>
      log.info(s"Received GetLatestZipFilename request. Sending ${state.latestZipFilename}")
      sender() ! state.latestZipFilename

    case SaveSnapshotSuccess(md) =>
      log.info(s"Save snapshot success: $md")

    case SaveSnapshotFailure(md, cause) =>
      log.error(s"Save snapshot failure: $md", cause)

    case StreamCompleted => log.warn("Stream completed")

    case unexpected => log.info(s"Received unexpected message ${unexpected.getClass}")
  }

  def persistLastSeenFileName(lastSeenFileName: String): Unit = persistAndMaybeSnapshot(latestFilenameToMessage(lastSeenFileName))

  def persistManifests(updatedManifests: Set[VoyageManifest]): Unit = persistAndMaybeSnapshot(voyageManifestsToMessage(updatedManifests))

  def persistFeedStatus(feedStatus: FeedStatus): Unit = persistAndMaybeSnapshot(feedStatusToMessage(feedStatus))

  def voyageManifestsToMessage(updatedManifests: Set[VoyageManifest]): VoyageManifestsMessage = VoyageManifestsMessage(
    Option(SDate.now().millisSinceEpoch),
    updatedManifests.map(ManifestMessageConversion.voyageManifestToMessage).toList
  )

  def latestFilenameToMessage(filename: String): VoyageManifestLatestFileNameMessage = {
    VoyageManifestLatestFileNameMessage(
      createdAt = Option(SDate.now().millisSinceEpoch),
      latestFilename = Option(filename))
  }

  override def stateToMessage: VoyageManifestStateSnapshotMessage = VoyageManifestStateSnapshotMessage(
    Option(state.latestZipFilename),
    stateVoyageManifestsToMessages(state.manifests),
    state.maybeSourceStatuses.flatMap(mss => FlightMessageConversion.feedStatusesToMessage(mss.feedStatuses))
  )

  def stateVoyageManifestsToMessages(manifests: SortedMap[MilliDate, VoyageManifest]): Seq[VoyageManifestMessage] =
    manifests
      .map { case (_, vm) => ManifestMessageConversion.voyageManifestToMessage(vm) }
      .toList

}
