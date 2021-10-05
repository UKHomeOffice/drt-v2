package actors.persistent.arrivals

import drt.shared.{FeedSourceStatuses, FeedStatus, FeedStatuses}
import uk.gov.homeoffice.drt.ports.FeedSource

trait FeedStateLike {
  def feedSource: FeedSource

  def maybeSourceStatuses: Option[FeedSourceStatuses]

  def addStatus(newStatus: FeedStatus): FeedSourceStatuses = {
    maybeSourceStatuses match {
      case Some(feedSourceStatuses) => feedSourceStatuses.copy(
        feedStatuses = feedSourceStatuses.feedStatuses.add(newStatus)
      )
      case None => FeedSourceStatuses(feedSource, FeedStatuses(List(), None, None, None).add(newStatus))
    }
  }
}
