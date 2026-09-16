package drt.client.components

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.homeoffice.drt.ports.{
  AclFeedSource,
  ApiFeedSource,
  ForecastFeedSource,
  LiveBaseFeedSource,
  LiveFeedSource,
  FeedSource
}

class FeedSourceDisplayTest extends AnyWordSpec with Matchers {
  "FeedSourceDisplay.displayName" should {
    "label representative production port configurations by their actual provider" in {
      val portConfigurations: Seq[(String, FeedSource, Boolean, String)] = Seq(
        ("NWI", LiveFeedSource, false, "Live arrivals (Cirium)"),
        ("SEN", LiveFeedSource, false, "Live arrivals (Cirium)"),
        ("HUY", LiveFeedSource, false, "Live arrivals (Cirium)"),
        ("BFS", LiveBaseFeedSource, false, "Live arrivals (Cirium)"),
        ("LHR operator feed", LiveFeedSource, true, "Port live"),
        ("LHR Cirium backup", LiveBaseFeedSource, true, "Live arrivals (Cirium)")
      )

      portConfigurations.foreach { case (port, feedSource, hasOperatorLiveFeed, expected) =>
        withClue(s"$port: ") {
          FeedSourceDisplay.displayName(feedSource, hasOperatorLiveFeed) shouldBe expected
        }
      }
    }

    "preserve the labels of unrelated feeds" in {
      FeedSourceDisplay.displayName(ApiFeedSource, hasOperatorLiveFeed = false) shouldBe "API"
      FeedSourceDisplay.displayName(AclFeedSource, hasOperatorLiveFeed = false) shouldBe "Forecast schedule"
      FeedSourceDisplay.displayName(ForecastFeedSource, hasOperatorLiveFeed = false) shouldBe "Port forecast"
    }
  }

  "FeedSourceDisplay.description" should {
    "describe legacy Cirium as timing data rather than an operator feed" in {
      FeedSourceDisplay.description(LiveFeedSource, hasOperatorLiveFeed = false) shouldBe
        "Estimated and actual arrival time updates where not available from the port operator."
    }

    "describe an operator live feed as including passenger and operational data" in {
      FeedSourceDisplay.description(LiveFeedSource, hasOperatorLiveFeed = true) shouldBe
        "Up-to-date passenger numbers, estimated and actual arrival times, gates and stands."
    }

    "describe Cirium as backup only where an operator live feed exists" in {
      FeedSourceDisplay.description(LiveBaseFeedSource, hasOperatorLiveFeed = true) shouldBe
        "Estimated and actual arrival time updates where not available from live feed."
      FeedSourceDisplay.description(LiveBaseFeedSource, hasOperatorLiveFeed = false) shouldBe
        "Estimated and actual arrival time updates."
    }
  }
}

