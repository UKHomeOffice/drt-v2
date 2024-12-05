package drt.client.components

import drt.client.SPAMain.{Loc, TerminalPageTabLoc}
import japgolly.scalajs.react.{BackendScope, CtorType, Reusability, ScalaComponent}
import japgolly.scalajs.react.component.Scala.{Component, Unmounted}
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.html_<^.VdomTagOf
import org.scalajs.dom.html.Div
import uk.gov.homeoffice.drt.ports.AirportConfig
import japgolly.scalajs.react.vdom.html_<^._

import scala.util.Try

object StaffingShifts {

  case class State()

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
    def render(props: Props, state: State): VdomTagOf[Div] = {
      <.div(
        AddShiftFormComponent(ShiftsProps(30, Seq.empty[Shift]))
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
