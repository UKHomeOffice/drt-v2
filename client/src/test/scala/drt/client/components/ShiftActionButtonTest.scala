package drt.client.components

import japgolly.scalajs.react.callback.Callback
import org.scalajs.dom
import drt.client.services.JSDateConversions.SDate
import utest._

object ShiftActionButtonTest extends TestSuite {
  def tests: Tests = Tests {
    test("create shift pattern action") {
      checkButton("Create shift pattern")
    }
    test("add shift action") {
      checkButton("Add shift")
    }
    test("navigation buttons select the previous and next dates") {
      val viewingDate = SDate("2026-07-14T12:00:00Z")
      Seq("daily", "weekly", "monthly").foreach { period =>
        val (previous, next) = StaffingUtil.navigationDates(viewingDate, period, () => viewingDate)
        var dates = Vector.empty[String]
        val container = dom.document.createElement("div")
        dom.document.body.appendChild(container)
        try {
          MonthlyStaffingBar.navigationArrows(
            previous,
            next,
            date => Callback { dates :+= date.toISODateOnly }
          ).renderIntoDOM(container)
          val buttons = container.querySelectorAll("button")
          assert(buttons.length == 2)
          Seq("Previous period", "Next period").indices.foreach { index =>
            val button = buttons(index).asInstanceOf[dom.html.Button]
            assert(button.getAttribute("aria-label") == Seq("Previous period", "Next period")(index))
            assert(button.`type` == "button")
            assert(button.classList.contains("govuk-button--secondary"))
            assert(button.classList.contains("drt-govuk-button--secondary-home-office"))
            assert(button.querySelector("svg") != null)
            button.click()
          }
          assert(dates == Vector(previous.toISODateOnly, next.toISODateOnly))
        } finally {
          dom.document.body.removeChild(container)
        }
      }
    }
  }

  private def checkButton(label: String): Unit = {
    var clicks = 0
    val container = dom.document.createElement("div")
    dom.document.body.appendChild(container)
    try {
      MonthlyStaffingBar.shiftActionButton(label, Callback { clicks += 1 }).renderIntoDOM(container)
      val button = container.querySelector("button").asInstanceOf[dom.html.Button]
      assert(button != null)
      assert(button.textContent == label)
      assert(button.`type` == "button")
      assert(button.classList.contains("govuk-button--secondary"))
      assert(button.classList.contains("drt-govuk-button--secondary-home-office"))
      assert(button.classList.contains("staffing-action-button"))
      assert(clicks == 0)
      button.click()
      assert(clicks == 1)
    } finally {
      dom.document.body.removeChild(container)
    }
  }
}
