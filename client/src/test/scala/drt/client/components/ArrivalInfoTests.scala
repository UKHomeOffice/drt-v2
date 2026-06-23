package drt.client.components

import org.scalajs.dom
import org.scalajs.dom.html
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
  }
}
