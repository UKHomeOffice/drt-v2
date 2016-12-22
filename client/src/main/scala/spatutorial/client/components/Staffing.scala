package spatutorial.client.components

import diode.react.ModelProxy
import japgolly.scalajs.react.vdom.prefix_<^._
import japgolly.scalajs.react.{BackendScope, ReactComponentB, ReactElement}
import spatutorial.client.logger._
import spatutorial.client.services.JSDateConversions._
import spatutorial.client.services.{SPACircuit, ShiftService, Shifts, StaffMovements}
import spatutorial.shared.WorkloadsHelpers

object Staffing {

  case class Props()

  class Backend($: BackendScope[Props, Unit]) {
    def render(props: Props) = {
      val shiftsRawRCP = SPACircuit.connect(_.shiftsRaw)
      shiftsRawRCP((shiftsMP: ModelProxy[String]) => {
        val rawShifts = shiftsMP()
        log.info(s"shiftsRaw: $rawShifts")
        val shifts = Shifts(rawShifts).parsedShifts.toList
        val ss = ShiftService(shifts)
        val startOfDay: Long = SDate(2016, 12, 1, 0, 0)
        val timeMinPlusOneDay: Long = startOfDay + WorkloadsHelpers.oneMinute * 60 * 24
        val daysWorthOf15Minutes = startOfDay until timeMinPlusOneDay by (WorkloadsHelpers.oneMinute * 15)
        <.div(
          <.h1("Staffing"),
          <.h2("Shifts"),
          <.div(shifts.map(s => {
            <.div(s"${s.name}, ${s.startDt}, ${s.endDt}, ${s.numberOfStaff}")
          }).toSeq),
          <.h2("Staff over the day"),
          <.div(daysWorthOf15Minutes.map(t => <.div(s"time: $t -> ${StaffMovements.staffAt(ss)(Nil)(t)}")))
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
