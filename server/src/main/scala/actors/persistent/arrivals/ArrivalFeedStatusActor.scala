package actors.persistent.arrivals

import actors.persistent.staffing.GetFeedStatuses
import akka.persistence.{SaveSnapshotFailure, SaveSnapshotSuccess}
import drt.server.feeds.{ArrivalsFeedFailure, ArrivalsFeedSuccess}
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import uk.gov.homeoffice.drt.actor.acking.AckingReceiver.StreamCompleted
import uk.gov.homeoffice.drt.actor.state.ArrivalsState
import uk.gov.homeoffice.drt.actor.{PersistentDrtActor, RecoveryActorLike, Sizes}
import uk.gov.homeoffice.drt.feeds.{FeedSourceStatuses, FeedStatus, FeedStatusFailure, FeedStatusSuccess}
import uk.gov.homeoffice.drt.ports.FeedSource
import uk.gov.homeoffice.drt.protobuf.messages.FlightsMessage.{FeedStatusMessage, FlightStateSnapshotMessage}
import uk.gov.homeoffice.drt.protobuf.serialisation.FlightMessageConversion._
import uk.gov.homeoffice.drt.time.SDateLike


class ArrivalFeedStatusActor(feedSource: FeedSource,
                             override val persistenceId: String,
                            ) extends RecoveryActorLike with PersistentDrtActor[ArrivalsState] {
  override protected val log: Logger = LoggerFactory.getLogger(getClass)

  override val maybeSnapshotInterval: Option[Int] = Option(500)

  var state: ArrivalsState = initialState

  override val snapshotBytesThreshold: Int = Sizes.oneMegaByte

  override def initialState: ArrivalsState = ArrivalsState.empty(feedSource)

  def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case stateMessage: FlightStateSnapshotMessage =>
      state = state.copy(maybeSourceStatuses = feedStatusesFromSnapshotMessage(stateMessage)
        .map(fs => FeedSourceStatuses(feedSource, fs)))
  }

  def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case feedStatusMessage: FeedStatusMessage =>
      val status = feedStatusFromFeedStatusMessage(feedStatusMessage)
      state = state.copy(maybeSourceStatuses = Option(state.addStatus(status)))
    case _ => ()
  }

  override def stateToMessage: GeneratedMessage = arrivalsStateToSnapshotMessage(state)

  override def receiveCommand: Receive = {
    case ArrivalsFeedSuccess(incomingArrivals, createdAt) =>
      handleFeedSuccess(incomingArrivals.size, createdAt)

    case ArrivalsFeedFailure(message, createdAt) =>
      handleFeedFailure(message, createdAt)

    case GetFeedStatuses =>
      sender() ! state.maybeSourceStatuses

    case SaveSnapshotSuccess(md) =>
      log.info(s"Save snapshot success: $md")

    case SaveSnapshotFailure(md, cause) =>
      log.error(s"Save snapshot failure: $md", cause)

    case StreamCompleted => log.warn("Received shutdown")

    case unexpected => log.error(s"Received unexpected message ${unexpected.getClass}")
  }

  private def handleFeedFailure(message: String, createdAt: SDateLike): Unit = {
    log.warn("Received feed failure")
    val newStatus = FeedStatusFailure(createdAt.millisSinceEpoch, message)
    state = state.copy(maybeSourceStatuses = Option(state.addStatus(newStatus)))
    persistFeedStatus(FeedStatusFailure(createdAt.millisSinceEpoch, message))
  }

  private def handleFeedSuccess(arrivalCount: Int, createdAt: SDateLike): Unit = {
    log.info(s"Received ${arrivalCount} arrivals ${state.feedSource.displayName}")
    val newStatus = FeedStatusSuccess(createdAt.millisSinceEpoch, arrivalCount)
    state = state.copy(maybeSourceStatuses = Option(state.addStatus(newStatus)))
    persistFeedStatus(newStatus)
  }

  private def persistFeedStatus(feedStatus: FeedStatus): Unit = persistAndMaybeSnapshot(feedStatusToMessage(feedStatus))
}
