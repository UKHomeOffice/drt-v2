package drt.client.components

import drt.client.components.MultiDayExportComponent.{ State, StateDate }
import drt.client.components.govuk.{ DatePicker, DatePickerProps }
import org.scalajs.dom
import uk.gov.homeoffice.drt.time.LocalDate
import utest._

import scala.scalajs.js

object MultiDayExportComponentTest extends TestSuite {
  private val initialState = State(
    startDate = StateDate(LocalDate(2026, 9, 18)),
    endDate = StateDate(LocalDate(2026, 9, 18))
  )

  def tests: Tests = Tests {
    test("updates From and To independently from ISO date values") {
      val withNewStart = initialState.setStart("2026-09-16")
      val withNewEnd = withNewStart.setEnd("2026-09-20")

      assert(withNewStart.startDate.date == LocalDate(2026, 9, 16))
      assert(withNewStart.endDate == initialState.endDate)
      assert(withNewEnd.startDate == withNewStart.startDate)
      assert(withNewEnd.endDate.date == LocalDate(2026, 9, 20))
    }

    test("retains the last committed dates for invalid values") {
      assert(initialState.setStart("") == initialState)
      assert(initialState.setEnd("not-a-date") == initialState)
    }

    test("renders the drt-react datepicker without hint text") {
      val container = dom.document.createElement("div")
      dom.document.body.appendChild(container)
      var selectedDate: Option[String] = None
      val handleChange: js.Function1[String, Unit] = value => selectedDate = Option(value)

      DatePicker(
        DatePickerProps(
          id = "multi-day-export-from",
          name = "multi-day-export-from",
          label = "From",
          hint = "",
          value = "2026-09-18",
          required = true,
          onChange = handleChange
        )
      ).renderIntoDOM(container)

      val input = container.querySelector("#multi-day-export-from")
      val label = container.querySelector("label[for='multi-day-export-from']")

      assert(input != null)
      assert(input.getAttribute("name") == "multi-day-export-from")
      assert(input.getAttribute("value") == "18/09/2026")
      assert(input.hasAttribute("required"))
      assert(label != null)
      assert(label.textContent.trim == "From")
      assert(container.querySelector(".govuk-hint") == null)

      container.querySelector(".moj-datepicker__toggle").asInstanceOf[dom.html.Button].click()
      container.querySelector("[data-date='2026-09-20']").asInstanceOf[dom.html.Button].click()
      assert(selectedDate.contains("2026-09-20"))

      dom.document.body.removeChild(container)
    }
  }
}
