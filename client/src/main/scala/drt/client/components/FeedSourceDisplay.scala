package drt.client.components

import uk.gov.homeoffice.drt.ports.{ FeedSource, LiveBaseFeedSource, LiveFeedSource }

object FeedSourceDisplay {
  val CiriumLiveArrivals: String = "Live arrivals (Cirium)"

  def displayName(feedSource: FeedSource, hasOperatorLiveFeed: Boolean): String =
    feedSource match {
      case LiveBaseFeedSource                            => CiriumLiveArrivals
      case LiveFeedSource if !hasOperatorLiveFeed        => CiriumLiveArrivals
      case LiveFeedSource                                => "Port live"
      case _                                             => feedSource.displayName
    }

  def description(feedSource: FeedSource, hasOperatorLiveFeed: Boolean): String =
    feedSource match {
      case LiveFeedSource => feedSource.description(!hasOperatorLiveFeed)
      case _              => feedSource.description(hasOperatorLiveFeed)
    }
}
