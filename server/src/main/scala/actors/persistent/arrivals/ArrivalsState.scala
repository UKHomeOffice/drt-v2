package actors.persistent.arrivals

import drt.shared.FeedSourceStatuses
import uk.gov.homeoffice.drt.arrivals.{Arrival, UniqueArrival}
import uk.gov.homeoffice.drt.ports.FeedSource

import scala.collection.immutable.SortedMap

case class ArrivalsState(arrivals: SortedMap[UniqueArrival, Arrival],
                         feedSource: FeedSource,
                         maybeSourceStatuses: Option[FeedSourceStatuses]) extends FeedStateLike {
  def clear(): ArrivalsState = {
    copy(arrivals = SortedMap(), maybeSourceStatuses = None)
  }

  def ++(incoming: Iterable[Arrival]): ArrivalsState = {
    copy(arrivals = arrivals ++ incoming.map(a => (a.unique, arrivals.get(a.unique).map(_.update(a)).getOrElse(a))))
  }

  def ++(incoming: Iterable[Arrival], statuses: Option[FeedSourceStatuses]): ArrivalsState =
    ++(incoming).copy(maybeSourceStatuses = statuses)
}

object ArrivalsState {
  def empty(feedSource: FeedSource): ArrivalsState = ArrivalsState(SortedMap(), feedSource, None)
}
