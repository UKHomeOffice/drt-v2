package drt.client.components

import diode.data.Pot
import drt.client.actions.Actions.SetViewMode
import drt.client.services.JSDateConversions.SDate
import drt.client.services._
import drt.shared.Crunch.CrunchState
import drt.shared.FlightsApi.TerminalName
import drt.shared.{AirportConfig, AirportInfo, SDateLike}
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Callback, ScalaComponent}

object TerminalDisplayModeComponent {

  case class Props(crunchStatePot: Pot[CrunchState],
                   airportConfig: AirportConfig,
                   terminalName: TerminalName,
                   airportInfoPot: Pot[AirportInfo],
                   timeRangeHours: TimeRangeHours,
                   viewMode: ViewMode)

  case class State(activeTab: String)

  val component = ScalaComponent.builder[Props]("Terminal")
    .initialState(State("liveData"))
    .renderPS((scope, props, state) => {

      val terminalContentProps = TerminalContentComponent.Props(
        props.crunchStatePot,
        props.airportConfig,
        props.terminalName,
        props.airportInfoPot,
        props.timeRangeHours,
        props.viewMode
      )

      val liveDataClass = if (state.activeTab == "liveData") "active" else ""
      val snapshotDataClass = if (state.activeTab == "snapshot") "active" else ""

      <.div(
        <.ul(^.className := "nav nav-tabs",
          <.li(^.className := liveDataClass, <.a(VdomAttr("data-toggle") := "tab", "Current"), ^.onClick --> {
            SPACircuit.dispatch(SetViewMode(ViewLive()))
            scope.modState(_ => State("liveData"))
          }),
          <.li(^.className := snapshotDataClass,
            <.a(VdomAttr("data-toggle") := "tab", "Snapshot"), ^.onClick --> {
              SPACircuit.dispatch(SetViewMode(ViewPointInTime(SDate.now())))
              scope.modState(_ => State("snapshot"))
            }
          )
        ),
        <.div(^.className := "tab-content",
          <.div(^.id := "arrivals", ^.className := "tab-pane fade in active", {
            if (state.activeTab == "liveData") <.div(
              DatePickerComponent(DatePickerComponent.Props(props.viewMode, props.terminalName)),
              TerminalContentComponent(terminalContentProps)
            ) else <.div(
              SnapshotSelector(SnapshotSelector.Props()),
              TerminalContentComponent(terminalContentProps)
            )
          })))
    })
    .componentDidUpdate(p => Callback.log("Updating Terminal Component"))
    .componentDidMount(p => Callback.log("Updating Terminal Component"))
    .build

  def apply(props: Props): VdomElement = {
    component(props)
  }
}


