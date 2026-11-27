package drt.client.components

import drt.shared.FeedSourceArrival
import org.scalajs.dom
import org.scalajs.dom.html
import uk.gov.homeoffice.drt.ports._
import utest._

object ArrivalInfoTests extends TestSuite {
  def tests = Tests {
    test("shouldWrapFocus") {
      test("should return first element when Tab pressed on last element") {
        val container = dom.document.createElement("div").asInstanceOf[html.Div]
        container.className = "test-modal"

        val first = dom.document.createElement("h2").asInstanceOf[html.Heading]
        first.tabIndex = 0
        val middle = dom.document.createElement("button").asInstanceOf[html.Button]
        val last = dom.document.createElement("div").asInstanceOf[html.Div]
        last.tabIndex = 0

        container.appendChild(first)
        container.appendChild(middle)
        container.appendChild(last)

        val result = ArrivalInfo.shouldWrapFocus(container, last, shiftKey = false)

        assert(result.contains(first))
      }

      test("should return last element when Shift+Tab pressed on first element") {
        val container = dom.document.createElement("div").asInstanceOf[html.Div]
        container.className = "test-modal"

        val first = dom.document.createElement("h2").asInstanceOf[html.Heading]
        first.tabIndex = 0
        val middle = dom.document.createElement("button").asInstanceOf[html.Button]
        val last = dom.document.createElement("div").asInstanceOf[html.Div]
        last.tabIndex = 0

        container.appendChild(first)
        container.appendChild(middle)
        container.appendChild(last)

        val result = ArrivalInfo.shouldWrapFocus(container, first, shiftKey = true)

        assert(result.contains(last))
      }

      test("should return None when Tab pressed on middle element") {
        val container = dom.document.createElement("div").asInstanceOf[html.Div]
        container.className = "test-modal"

        val first = dom.document.createElement("h2").asInstanceOf[html.Heading]
        first.tabIndex = 0
        val middle = dom.document.createElement("button").asInstanceOf[html.Button]
        val last = dom.document.createElement("div").asInstanceOf[html.Div]
        last.tabIndex = 0

        container.appendChild(first)
        container.appendChild(middle)
        container.appendChild(last)

        val result = ArrivalInfo.shouldWrapFocus(container, middle, shiftKey = false)

        assert(result.isEmpty)
      }

      test("should return None when Shift+Tab pressed on last element") {
        val container = dom.document.createElement("div").asInstanceOf[html.Div]
        container.className = "test-modal"

        val first = dom.document.createElement("h2").asInstanceOf[html.Heading]
        first.tabIndex = 0
        val middle = dom.document.createElement("button").asInstanceOf[html.Button]
        val last = dom.document.createElement("div").asInstanceOf[html.Div]
        last.tabIndex = 0

        container.appendChild(first)
        container.appendChild(middle)
        container.appendChild(last)

        val result = ArrivalInfo.shouldWrapFocus(container, last, shiftKey = true)

        assert(result.isEmpty)
      }

      test("should return None when modal has no focusable elements") {
        val container = dom.document.createElement("div").asInstanceOf[html.Div]
        container.className = "test-modal"

        val nonFocusable = dom.document.createElement("span").asInstanceOf[html.Span]
        container.appendChild(nonFocusable)

        val result = ArrivalInfo.shouldWrapFocus(container, nonFocusable, shiftKey = false)

        assert(result.isEmpty)
      }

      test("should handle disabled buttons correctly") {
        val container = dom.document.createElement("div").asInstanceOf[html.Div]
        container.className = "test-modal"

        val first = dom.document.createElement("h2").asInstanceOf[html.Heading]
        first.tabIndex = 0
        val disabledButton = dom.document.createElement("button").asInstanceOf[html.Button]
        disabledButton.disabled = true
        val last = dom.document.createElement("div").asInstanceOf[html.Div]
        last.tabIndex = 0

        container.appendChild(first)
        container.appendChild(disabledButton)
        container.appendChild(last)

        val result = ArrivalInfo.shouldWrapFocus(container, last, shiftKey = false)

        assert(result.contains(first))
      }

      test("should work with dynamically added focusable elements") {
        val container = dom.document.createElement("div").asInstanceOf[html.Div]
        container.className = "test-modal"

        val first = dom.document.createElement("h2").asInstanceOf[html.Heading]
        first.tabIndex = 0
        val last = dom.document.createElement("div").asInstanceOf[html.Div]
        last.tabIndex = 0

        container.appendChild(first)
        container.appendChild(last)

        val newButton = dom.document.createElement("button").asInstanceOf[html.Button]
        container.appendChild(newButton)

        val result = ArrivalInfo.shouldWrapFocus(container, newButton, shiftKey = false)

        assert(result.contains(first))
      }
    }

    test("rowsFor") {
      val portLive = ArrivalGenerator.arrival(iata = "BA0001", schDt = "2024-06-01T10:00", feedSource = LiveFeedSource)
      val api = ArrivalGenerator.arrival(iata = "BA0001", schDt = "2024-06-01T10:00", feedSource = ApiFeedSource)
      val configured = Seq(LiveFeedSource, ApiFeedSource, LiveBaseFeedSource, AclFeedSource, ForecastFeedSource)

      test("should give a row per configured feed in preference order, with None for feeds that sent no data") {
        val sources = Seq(
          Option(FeedSourceArrival(LiveFeedSource, portLive)),
          None,
          Option(FeedSourceArrival(ApiFeedSource, api))
        )

        val result = ArrivalInfo.rowsFor(sources, configured)

        assert(result == Seq(
          (AclFeedSource, None),
          (ForecastFeedSource, None),
          (LiveBaseFeedSource, None),
          (ApiFeedSource, Option(api)),
          (LiveFeedSource, Option(portLive))
        ))
      }

      test("should omit feeds the port isn't configured for") {
        val result = ArrivalInfo.rowsFor(Seq(Option(FeedSourceArrival(ApiFeedSource, api))), Seq(ApiFeedSource, LiveFeedSource))

        assert(result == Seq((ApiFeedSource, Option(api)), (LiveFeedSource, None)))
      }
    }

    test("FeedSourceRow cell values") {
      test("should show a dash for missing or blank values") {
        assert(FeedSourceRow.orDash(None) == "-")
        assert(FeedSourceRow.orDash(Option(" ")) == "-")
        assert(FeedSourceRow.orDash(Option("4")) == "4")
      }

      test("should show a single dash when there is neither gate nor stand, and dash the missing half otherwise") {
        val noGateOrStand = ArrivalGenerator.arrival(feedSource = LiveFeedSource)
        val blankGateAndStand = ArrivalGenerator.arrival(gate = Option(" "), stand = Option(""), feedSource = LiveFeedSource)
        val standOnly = ArrivalGenerator.arrival(stand = Option("1a"), feedSource = LiveFeedSource)
        val gateOnly = ArrivalGenerator.arrival(gate = Option("A1"), feedSource = LiveFeedSource)
        val gateAndStand = ArrivalGenerator.arrival(gate = Option("A1"), stand = Option("1a"), feedSource = LiveFeedSource)

        assert(FeedSourceRow.gateStand(noGateOrStand) == "-")
        assert(FeedSourceRow.gateStand(blankGateAndStand) == "-")
        assert(FeedSourceRow.gateStand(standOnly) == "- / 1a")
        assert(FeedSourceRow.gateStand(gateOnly) == "A1 / -")
        assert(FeedSourceRow.gateStand(gateAndStand) == "A1 / 1a")
      }
    }
  }
}
