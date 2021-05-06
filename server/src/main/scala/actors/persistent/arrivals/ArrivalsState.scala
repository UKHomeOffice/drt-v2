package actors.persistent.arrivals

import drt.shared.api.Arrival
import drt.shared.{FeedSource, FeedSourceStatuses, UniqueArrival}

import scala.collection.immutable.SortedMap

case class ArrivalsState(arrivals: SortedMap[UniqueArrival, Arrival],
                         feedSource: FeedSource,
                         maybeSourceStatuses: Option[FeedSourceStatuses]) extends FeedStateLike {
  def clear(): ArrivalsState = {
    copy(arrivals = SortedMap(), maybeSourceStatuses = None)
  }
}
