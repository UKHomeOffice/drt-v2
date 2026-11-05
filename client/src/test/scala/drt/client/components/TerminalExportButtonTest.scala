package drt.client.components

import drt.client.components.govuk.{ Button, ButtonProps }
import drt.client.services.{
  ExportArrivals,
  ExportArrivalsCombinedTerminals,
  ExportArrivalsSingleTerminal,
  ExportDeskRecsCombinedTerminals,
  ExportDeskRecsSingleTerminal,
  ViewLive
}
import drt.client.services.JSDateConversions.SDate
import japgolly.scalajs.react.ReactEvent
import org.scalajs.dom
import uk.gov.homeoffice.drt.ports.Terminals.T2
import utest._

import scala.scalajs.js

object TerminalExportButtonTest extends TestSuite {
  def tests: Tests = Tests {
    test("terminal export dialogs use the same flexible layout for both options") {
      val container = dom.document.createElement("div")
      val portCode = dom.document.createElement("input")
      portCode.id = "port-code"
      portCode.setAttribute("value", "TEST")
      dom.document.body.appendChild(container)
      dom.document.body.appendChild(portCode)
      try {
        val options = Seq(
          "arrivals" -> List(ExportArrivalsSingleTerminal(T2), ExportArrivalsCombinedTerminals),
          "recommendations" -> List(ExportDeskRecsSingleTerminal(T2), ExportDeskRecsCombinedTerminals)
        )
        options.foreach { case (name, exports) =>
          TerminalExportComponent.exportLinks(
            name,
            exports,
            SDate("2026-09-18T00:00:00Z"),
            T2,
            ViewLive
          ).renderIntoDOM(container)
          val links = container.querySelectorAll(".terminal-export-option a.govuk-button--secondary")
          assert(links.length == 2)
          assert(links(0).textContent.trim == "Export T2")
          assert(links(1).textContent.trim == "Export all terminals")
          assert(links(0).getAttribute("target") == "_blank")
          assert(links(1).getAttribute("target") == "_blank")
        }
      } finally {
        dom.document.body.removeChild(container)
        dom.document.body.removeChild(portCode)
      }
    }

    test("modal button forwards its attributes and action") {
      val container = dom.document.createElement("div")
      dom.document.body.appendChild(container)
      var clicks = 0
      try {
        Button(ButtonProps(
          children = "Multi Day Export",
          variant = "secondary",
          dataToggle = "modal",
          dataTarget = "#multi-day-export",
          onClick = ((_: ReactEvent) => clicks += 1): js.Function1[ReactEvent, Unit]
        )).renderIntoDOM(container)
        val button = container.querySelector("button").asInstanceOf[dom.html.Button]
        assert(button.getAttribute("type") == "button")
        assert(button.getAttribute("data-toggle") == "modal")
        assert(button.getAttribute("data-target") == "#multi-day-export")
        assert(button.classList.contains("drt-govuk-button--secondary-home-office"))
        button.click()
        assert(clicks == 1)
      } finally {
        dom.document.body.removeChild(container)
      }
    }

    test("export link retains its destination, new-tab behavior and click analytics") {
      val container = dom.document.createElement("div")
      val gaCode = dom.document.createElement("input")
      val portCode = dom.document.createElement("input")
      gaCode.id = "ga-code"
      gaCode.setAttribute("value", "test")
      portCode.id = "port-code"
      portCode.setAttribute("value", "LHR")
      dom.document.body.appendChild(container)
      dom.document.body.appendChild(gaCode)
      dom.document.body.appendChild(portCode)

      val global = js.Dynamic.global.globalThis
      val previousGtag = global.selectDynamic("gtag")
      var calls = 0
      var action = ""
      val track: js.Function3[String, String, js.Dictionary[String], Unit] = (_, _, fields) => {
        calls += 1
        action = fields("action")
      }
      global.updateDynamic("gtag")(track)

      try {
        TerminalContentComponent.exportLink(
          SDate("2026-09-18T00:00:00Z"),
          "T2",
          ExportArrivals(T2),
          "/exports/arrivals",
          title = "arrivals"
        ).renderIntoDOM(container)

        val link = container.querySelector("#export-day-arrivals").asInstanceOf[dom.html.Anchor]
        assert(link != null)
        assert(link.getAttribute("href") == "/exports/arrivals")
        assert(link.getAttribute("target") == "_blank")
        assert(link.classList.contains("govuk-button--secondary"))
        assert(link.classList.contains("drt-govuk-button--secondary-home-office"))

        link.addEventListener("click", (event: dom.Event) => event.preventDefault())
        val click = dom.document.createEvent("Event")
        click.asInstanceOf[js.Dynamic].initEvent("click", true, true)
        link.querySelector("span").dispatchEvent(click)
        assert(calls == 1)
        assert(action == s"Export ${ExportArrivals(T2).linkLabel}")

        val backgroundClick = dom.document.createEvent("Event")
        backgroundClick.asInstanceOf[js.Dynamic].initEvent("click", true, true)
        container.firstElementChild.dispatchEvent(backgroundClick)
        assert(calls == 1)
      } finally {
        global.updateDynamic("gtag")(previousGtag)
        dom.document.body.removeChild(container)
        dom.document.body.removeChild(gaCode)
        dom.document.body.removeChild(portCode)
      }
    }
  }
}
