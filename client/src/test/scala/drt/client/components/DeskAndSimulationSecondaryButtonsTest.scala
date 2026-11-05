package drt.client.components

import drt.client.components.scenarios.{ ScenarioSimulationFormComponent, SimulationFormFields }
import japgolly.scalajs.react.Callback
import org.scalajs.dom
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.LocalDate
import utest._

object DeskAndSimulationSecondaryButtonsTest extends TestSuite {
  def tests: Tests = Tests {
    test("desk recalculation buttons retain their individual actions") {
      var deskRequests = 0
      var paxRequests = 0
      withContainer { container =>
        TerminalDesksAndQueues.adminRecalcButton(
          () => Callback(deskRequests += 1),
          "Recalc Desk Recs"
        ).renderIntoDOM(container)
        val button = secondaryButton(container, "Recalc Desk Recs")
        button.click()
        assert(deskRequests == 1)
        assert(paxRequests == 0)
      }
      withContainer { container =>
        TerminalDesksAndQueues.adminRecalcButton(
          () => Callback(paxRequests += 1),
          "Recalc Pax Loads"
        ).renderIntoDOM(container)
        secondaryButton(container, "Recalc Pax Loads").click()
        assert(deskRequests == 1)
        assert(paxRequests == 1)
      }
    }

    test("eGate selection buttons retain distinct actions") {
      var selected = 0
      var deselected = 0
      withContainer { container =>
        ScenarioSimulationFormComponent.eGateSelectionButton("Select All", Callback(selected += 1))
          .renderIntoDOM(container)
        secondaryButton(container, "Select All").click()
        assert(selected == 1)
        assert(deselected == 0)
      }
      withContainer { container =>
        ScenarioSimulationFormComponent.eGateSelectionButton("De-select All", Callback(deselected += 1))
          .renderIntoDOM(container)
        secondaryButton(container, "De-select All").click()
        assert(selected == 1)
        assert(deselected == 1)
      }
    }

    test("simulation export retains its link and new-tab behavior") {
      withContainer { container =>
        ScenarioSimulationFormComponent.simulationExportButton("/export/desk-rec-simulation?terminal=T2")
          .renderIntoDOM(container)
        val link = container.querySelector("#export-simulation")
        assert(link != null)
        assert(link.tagName == "A")
        assert(link.getAttribute("href") == "/export/desk-rec-simulation?terminal=T2")
        assert(link.getAttribute("target") == "_blank")
        assert(link.classList.contains("govuk-button--secondary"))
        assert(link.classList.contains("drt-govuk-button--secondary-home-office"))
      }
    }

    test("simulation update only runs for valid fields") {
      val validForm = SimulationFormFields(
        Terminal("T1"), LocalDate(2020, 2, 2), Some(1.0), Map(), Map(), 0, IndexedSeq(), Map(), 0, Seq()
      )
      var updates = 0
      withContainer { container =>
        ScenarioSimulationFormComponent.submitButton(Callback(updates += 1), validForm).renderIntoDOM(container)
        secondaryButton(container, "Update").click()
        assert(updates == 1)
      }
      withContainer { container =>
        ScenarioSimulationFormComponent.submitButton(Callback(updates += 1), validForm.copy(passengerWeighting = None))
          .renderIntoDOM(container)
        val button = secondaryButton(container, "Update")
        assert(!button.disabled)
        button.click()
        assert(updates == 1)
      }
    }
  }

  private def secondaryButton(container: dom.Element, label: String): dom.html.Button = {
    val button = container.querySelector("button").asInstanceOf[dom.html.Button]
    assert(button != null)
    assert(button.textContent == label)
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
