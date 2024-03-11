package actors.persistent.arrivals

import actors.PartitionedPortStateActor.GetFlights
import actors.persistent.StreamingFeedStatusUpdates
import actors.persistent.staffing.GetFeedStatuses
import akka.persistence.{SaveSnapshotFailure, SaveSnapshotSuccess}
import drt.server.feeds.{ArrivalsFeedFailure, ArrivalsFeedSuccess}
import drt.shared.FlightsApi.Flights
import uk.gov.homeoffice.drt.time.SDateLike
import org.slf4j.{Logger, LoggerFactory}
import uk.gov.homeoffice.drt.actor.acking.AckingReceiver.StreamCompleted
import uk.gov.homeoffice.drt.actor.commands.Commands.{AddUpdatesSubscriber, GetState}
import uk.gov.homeoffice.drt.protobuf.messages.FlightsMessage.FlightsDiffMessage
import uk.gov.homeoffice.drt.ports.{FeedSource, LiveBaseFeedSource}

object CiriumLiveArrivalsActor extends StreamingFeedStatusUpdates {
  val persistenceId = "actors.LiveBaseArrivalsActor-live-base"
  override val sourceType: FeedSource = LiveBaseFeedSource
}

class CiriumLiveArrivalsActor(val now: () => SDateLike,
                              expireAfterMillis: Int) extends ArrivalsActor(now, expireAfterMillis, LiveBaseFeedSource) {
  override def persistenceId: String = CiriumLiveArrivalsActor.persistenceId

  override val maybeSnapshotInterval: Option[Int] = Option(500)

  val log: Logger = LoggerFactory.getLogger(getClass)

  def consumeDiffsMessage(diffsMessage: FlightsDiffMessage): Unit = consumeUpdates(diffsMessage)

  override def receiveCommand: Receive = {
    case ArrivalsFeedSuccess(Flights(incomingArrivals), createdAt) =>
      handleFeedSuccess(incomingArrivals, createdAt)

    case ArrivalsFeedFailure(message, createdAt) =>
      handleFeedFailure(message, createdAt)

    case AddUpdatesSubscriber(newSubscriber) =>
      maybeSubscriber = Option(newSubscriber)

    case GetState =>
      sender() ! state

    case GetFlights(from, to) =>
      sender() ! state.arrivals.filter { case (ua, _) =>
        ua.scheduled >= from && ua.scheduled <= to
      }

    case GetFeedStatuses =>
      sender() ! state.maybeSourceStatuses

    case SaveSnapshotSuccess(md) =>
      log.info(s"Save snapshot success: $md")

    case SaveSnapshotFailure(md, cause) =>
      log.error(s"Save snapshot failure: $md", cause)

    case StreamCompleted => log.warn("Received shutdown")

    case unexpected => log.info(s"Received unexpected message ${unexpected.getClass}")
  }

}
