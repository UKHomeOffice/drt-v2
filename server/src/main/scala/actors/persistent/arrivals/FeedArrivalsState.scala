package actors.persistent.arrivals

import uk.gov.homeoffice.drt.arrivals.{FeedArrival, UniqueArrival}
import uk.gov.homeoffice.drt.feeds.{FeedSourceStatuses, FeedStateLike}
import uk.gov.homeoffice.drt.ports.FeedSource

import scala.collection.immutable.SortedMap

case class FeedArrivalsState[A <: FeedArrival](arrivals: Map[UniqueArrival, A],
                                               feedSource: FeedSource,
                                               maybeSourceStatuses: Option[FeedSourceStatuses]) extends FeedStateLike {
  def clear(): FeedArrivalsState[A] = {
    copy(arrivals = SortedMap(), maybeSourceStatuses = None)
  }

  def ++(incoming: Iterable[A]): FeedArrivalsState[A] = {
    copy(arrivals = arrivals ++ incoming.map(a => {
      val arrival = arrivals
        .get(a.unique)
        .map(_.update(a))
        .collect({ case a: A => a })
        .getOrElse(a)
      (a.unique, arrival)
    }))
  }

  def ++(incoming: Iterable[A], statuses: Option[FeedSourceStatuses]): FeedArrivalsState[A] =
    ++(incoming).copy(maybeSourceStatuses = statuses)
}

object FeedArrivalsState {
  def empty[A <: FeedArrival](feedSource: FeedSource): FeedArrivalsState[A] = FeedArrivalsState[A](Map(), feedSource, None)
}
