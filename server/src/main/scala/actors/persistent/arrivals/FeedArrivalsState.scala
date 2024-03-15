package actors.persistent.arrivals

import drt.server.feeds.FeedArrival
import uk.gov.homeoffice.drt.arrivals.{Arrival, UniqueArrival}
import uk.gov.homeoffice.drt.feeds.{FeedSourceStatuses, FeedStateLike}
import uk.gov.homeoffice.drt.ports.FeedSource

import scala.collection.immutable.SortedMap

case class FeedArrivalsState(arrivals: SortedMap[UniqueArrival, FeedArrival],
                         feedSource: FeedSource,
                         maybeSourceStatuses: Option[FeedSourceStatuses]) extends FeedStateLike {
  def clear(): FeedArrivalsState = {
    copy(arrivals = SortedMap(), maybeSourceStatuses = None)
  }

  def ++(incoming: Iterable[FeedArrival]): FeedArrivalsState = {
    copy(arrivals = arrivals ++ incoming.map(a => (a.unique, arrivals.get(a.unique).map(_.update(a)).getOrElse(a))))
  }

  def ++(incoming: Iterable[FeedArrival], statuses: Option[FeedSourceStatuses]): FeedArrivalsState =
    ++(incoming).copy(maybeSourceStatuses = statuses)
}

object FeedArrivalsState {
  def empty(feedSource: FeedSource): FeedArrivalsState = FeedArrivalsState(SortedMap(), feedSource, None)
}
