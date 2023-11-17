package actors.persistent

import actors.StreamingJournalLike
import actors.persistent.staffing.GetFeedStatuses
import akka.actor.Props
import uk.gov.homeoffice.drt.feeds.{FeedSourceStatuses, FeedStatuses}
import uk.gov.homeoffice.drt.ports.{ApiFeedSource, FeedSource}
import uk.gov.homeoffice.drt.protobuf.messages.FlightsMessage.{FeedStatusMessage, FlightStateSnapshotMessage, FlightsDiffMessage}
import uk.gov.homeoffice.drt.protobuf.messages.VoyageManifest.VoyageManifestStateSnapshotMessage
import uk.gov.homeoffice.drt.protobuf.serialisation.FlightMessageConversion.{feedStatusFromFeedStatusMessage, feedStatusesFromFeedStatusesMessage, feedStatusesFromSnapshotMessage}

trait StreamingFeedStatusUpdates {
  val sourceType: FeedSource
  val persistenceId: String

  def streamingUpdatesProps(journalType: StreamingJournalLike): Props =
    Props(new StreamingUpdatesActor[Option[FeedSourceStatuses]](
      persistenceId,
      journalType,
      Option.empty[FeedSourceStatuses],
      {
        case stateMessage: FlightStateSnapshotMessage =>
          feedStatusesFromSnapshotMessage(stateMessage)
            .map(statuses => FeedSourceStatuses(sourceType, statuses))
        case VoyageManifestStateSnapshotMessage(_, _, maybeStatusMessages, _) =>
          maybeStatusMessages
            .map(feedStatusesFromFeedStatusesMessage)
            .map(fs => FeedSourceStatuses(ApiFeedSource, fs))
      },
      (state, msg) => msg match {
        case feedStatusMessage: FeedStatusMessage =>
          val newStatus = feedStatusFromFeedStatusMessage(feedStatusMessage)
          val updated = state match {
            case Some(feedSourceStatuses) => feedSourceStatuses.copy(
              feedStatuses = feedSourceStatuses.feedStatuses.add(newStatus)
            )
            case None => FeedSourceStatuses(sourceType, FeedStatuses(List(), None, None, None).add(newStatus))
          }
          Option(updated)
        case _ => state
      },
      (getState, getSender) => {
        case GetFeedStatuses =>
          val statuses = getState().getOrElse(FeedSourceStatuses(sourceType, FeedStatuses(List(), None, None, None)))
          getSender() ! statuses
      }
    ))
}
