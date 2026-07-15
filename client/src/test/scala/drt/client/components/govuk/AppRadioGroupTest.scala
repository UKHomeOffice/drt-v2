package drt.client.components.govuk

import japgolly.scalajs.react.Callback
import japgolly.scalajs.react.vdom.html_<^._
import org.scalajs.dom
import org.scalajs.dom.html
import utest.{ TestSuite, Tests, test }

object AppRadioGroupTest extends TestSuite {
  def tests: Tests = Tests {
    test("renders expected radios and calls onChange with selected value") {
      val container = dom.document.createElement("div")
      dom.document.body.appendChild(container)

      var selectedValueFromChange: Option[String] = None

      AppRadioGroup(
        AppRadioGroup.Props(
          name = "display-type",
          legend = "View",
          selectedValue = "table",
          items = Seq(
            AppRadioGroup.RadioItem("table", <.span("Table"), id = Option("display-table")),
            AppRadioGroup.RadioItem("charts", <.span("Chart"), id = Option("display-charts"))
          ),
          inline = true,
          small = true,
          onChange = value => Callback {
            selectedValueFromChange = Option(value)
          }
        )
      ).renderIntoDOM(container)

      val legends = container.querySelectorAll("legend")
      assert(legends.length == 1)
      assert(legends(0).textContent.trim == "View")

      val govUkRadios = container.querySelectorAll(".govuk-radios")
      val govUkRadioItems = container.querySelectorAll(".govuk-radios__item")
      val govUkRadiosInline = container.querySelectorAll(".govuk-radios--inline")
      val govUkRadiosSmall = container.querySelectorAll(".govuk-radios--small")
      assert(govUkRadios.length == 1)
      assert(govUkRadioItems.length == 2)
      assert(govUkRadiosInline.length == 1)
      assert(govUkRadiosSmall.length == 1)

      val tableInput = container.querySelector("#display-table").asInstanceOf[html.Input]
      val chartsInput = container.querySelector("#display-charts").asInstanceOf[html.Input]

      assert(tableInput.checked)
      assert(!chartsInput.checked)

      chartsInput.click()

      assert(selectedValueFromChange.contains("charts"))

      dom.document.body.removeChild(container)
    }
  }
}



