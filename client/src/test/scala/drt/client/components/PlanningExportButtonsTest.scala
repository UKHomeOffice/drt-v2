package drt.client.components

import japgolly.scalajs.react.Callback
import org.scalajs.dom
import utest._

object PlanningExportButtonsTest extends TestSuite {
  def tests: Tests = Tests {
    test("planning export buttons run their own download action") {
      var headlines = 0
      var staff = 0
      Seq(
        ("Export Headlines", Callback(headlines += 1)),
        ("Export Staff Requirements", Callback(staff += 1))
      ).foreach { case (label, action) =>
        withContainer { container =>
          TerminalPlanningComponent.buttonWithProgress(label, isPreparing = false, action).renderIntoDOM(container)
          val button = secondaryButton(container)
          assert(button.textContent == label)
          assert(!button.disabled)
          button.click()
        }
      }
      assert(headlines == 1)
      assert(staff == 1)
    }

    test("preparing exports show progress and cannot be triggered again") {
      var downloads = 0
      withContainer { container =>
        TerminalPlanningComponent.buttonWithProgress("Export Headlines", isPreparing = true, Callback(downloads += 1))
          .renderIntoDOM(container)
        val button = secondaryButton(container)
        assert(button.disabled)
        assert(button.textContent.contains("Preparing... please wait"))
        assert(container.querySelector("[role='progressbar']") != null)
        button.click()
        assert(downloads == 0)
      }
    }
  }

  private def secondaryButton(container: dom.Element): dom.html.Button = {
    val button = container.querySelector("button").asInstanceOf[dom.html.Button]
    assert(button != null)
    assert(button.`type` == "button")
    assert(button.classList.contains("govuk-button--secondary"))
    assert(button.classList.contains("drt-govuk-button--secondary-home-office"))
    button
  }

  private def withContainer(check: dom.Element => Unit): Unit = {
    val container = dom.document.createElement("div")
    dom.document.body.appendChild(container)
    try check(container)
    finally dom.document.body.removeChild(container)
  }
}
