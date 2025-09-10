package drt.client.components

import diode.AnyAction.aType
import drt.client.SPAMain.{Loc, TerminalPageTabLoc}
import drt.client.modules.GoogleEventTracker
import drt.client.services.JSDateConversions.SDate
import drt.client.services.SPACircuit
import drt.client.services.handlers.SaveShifts
import japgolly.scalajs.react.callback.Callback
import japgolly.scalajs.react.{BackendScope, CtorType, Reusability, ScalaComponent}
import japgolly.scalajs.react.component.Scala.{Component, Unmounted}
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.html_<^.VdomTagOf
import org.scalajs.dom.html.Div
import japgolly.scalajs.react.vdom.html_<^._
import uk.gov.homeoffice.drt.Shift
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.SDateLike
import uk.gov.homeoffice.drt.time.LocalDate

object ShiftsComponent {

  case class Props(terminal: Terminal, portCode: String, router: RouterCtl[Loc])

  implicit val propsReuse: Reusability[Props] = Reusability((a, b) => a == b)

  class Backend(scope: BackendScope[Props, Unit]) {

    import upickle.default.{macroRW, ReadWriter => RW}

    implicit val rw: RW[Shift] = macroRW

    private def startDateInLocalDate: LocalDate = {
      val today: SDateLike = SDate.now()
      LocalDate(today.getFullYear, today.getMonth, 1)
    }

    def render(props: Props): VdomTagOf[Div] = {
      def confirmHandler(shifts: Seq[ShiftForm]): Unit = {
        val staffShifts = shifts.map(s => Shift(
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
        SPACircuit.dispatch(SaveShifts(staffShifts))
        Callback(GoogleEventTracker.sendEvent(props.terminal.toString, action = "Shifts", label = "save")).runNow()
        props.router.set(TerminalPageTabLoc(props.terminal.toString, "Shifts", "60", Map.empty)).runNow()
      }

      <.div(AddShiftsFormComponent(ShiftFormProps(props.portCode, props.terminal.toString, 30, Seq.empty[ShiftForm], confirmHandler, isEdit = false)))
    }

  }

  val component: Component[Props, Unit, Backend, CtorType.Props] = ScalaComponent.builder[Props]("StaffingShiftsV2")
    .renderBackend[Backend]
    .configure(Reusability.shouldComponentUpdate)
    .build

  def apply(terminal: Terminal, portCode: String, router: RouterCtl[Loc]): Unmounted[Props, Unit, Backend] = component(Props(terminal, portCode, router))
}
