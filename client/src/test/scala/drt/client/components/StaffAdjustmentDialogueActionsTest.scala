package drt.client.components

import org.scalajs.dom
import utest._

object StaffAdjustmentDialogueActionsTest extends TestSuite {
  def tests: Tests = Tests {
    test("Save is found by the Cypress hook and only saves the movement") {
      var saves = 0
      var cancels = 0
      withRenderedActions(() => cancels += 1, () => saves += 1) { container =>
        val button = container.querySelector(
          "#staff-adjustment-dialogue [data-cy='staff-adjustment-save']"
        ).asInstanceOf[dom.html.Button]
        assert(button != null)
        assert(button.textContent == "Save")
        assert(button.classList.contains("govuk-button"))
        assert(saves == 0)
        assert(cancels == 0)
        button.click()
        assert(saves == 1)
        assert(cancels == 0)
      }
    }

    test("Cancel has a distinct hook and does not save the movement") {
      var saves = 0
      var cancels = 0
      withRenderedActions(() => cancels += 1, () => saves += 1) { container =>
        val button = container.querySelector(
          "#staff-adjustment-dialogue [data-cy='staff-adjustment-cancel']"
        ).asInstanceOf[dom.html.Button]
        assert(button != null)
        assert(button.textContent == "Cancel")
        assert(button.classList.contains("govuk-button--secondary"))
        button.click()
        assert(saves == 0)
        assert(cancels == 1)
      }
    }
  }

  private def withRenderedActions(cancel: () => Unit, save: () => Unit)(check: dom.Element => Unit): Unit = {
    val container = dom.document.createElement("div")
    container.id = "staff-adjustment-dialogue"
    dom.document.body.appendChild(container)
    try {
      StaffAdjustmentDialogue.saveAndCancel(cancel, save).renderIntoDOM(container)
      check(dom.document.body)
    } finally {
      dom.document.body.removeChild(container)
    }
  }
}

