package drt.client.components

import diode.AnyAction.aType
import drt.client.SPAMain.{Loc, TerminalPageTabLoc}
import drt.client.services.SPACircuit
import drt.client.services.handlers.SaveShift
import drt.shared.StaffShift
import io.kinoplan.scalajs.react.material.ui.core.MuiButton
import io.kinoplan.scalajs.react.material.ui.core.MuiButton.Color
import japgolly.scalajs.react.{BackendScope, CtorType, Reusability, ScalaComponent}
import japgolly.scalajs.react.component.Scala.{Component, Unmounted}
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.html_<^.VdomTagOf
import org.scalajs.dom.html.Div
import uk.gov.homeoffice.drt.ports.AirportConfig
import japgolly.scalajs.react.vdom.html_<^._

import scala.util.Try

object StaffingShifts {

  case class State(confirmSummary: Boolean = false)

  case class Props(terminalPageTab: TerminalPageTabLoc,
                   router: RouterCtl[Loc],
                   airportConfig: AirportConfig,
                   enableStaffPlanningChanges: Boolean
                  ) {
    def timeSlotMinutes: Int = Try(terminalPageTab.subMode.toInt).toOption.getOrElse(60)

    def dayRangeType: String = terminalPageTab.dayRangeType match {
      case Some(dayRange) => dayRange
      case None => "monthly"
    }
  }


  implicit val propsReuse: Reusability[Props] = Reusability((a, b) => a == b)
  implicit val stateReuse: Reusability[State] = Reusability((a, b) => a == b)

  class Backend(scope: BackendScope[Props, State]) {

    import upickle.default._
    import upickle.default.{macroRW, ReadWriter => RW}

    implicit val rw: RW[StaffShift] = macroRW

    def render(props: Props, state: State): VdomTagOf[Div] = {
      def confirmHandler(shifts: Seq[Shift]): Unit = {
        val staffShifts = shifts.map(s => StaffShift(props.airportConfig.portCode.toString, props.terminalPageTab.terminal.toString, s.name, s.startTime, s.endTime, s.defaultStaffNumber))
        SPACircuit.dispatch(SaveShift(staffShifts))
        scope.modState(state => state.copy(confirmSummary = true)).runNow()
      }

      <.div(
        if (state.confirmSummary) {
          <.div(
            <.h2("Shifts saved"),
            MuiButton(color = Color.primary, variant = "outlined", size = "medium")(^.onClick --> scope.modState(_.copy(confirmSummary = false)), "Add more shifts")
          )
        } else {
          <.div(
            <.h2("Add Shifts"),
            AddShiftFormComponent(ShiftsProps(props.airportConfig.portCode.toString, props.terminalPageTab.terminal.toString, 30, Seq.empty[Shift], confirmHandler))
          )
        }
      )
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

  def apply(terminalPageTab: TerminalPageTabLoc,
            router: RouterCtl[Loc],
            airportConfig: AirportConfig,
            enableStaffPlanningChange: Boolean
           ): Unmounted[Props, State, Backend] = component(Props(terminalPageTab, router, airportConfig, enableStaffPlanningChange))
}
