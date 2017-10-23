package drt.client.components

import diode.data.Pot
import drt.client.SPAMain.{Loc, TerminalPageTabLoc}
import drt.client.actions.Actions.{GetForecastWeek, SetViewMode}
import drt.client.services.JSDateConversions.SDate
import drt.client.services._
import drt.shared.CrunchApi.CrunchState
import drt.shared.{AirportConfig, AirportInfo}
import japgolly.scalajs.react.ScalaComponent
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.html_<^.{<, ^, _}

object TerminalDisplayModeComponent {

  case class Props(crunchStatePot: Pot[CrunchState],
                   airportConfig: AirportConfig,
                   terminalPageTab: TerminalPageTabLoc,
                   airportInfoPot: Pot[AirportInfo],
                   timeRangeHours: TimeRangeHours,
                   router: RouterCtl[Loc]
                  )

  case class State(activeTab: String)

  val component = ScalaComponent.builder[Props]("Terminal")
    .initialStateFromProps(p => State(p.terminalPageTab.mode))
    .renderPS((scope, props, state) => {

      val terminalContentProps = TerminalContentComponent.Props(
        props.crunchStatePot,
        props.airportConfig,
        props.terminalPageTab,
        props.airportInfoPot,
        props.timeRangeHours,
        props.router
      )

      val currentClass = if (state.activeTab == "current") "active" else ""
      val snapshotDataClass = if (state.activeTab == "snapshot") "active" else ""
      val planningClass = if (state.activeTab == "planning") "active" else ""

      val currentContentClass = if (state.activeTab == "current") "fade in active" else "fade out"
      val snapshotContentClass = if (state.activeTab == "snapshot") "fade in active" else "fade out"
      val planningContentClass = if (state.activeTab == "planning") "fade in active" else "fade out"

      <.div(
        <.ul(^.className := "nav nav-tabs",
          <.li(^.className := currentClass, <.a(VdomAttr("data-toggle") := "tab", "Current"), ^.onClick --> {
            SPACircuit.dispatch(SetViewMode(ViewLive()))
            props.router.set(props.terminalPageTab.copy(mode = "current", date = None)).runNow()
            scope.modState(_ => State("current"))
          }),
          <.li(^.className := snapshotDataClass,
            <.a(VdomAttr("data-toggle") := "tab", "Snapshot"), ^.onClick --> {
              props.router.set(props.terminalPageTab.copy(mode = "snapshot", date = None)).runNow()
              SPACircuit.dispatch(SetViewMode(ViewPointInTime(SDate.now())))
              scope.modState(_ => State("snapshot"))
            }
          ),
          <.li(^.className := planningClass,
            <.a(VdomAttr("data-toggle") := "tab", "Planning"), ^.onClick --> {
              SPACircuit.dispatch(GetForecastWeek(SDate.now(), props.terminalPageTab.terminal))
              props.router.set(props.terminalPageTab.copy(mode = "planning", date = None)).runNow()
              scope.modState(_ => State("planning"))
            }
          )
        ),
        <.div(^.className := "tab-content",
          <.div(^.id := "current", ^.className := s"tab-pane $currentContentClass", {
            if (state.activeTab == "current") <.div(
              DatePickerComponent(DatePickerComponent.Props(props.router, props.terminalPageTab)),
              TerminalContentComponent(terminalContentProps)
            ) else ""
          }),
          <.div(^.id := "snapshot", ^.className := s"tab-pane $snapshotContentClass", {
            if (state.activeTab == "snapshot") <.div(
              SnapshotSelector(props.router, props.terminalPageTab),
              TerminalContentComponent(terminalContentProps)
            ) else ""
          }),
          <.div(^.id := "snapshot", ^.className := s"tab-pane $planningContentClass", {
            if (state.activeTab == "planning") {

              val forecastRCP = SPACircuit.connect(_.forecastPeriodPot)
              <.div(forecastRCP(forecastMP => {
                <.div(forecastMP().renderReady(fp => {
                  TerminalForecastComponent(TerminalForecastComponent.Props(fp))
                }))
              }))
            } else ""
          })))
    })
    .build

  def apply(props: Props): VdomElement = {
    component(props)
  }
}


