package drt.client.components

import diode.AnyAction.aType
import drt.client.SPAMain.{Loc, TerminalPageTabLoc}
import drt.client.services.JSDateConversions.SDate
import drt.client.services.SPACircuit
import drt.client.services.handlers.SaveShift
import drt.shared.StaffShift
import japgolly.scalajs.react.{BackendScope, CtorType, Reusability, ScalaComponent}
import japgolly.scalajs.react.component.Scala.{Component, Unmounted}
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.html_<^.VdomTagOf
import org.scalajs.dom.html.Div
import japgolly.scalajs.react.vdom.html_<^._
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.SDateLike

object StaffingShifts {

  case class State()

  case class Props(terminal: Terminal, portCode: String, router: RouterCtl[Loc])

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
        props.router.set(TerminalPageTabLoc(props.terminal.toString, "shifts", "60", Map.empty)).runNow()
      }

      <.div(AddShiftFormComponent(ShiftsProps(props.portCode, props.terminal.toString, 30, Seq.empty[Shift], confirmHandler)))
    }

  }

  private def stateFromProps(props: Props): State = {
    State()
  }

  val component: Component[Props, State, Backend, CtorType.Props] = ScalaComponent.builder[Props]("StaffingShiftsV2")
    .initialStateFromProps(stateFromProps)
    .renderBackend[Backend]
    .configure(Reusability.shouldComponentUpdate)
    .build

  def apply(terminal: Terminal, portCode: String, router: RouterCtl[Loc]): Unmounted[Props, State, Backend] = component(Props(terminal, portCode,router))
}
