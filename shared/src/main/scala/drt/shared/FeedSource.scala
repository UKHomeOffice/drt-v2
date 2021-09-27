package drt.shared

import ujson.Value.Value
import upickle.default.readwriter
import scala.concurrent.duration._
import upickle.default._
import scala.concurrent.duration.FiniteDuration

trait FeedSource {
  val name: String

  val maybeLastUpdateThreshold: Option[FiniteDuration]

  val description: Boolean => String

  override val toString: String = getClass.getSimpleName.split("\\$").last
}


case object ApiFeedSource extends FeedSource {
  val name: String = "API"

  val maybeLastUpdateThreshold: Option[FiniteDuration] = None

  val description: Boolean => String = isLiveFeedAvailable => if (isLiveFeedAvailable)
    "Actual passenger nationality and age data when available."
  else
    "Actual passenger numbers and nationality data when available."
}

case object AclFeedSource extends FeedSource {
  val name: String = "ACL"

  val maybeLastUpdateThreshold: Option[FiniteDuration] = Option(36 hours)

  val description: Boolean => String = _ => "Flight schedule for up to 6 months."
}

case object ForecastFeedSource extends FeedSource {
  val name: String = "Port forecast"

  val maybeLastUpdateThreshold: Option[FiniteDuration] = None

  val description: Boolean => String = _ => "Updated forecast of passenger numbers."
}

case object LiveFeedSource extends FeedSource {
  val name: String = "Port live"

  val maybeLastUpdateThreshold: Option[FiniteDuration] = Option(12 hours)

  val description: Boolean => String = _ => "Up-to-date passenger numbers, estimated and actual arrival times, gates and stands."
}

case object ScenarioSimulationSource extends FeedSource {
  val name: String = "Scenario Simulation"

  val maybeLastUpdateThreshold: Option[FiniteDuration] = Option(12 hours)

  val description: Boolean => String = _ => "An altered arrival to explore a simulated scenario."
}

case object LiveBaseFeedSource extends FeedSource {
  val name: String = "Cirium live"

  val maybeLastUpdateThreshold: Option[FiniteDuration] = Option(12 hours)

  val description: Boolean => String = isLiveFeedAvailable => if (isLiveFeedAvailable)
    "Estimated and actual arrival time updates where not available from live feed."
  else
    "Estimated and actual arrival time updates."
}

case object UnknownFeedSource extends FeedSource {
  val name: String = "Unknown"

  val maybeLastUpdateThreshold: Option[FiniteDuration] = None

  val description: Boolean => String = _ => ""
}

object FeedSource {
  def feedSources: Set[FeedSource] = Set(ApiFeedSource, AclFeedSource, ForecastFeedSource, LiveFeedSource, LiveBaseFeedSource)

  def apply(feedSource: String): Option[FeedSource] = feedSources.find(fs => fs.toString == feedSource)

  implicit val feedSourceReadWriter: ReadWriter[FeedSource] =
    readwriter[Value].bimap[FeedSource](
      feedSource => feedSource.toString,
      (s: Value) => apply(s.str).getOrElse(UnknownFeedSource)
    )
}