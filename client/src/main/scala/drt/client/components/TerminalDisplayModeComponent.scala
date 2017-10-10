package drt.client.components

import diode.data.Pot
import drt.client.actions.Actions.{SetPointInTime, SetPointInTimeToLive}
import drt.client.services.{SPACircuit, TimeRangeHours}
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
                   dayToDisplay: SDateLike)

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
        () => props.dayToDisplay
      )

      val liveDataClass = if (state.activeTab == "liveData") "active" else ""
      val snapshotDataClass = if (state.activeTab == "snapshot") "active" else ""

      <.div(
        <.ul(^.className := "nav nav-tabs",
          <.li(^.className := liveDataClass, <.a(VdomAttr("data-toggle") := "tab", "Current"), ^.onClick --> {
            SPACircuit.dispatch(SetPointInTimeToLive())
            scope.modState(_ => State("liveData"))
          }),
          <.li(^.className := snapshotDataClass,
            <.a(VdomAttr("data-toggle") := "tab", "Snapshot"), ^.onClick --> {
              SPACircuit.dispatch(SetPointInTime(props.dayToDisplay.millisSinceEpoch))
              scope.modState(_ => State("snapshot"))
            }
          )
        ),
        <.div(^.className := "tab-content",
          <.div(^.id := "arrivals", ^.className := "tab-pane fade in active", {
            if (state.activeTab == "liveData") <.div(
              DatePickerComponent(DatePickerComponent.Props(Option(props.dayToDisplay), props.terminalName)),
              TerminalContentComponent(terminalContentProps)
            ) else <.div(
              SnapshotSelector(SnapshotSelector.Props(None, props.terminalName)),
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


