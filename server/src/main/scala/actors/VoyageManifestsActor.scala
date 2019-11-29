package actors

import actors.FlightMessageConversion.{feedStatusFromFeedStatusMessage, feedStatusToMessage, feedStatusesFromFeedStatusesMessage}
import actors.acking.AckingReceiver.StreamCompleted
import akka.persistence._
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.core.PassengerTypeCalculatorValues.DocumentType
import passengersplits.parsing.VoyageManifestParser.{ManifestDateOfArrival, ManifestTimeOfArrival, PassengerInfoJson, VoyageManifest}
import server.feeds.{BestManifestsFeedSuccess, DqManifests, ManifestsFeedFailure, ManifestsFeedSuccess}
import server.protobuf.messages.FlightsMessage.FeedStatusMessage
import server.protobuf.messages.VoyageManifest._
import services.SDate
import services.graphstages.Crunch

import scala.collection.mutable

case class VoyageManifestState(manifests: mutable.SortedMap[MilliDate, VoyageManifest],
                               var latestZipFilename: String,
                               feedName: String,
                               var maybeFeedStatuses: Option[FeedStatuses]) extends FeedStateLike

case object GetLatestZipFilename

class VoyageManifestsActor(val initialSnapshotBytesThreshold: Int,
                           now: () => SDateLike,
                           expireAfterMillis: Long,
                           val initialMaybeSnapshotInterval: Option[Int]) extends RecoveryActorLike with PersistentDrtActor[VoyageManifestState] {
  val log: Logger = LoggerFactory.getLogger(getClass)

  val name = "API"

  var state: VoyageManifestState = initialState

  override val maybeSnapshotInterval: Option[Int] = initialMaybeSnapshotInterval
  override val snapshotBytesThreshold: Int = initialSnapshotBytesThreshold

  def initialState = VoyageManifestState(
    manifests = mutable.SortedMap[MilliDate, VoyageManifest](),
    latestZipFilename = defaultLatestZipFilename,
    feedName = name,
    maybeFeedStatuses = None
  )

  def defaultLatestZipFilename: String = {
    val yesterday = SDate.now().addDays(-1)
    val yymmddYesterday = f"${yesterday.getFullYear() - 2000}%02d${yesterday.getMonth()}%02d${yesterday.getDate()}%02d"
    s"drt_dq_$yymmddYesterday"
  }

  override def persistenceId: String = "arrival-manifests"

  def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case VoyageManifestStateSnapshotMessage(Some(latestFilename), manifests, maybeStatusMessages) =>
      newStateManifests(state.manifests, manifests.map(voyageManifestFromMessage))
      val maybeStatuses = maybeStatusMessages.map(feedStatusesFromFeedStatusesMessage)
      state.latestZipFilename = latestFilename
      state.maybeFeedStatuses = maybeStatuses

    case lzf: String =>
      log.info(s"Updating state from latestZipFilename $lzf")
      state = state.copy(latestZipFilename = lzf)
  }

  def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case recoveredLZF: String =>
      state = state.copy(latestZipFilename = recoveredLZF)

    case m@VoyageManifestLatestFileNameMessage(_, Some(latestFilename)) =>
      state = state.copy(latestZipFilename = latestFilename)
      bytesSinceSnapshotCounter += m.serializedSize
      messagesPersistedSinceSnapshotCounter += 1

    case m@VoyageManifestsMessage(_, manifestMessages) =>
      val updatedManifests = manifestMessages.map(voyageManifestFromMessage)
      newStateManifests(state.manifests, updatedManifests)
      bytesSinceSnapshotCounter += m.serializedSize
      messagesPersistedSinceSnapshotCounter += 1

    case feedStatusMessage: FeedStatusMessage =>
      val status = feedStatusFromFeedStatusMessage(feedStatusMessage)
      state = state.copy(maybeFeedStatuses = Option(state.addStatus(status)))
      bytesSinceSnapshotCounter += feedStatusMessage.serializedSize
      messagesPersistedSinceSnapshotCounter += 1
  }

  def newStateManifests(existing: mutable.SortedMap[MilliDate, VoyageManifest], updates: Seq[VoyageManifest]): Unit = {
    existing ++= updates.map(vm => (MilliDate(vm.scheduleArrivalDateTime.map(_.millisSinceEpoch).getOrElse(0L)), vm))
    Crunch.purgeExpired(existing, MilliDate.atTime, now, expireAfterMillis.toInt)
  }

  override def receiveCommand: Receive = {
    case ManifestsFeedSuccess(DqManifests(updatedLZF, newManifests), createdAt) =>
      log.info(s"Received ${newManifests.size} manifests, up to file $updatedLZF from connection at ${createdAt.toISOString()}")

      val updates = newManifests -- state.manifests.values.toSet
      newStateManifests(state.manifests, newManifests.toSeq)

      val newStatus = FeedStatusSuccess(createdAt.millisSinceEpoch, updates.size)

      if (updates.nonEmpty) persistManifests(updates) else log.info(s"No new manifests to persist")

      if (updatedLZF != state.latestZipFilename) persistLastSeenFileName(updatedLZF)

      state.latestZipFilename = updatedLZF
      state.maybeFeedStatuses = Option(state.addStatus(newStatus))

      persistFeedStatus(newStatus)

    case ManifestsFeedFailure(message, failedAt) =>
      log.error(s"Failed to connect to AWS S3 for API data at ${failedAt.toISOString()}. $message")
      val newStatus = FeedStatusFailure(failedAt.millisSinceEpoch, message)
      state = state.copy(maybeFeedStatuses = Option(state.addStatus(newStatus)))

      persistFeedStatus(newStatus)

    case _: BestManifestsFeedSuccess =>

    case GetFeedStatuses =>
      log.debug(s"Received GetFeedStatuses request")
      sender() ! state.maybeFeedStatuses

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

    case StreamCompleted => log.warn("Received shutdown")

    case unexpected => log.info(s"Received unexpected message ${unexpected.getClass}")
  }

  def persistLastSeenFileName(lastSeenFileName: String): Unit = persistAndMaybeSnapshot(latestFilenameToMessage(lastSeenFileName))

  def persistManifests(updatedManifests: Set[VoyageManifest]): Unit = persistAndMaybeSnapshot(voyageManifestsToMessage(updatedManifests))

  def persistFeedStatus(feedStatus: FeedStatus): Unit = persistAndMaybeSnapshot(feedStatusToMessage(feedStatus))

  def voyageManifestsToMessage(updatedManifests: Set[VoyageManifest]): VoyageManifestsMessage = VoyageManifestsMessage(
    Option(SDate.now().millisSinceEpoch),
    updatedManifests.map(voyageManifestToMessage).toList
  )

  def latestFilenameToMessage(filename: String): VoyageManifestLatestFileNameMessage = {
    VoyageManifestLatestFileNameMessage(
      createdAt = Option(SDate.now().millisSinceEpoch),
      latestFilename = Option(filename))
  }

  def passengerInfoToMessage(pi: PassengerInfoJson): PassengerInfoJsonMessage = {
    PassengerInfoJsonMessage(
      documentType = pi.DocumentType.map(_.toString),
      documentIssuingCountryCode = Option(pi.DocumentIssuingCountryCode.toString),
      eeaFlag = Option(pi.EEAFlag),
      age = pi.Age,
      disembarkationPortCode = pi.DisembarkationPortCode.map(_.toString),
      inTransitFlag = Option(pi.InTransitFlag),
      disembarkationPortCountryCode = pi.DisembarkationPortCountryCode.map(_.toString),
      nationalityCountryCode = pi.NationalityCountryCode.map(_.toString),
      passengerIdentifier = pi.PassengerIdentifier
    )
  }

  def voyageManifestToMessage(vm: VoyageManifest): VoyageManifestMessage = {
    VoyageManifestMessage(
      createdAt = Option(SDate.now().millisSinceEpoch),
      eventCode = Option(vm.EventCode.toString),
      arrivalPortCode = Option(vm.ArrivalPortCode.iata),
      departurePortCode = Option(vm.DeparturePortCode.iata),
      voyageNumber = Option(vm.VoyageNumber.toString),
      carrierCode = Option(vm.CarrierCode.code),
      scheduledDateOfArrival = Option(vm.ScheduledDateOfArrival.date),
      scheduledTimeOfArrival = Option(vm.ScheduledTimeOfArrival.time),
      passengerList = vm.PassengerList.map(passengerInfoToMessage)
    )
  }

  override def stateToMessage: VoyageManifestStateSnapshotMessage = VoyageManifestStateSnapshotMessage(
    Option(state.latestZipFilename), stateVoyageManifestsToMessages(state.manifests), state.maybeFeedStatuses.flatMap(FlightMessageConversion.feedStatusesToMessage))

  def stateVoyageManifestsToMessages(manifests: mutable.SortedMap[MilliDate, VoyageManifest]): Seq[VoyageManifestMessage] = {
    manifests.map { case (_, vm) => voyageManifestToMessage(vm) }.toList
  }

  def passengerInfoFromMessage(m: PassengerInfoJsonMessage): PassengerInfoJson = {
    PassengerInfoJson(
      DocumentType = m.documentType.map(DocumentType(_)),
      DocumentIssuingCountryCode = Nationality(m.documentIssuingCountryCode.getOrElse("")),
      EEAFlag = m.eeaFlag.getOrElse(""),
      Age = m.age,
      DisembarkationPortCode = m.disembarkationPortCode.map(PortCode(_)),
      InTransitFlag = m.inTransitFlag.getOrElse(""),
      DisembarkationPortCountryCode = m.disembarkationPortCountryCode.map(Nationality(_)),
      NationalityCountryCode = m.nationalityCountryCode.map(Nationality(_)),
      PassengerIdentifier = m.passengerIdentifier
    )
  }

  def voyageManifestFromMessage(m: VoyageManifestMessage): VoyageManifest = {
    VoyageManifest(
      EventCode = EventType(m.eventCode.getOrElse("")),
      ArrivalPortCode = PortCode(m.arrivalPortCode.getOrElse("")),
      DeparturePortCode = PortCode(m.departurePortCode.getOrElse("")),
      VoyageNumber = VoyageNumber(m.voyageNumber.getOrElse("")),
      CarrierCode = CarrierCode(m.carrierCode.getOrElse("")),
      ScheduledDateOfArrival = ManifestDateOfArrival(m.scheduledDateOfArrival.getOrElse("")),
      ScheduledTimeOfArrival = ManifestTimeOfArrival(m.scheduledTimeOfArrival.getOrElse("")),
      PassengerList = m.passengerList.toList.map(passengerInfoFromMessage)
    )
  }
}
