package drt.client.components

import diode.AnyAction.aType
import drt.client.services.JSDateConversions.SDate
import drt.client.services.SPACircuit
import drt.client.services.handlers.SaveShift
import drt.shared.StaffShift
import io.kinoplan.scalajs.react.material.ui.core.MuiButton
import io.kinoplan.scalajs.react.material.ui.core.MuiButton.Color
import io.kinoplan.scalajs.react.material.ui.core.system.SxProps
import japgolly.scalajs.react.{BackendScope, CtorType, Reusability, ScalaComponent}
import japgolly.scalajs.react.component.Scala.{Component, Unmounted}
import japgolly.scalajs.react.vdom.html_<^.VdomTagOf
import org.scalajs.dom.html.Div
import japgolly.scalajs.react.vdom.html_<^._
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.SDateLike

object StaffingShifts {

  case class State(confirmSummary: Boolean = false, shifts: Seq[Shift])

  case class Props(terminal: Terminal, portCode: String)

  implicit val propsReuse: Reusability[Props] = Reusability((a, b) => a == b)
  implicit val stateReuse: Reusability[State] = Reusability((a, b) => a == b)

  class Backend(scope: BackendScope[Props, State]) {

    import upickle.default.{macroRW, ReadWriter => RW}

    implicit val rw: RW[StaffShift] = macroRW

    private def startDateInLocalDate: uk.gov.homeoffice.drt.time.LocalDate = {
      val today: SDateLike = SDate.now()
      uk.gov.homeoffice.drt.time.LocalDate(today.getFullYear, today.getMonth, 1)
    }

    def render(props: Props, state: State): VdomTagOf[Div] = {
      def confirmHandler(shifts: Seq[Shift]): Unit = {
        val staffShifts = shifts.map(s => StaffShift(
          port = props.portCode,
          terminal = props.terminal.toString,
          shiftName = s.name,
          startDate = startDateInLocalDate,
          startTime = s.startTime,
          endTime = s.endTime,
          endDate = None,
          staffNumber = s.defaultStaffNumber,
          frequency = None,
          createdBy = None,
          createdAt = System.currentTimeMillis()
        ))
        SPACircuit.dispatch(SaveShift(staffShifts))
        scope.modState(state => state.copy(confirmSummary = true, shifts = shifts)).runNow()
      }

      <.div(
        if (state.confirmSummary) {
          <.div(
            <.h2("Shifts saved"),
            ShiftSummaryComponent(InitialShiftsProps(state.shifts.map(s => DefaultShift(s.name, s.defaultStaffNumber, s.startTime, s.endTime)))),
            MuiButton(color = Color.primary, variant = "outlined", size = "medium")
            (^.onClick --> scope.modState(_.copy(confirmSummary = false)), "Add more shifts"),
            MuiButton(color = Color.primary, variant = "outlined", size = "medium", component = "a", sx = SxProps(Map("marginLeft" -> "10px")))
            (^.href := s"#terminal/${props.terminal}/shifts/60/")("View Shifts and Pax")
          )
        } else {
          <.div(
            <.h2("Add Shifts"),
            AddShiftFormComponent(ShiftsProps(props.portCode, props.terminal.toString, 30, Seq.empty[Shift], confirmHandler))
          )
        }
      )
    }

  }

  private def stateFromProps(props: Props): State = {
    State(confirmSummary = false, shifts = Seq.empty)
  }

  val component: Component[Props, State, Backend, CtorType.Props] = ScalaComponent.builder[Props]("StaffingShiftsV2")
    .initialStateFromProps(stateFromProps)
    .renderBackend[Backend]
    .configure(Reusability.shouldComponentUpdate)
    .build

  def apply(terminal: Terminal, portCode: String): Unmounted[Props, State, Backend] = component(Props(terminal, portCode))
}
