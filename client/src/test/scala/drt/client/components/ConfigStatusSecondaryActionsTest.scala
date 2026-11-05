package drt.client.components

import drt.client.components.ConfirmDialog.ConfirmParams
import japgolly.scalajs.react.Callback
import japgolly.scalajs.react.vdom.html_<^._
import org.scalajs.dom
import uk.gov.homeoffice.drt.egates.{ EgateBank, EgateBanksUpdate, EgateBanksUpdates }
import uk.gov.homeoffice.drt.ports.AclFeedSource
import uk.gov.homeoffice.drt.ports.Queues.EeaDesk
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.ports.config.slas.SlaConfigs
import utest._

import scala.collection.immutable.SortedMap
import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

object ConfigStatusSecondaryActionsTest extends TestSuite {
  @js.native
  @JSImport("react-dom", scala.scalajs.js.annotation.JSImport.Namespace)
  private object ReactDom extends js.Object {
    def unmountComponentAtNode(container: dom.Element): Boolean = js.native
  }

  @js.native
  @JSImport("react-dom/test-utils", scala.scalajs.js.annotation.JSImport.Namespace)
  private object ReactDomTestUtils extends js.Object {
    val Simulate: js.Dynamic = js.native
  }

  def tests: Tests = Tests {
    test("crunch actions invoke only their own callbacks from the button") {
      var actions = Vector.empty[String]
      val container = dom.document.createElement("div")
      dom.document.body.appendChild(container)
      try {
        <.div(FeedsStatusPage.crunchActions(
          Callback { actions :+= "splits" },
          Callback { actions :+= "arrivals" },
          Callback { actions :+= "historic" },
          Callback { actions :+= "forecast" }
        )).renderIntoDOM(container)

        val buttons = container.querySelectorAll("button")
        val labels = Seq(
          "Refresh splits",
          "Recalculate arrivals",
          "Lookup missing historic splits",
          "Lookup missing forecast pax nos"
        )
        val expected = Seq("splits", "arrivals", "historic", "forecast")
        assert(buttons.length == labels.size)
        labels.indices.foreach { index =>
          val button = buttons(index).asInstanceOf[dom.html.Button]
          assert(button.textContent == labels(index))
          assert(button.classList.contains("govuk-button--secondary"))
          assert(button.classList.contains("drt-govuk-button--secondary-home-office"))
          button.click()
          assert(actions == expected.take(index + 1))
        }
      } finally {
        ReactDom.unmountComponentAtNode(container)
        dom.document.body.removeChild(container)
      }
    }

    test("confirm dialog cancel does not confirm the deletion") {
      var cancelled = 0
      var confirmed = 0
      withDialogContainer { container =>
        ConfirmDialog(ConfirmParams(
          "Delete this change?",
          () => Callback { confirmed += 1 },
          () => Callback { cancelled += 1 }
        )).renderIntoDOM(container)
        val button = dom.document.querySelector(".MuiDialogActions-root .govuk-button--secondary")
          .asInstanceOf[dom.html.Button]
        assert(button != null)
        assert(button.textContent == "Cancel")
        assert(button.classList.contains("drt-govuk-button--secondary-home-office"))
        button.click()
        assert(cancelled == 1)
        assert(confirmed == 0)
        val confirmButton = dom.document.querySelector(".MuiDialogActions-root .MuiButton-root")
          .asInstanceOf[dom.html.Button]
        assert(confirmButton.textContent == "Confirm")
        confirmButton.click()
        assert(cancelled == 1)
        assert(confirmed == 1)
      }
    }

    test("e-gates add bank and cancel retain their editing behavior") {
      withDialogContainer { container =>
        EgatesScheduleEditor(T1, EgateBanksUpdates.empty, IndexedSeq.empty).renderIntoDOM(container)
        val addChange = buttonWithText(container, "Add e-Gates change")
        assert(addChange.classList.contains("drt-govuk-button--secondary-home-office"))
        assert(addChange.classList.contains("config-add-change"))
        assert(addChange.parentElement.classList.contains("config-add-change-cell"))
        addChange.click()
        val addBank = buttonWithText(dom.document.body, "Add bank")
        assert(addBank.classList.contains("drt-govuk-button--secondary-home-office"))
        addBank.click()
        assert(dom.document.body.textContent.contains("Bank 1"))
        val removeBank = dom.document.querySelector("button[aria-label='Remove bank 1']")
          .asInstanceOf[dom.html.Button]
        assert(removeBank != null)
        assertSecondaryIcon(removeBank)
        removeBank.click()
        assert(!dom.document.body.textContent.contains("Bank 1"))
        addBank.click()
        assertSaveBeforeCancel()
        val cancel = buttonWithText(dom.document.body, "Cancel")
        assert(cancel.classList.contains("drt-govuk-button--secondary-home-office"))
        cancel.click()
        buttonWithText(container, "Add e-Gates change").click()
        assert(!dom.document.body.textContent.contains("Bank 1"))
      }
    }

    test("SLA cancel discards the new change") {
      withDialogContainer { container =>
        SlaConfigEditor(SlaConfigs(SortedMap.empty), Map.empty).renderIntoDOM(container)
        val addChange = buttonWithText(container, "Add SLA change")
        assert(addChange.classList.contains("drt-govuk-button--secondary-home-office"))
        assert(addChange.classList.contains("config-add-change"))
        assert(addChange.parentElement.classList.contains("config-add-change-cell"))
        addChange.click()
        assertSaveBeforeCancel()
        val cancel = buttonWithText(dom.document.body, "Cancel")
        assert(cancel.classList.contains("drt-govuk-button--secondary-home-office"))
        cancel.click()
        assert(!dom.document.body.textContent.contains("Date the SLAs take effect"))
      }
    }

    test("ACL refresh icon is labelled and invokes the refresh callback") {
      var refreshes = 0
      val container = dom.document.createElement("div")
      dom.document.body.appendChild(container)
      try {
        FeedsStatusPage.refreshFeedButton(AclFeedSource, Callback { refreshes += 1 }).renderIntoDOM(container)
        val button = container.querySelector("button").asInstanceOf[dom.html.Button]
        assert(button.getAttribute("aria-label") == s"Check ${AclFeedSource.displayName} feed")
        assert(button.classList.contains("drt-govuk-button--secondary-home-office"))
        assert(button.querySelector("svg") != null)
        button.click()
        assert(refreshes == 1)
      } finally {
        ReactDom.unmountComponentAtNode(container)
        dom.document.body.removeChild(container)
      }
    }

    test("e-gates row edit icon opens the selected change") {
      withDialogContainer { container =>
        val updates = EgateBanksUpdates(List(EgateBanksUpdate(0L, IndexedSeq(EgateBank(IndexedSeq(true, false))))))
        EgatesScheduleEditor(T1, updates, IndexedSeq.empty).renderIntoDOM(container)
        val edit = container.querySelector("button[aria-label^='Edit T1 e-Gates change effective ']")
          .asInstanceOf[dom.html.Button]
        assert(edit != null)
        assert(edit.classList.contains("drt-govuk-button--secondary-home-office"))
        assert(edit.querySelector("svg") != null)
        val delete = container.querySelector("button[aria-label^='Delete T1 e-Gates change effective ']")
          .asInstanceOf[dom.html.Button]
        assert(delete != null)
        assertSecondaryIcon(delete)
        delete.click()
        assert(dom.document.body.textContent.contains("Are you sure you want to delete e-Gates change?"))
        buttonWithText(dom.document.body, "Cancel").click()
        container.querySelector("button[aria-label^='Edit T1 e-Gates change effective ']")
          .asInstanceOf[dom.html.Button].click()
        assert(dom.document.body.textContent.contains("Bank 1"))
      }
    }

    test("SLA row edit icon opens the selected change") {
      withDialogContainer { container =>
        val configs = SlaConfigs(SortedMap(0L -> Map[Queue, Int](EeaDesk -> 25)))
        SlaConfigEditor(configs, Map.empty).renderIntoDOM(container)
        val edit = container.querySelector("button[aria-label^='Edit SLA change effective ']")
          .asInstanceOf[dom.html.Button]
        assert(edit != null)
        assert(edit.classList.contains("drt-govuk-button--secondary-home-office"))
        assert(edit.querySelector("svg") != null)
        val delete = container.querySelector("button[aria-label^='Delete SLA change effective ']")
          .asInstanceOf[dom.html.Button]
        assert(delete != null)
        assertSecondaryIcon(delete)
        delete.click()
        assert(dom.document.body.textContent.contains("Are you sure you want to delete this SLA change?"))
        buttonWithText(dom.document.body, "Cancel").click()
        container.querySelector("button[aria-label^='Edit SLA change effective ']")
          .asInstanceOf[dom.html.Button].click()
        assert(dom.document.body.textContent.contains("Edit SLA change"))
        val slaInput = dom.document.querySelector("input[value='25']").asInstanceOf[dom.html.Input]
        assert(slaInput != null)
        val save = buttonWithText(dom.document.body, "Save")
        assert(!save.disabled)
        slaInput.value = "invalid"
        ReactDomTestUtils.Simulate.change(slaInput)
        assert(save.disabled)
        save.click()
        assert(dom.document.body.textContent.contains("Edit SLA change"))
        slaInput.value = "30"
        ReactDomTestUtils.Simulate.change(slaInput)
        assert(!save.disabled)
      }
    }
  }

