package spatutorial.client.components

import diode.react.ModelProxy
import japgolly.scalajs.react.vdom.prefix_<^._
import japgolly.scalajs.react._
import spatutorial.client.logger._
import spatutorial.client.services.JSDateConversions._
import spatutorial.client.services._
import spatutorial.shared.WorkloadsHelpers
import scala.scalajs.js.Date

object Staffing {

  case class Props()

  class Backend($: BackendScope[Props, Unit]) {
    def render(props: Props) = {
      val shiftsRawRCP = SPACircuit.connect(_.shiftsRaw)
      shiftsRawRCP((shiftsMP: ModelProxy[String]) => {
        val rawShifts = shiftsMP()
        val shifts = Shifts(rawShifts).parsedShifts.toList
        val ss = ShiftService(shifts)
        val today = new Date
        val startOfDay: Long = SDate(today.getFullYear, today.getMonth, today.getDate, 0, 0)
        val timeMinPlusOneDay: Long = startOfDay + WorkloadsHelpers.oneMinute * 60 * 24
        val daysWorthOf15Minutes = startOfDay until timeMinPlusOneDay by (WorkloadsHelpers.oneMinute * 15)
        val shiftExamples = Seq(
          s"Morning shift,${today.getDate}/${today.getMonth + 1}/${today.getFullYear - 2000},00:00,07:59,5",
          s"Day shift,${today.getDate}/${today.getMonth + 1}/${today.getFullYear - 2000},08:00,15:59,20",
          s"Evening shift,${today.getDate}/${today.getMonth + 1}/${today.getFullYear - 2000},16:00,23:59,15"
        )
        <.div(
          <.h1("Staffing"),
          <.h2("Shifts"),
          <.div("One shift per line with values separated by commas, e.g.:"),
          <.div(shiftExamples.map(<.p(_))),
          <.textarea(^.value := rawShifts,
            ^.className := "staffing-editor",
            ^.onChange ==> ((e: ReactEventI) => shiftsMP.dispatch(UpdateShifts(e.target.value)))),
          <.h2("Staff over the day"),
          <.table(
            <.tr(
              daysWorthOf15Minutes.map((t: Long) => {
                val d = new Date(t)
                val display = s"${d.getHours}:${d.getMinutes}"
                <.td(display)
              })
            ),
            <.tr(
              daysWorthOf15Minutes.map(t => <.td(s"${StaffMovements.staffAt(ss)(Nil)(t)}"))
            )
          )
        )
      })
    }
  }

  def apply(): ReactElement =
    component(Props())

  private val component = ReactComponentB[Props]("Staffing")
    .renderBackend[Backend]
    .build
}
