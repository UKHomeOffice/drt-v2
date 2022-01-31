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
}