  private def assertSaveBeforeCancel(): Unit = {
    val buttons = dom.document.querySelectorAll(".config-dialog-actions button")
    assert(buttons.length == 2)
    assert(buttons(0).textContent == "Save")
    assert(buttons(0).classList.contains("govuk-button"))
    assert(!buttons(0).classList.contains("govuk-button--secondary"))
    assert(buttons(1).textContent == "Cancel")
  }

  private def assertSecondaryIcon(button: dom.html.Button): Unit = {
    assert(button.`type` == "button")
    assert(button.classList.contains("govuk-button--secondary"))
    assert(button.classList.contains("drt-govuk-button--secondary-home-office"))
    assert(button.querySelector("svg") != null)
  }

  private def buttonWithText(container: dom.Element, text: String): dom.html.Button = {
    val buttons = container.querySelectorAll("button")
    (0 until buttons.length).map(index => buttons(index).asInstanceOf[dom.html.Button])
      .find(_.textContent.trim == text).getOrElse(throw new java.lang.AssertionError(s"Button not found: $text"))
  }

  private def withDialogContainer(check: dom.Element => Unit): Unit = {
    val container = dom.document.createElement("div")
    if (js.isUndefined(js.Dynamic.global.global.selectDynamic("DocumentFragment")))
      js.Dynamic.global.global.updateDynamic("DocumentFragment")(js.Dynamic.global.window.DocumentFragment)
    dom.document.body.appendChild(container)
    try check(container)
    finally {
      ReactDom.unmountComponentAtNode(container)
      dom.document.body.removeChild(container)
    }
  }
}
