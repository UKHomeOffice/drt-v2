package drt.client.components

import diode.UseValueEq
import diode.data.Pot
import diode.react.ModelProxy
import drt.client.SPAMain.{Loc, PortDashboardLoc}
import drt.client.components.TerminalDashboardComponent.defaultSlotSize
import drt.client.modules.GoogleEventTracker
import drt.client.services.JSDateConversions.SDate
import drt.client.services.SPACircuit
import drt.client.services.handlers.UpdateUserPreferences
import drt.shared._
import io.kinoplan.scalajs.react.material.ui.core.{MuiButton, MuiTypography}
import io.kinoplan.scalajs.react.material.ui.core.MuiButton._
import io.kinoplan.scalajs.react.material.ui.lab.MuiToggleButtonGroup
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Callback, CtorType, ReactEventFromInput, ScalaComponent}
import uk.gov.homeoffice.drt.models.UserPreferences
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{AirportConfig, FeedSource, Queues, Terminals}
import uk.gov.homeoffice.drt.time.SDateLike

import scala.util.Try

object PortDashboardPage {

  case class Props(router: RouterCtl[Loc], dashboardPage: PortDashboardLoc) extends UseValueEq

  case class DisplayPeriod(start: SDateLike, end: SDateLike) {
    def duration: Int = ((end.millisSinceEpoch - start.millisSinceEpoch) / 1000).toInt

    def displayPeriodString = s"${start.prettyTime} - ${end.prettyTime}"
  }

  private object DisplayPeriod {
    def apply(start: SDateLike, minutes: Int = 180): DisplayPeriod = DisplayPeriod(start, start.addMinutes(minutes))
  }

  private case class PortDashboardModel(airportConfig: Pot[AirportConfig],
                                        portState: Pot[PortState],
                                        featureFlags: Pot[FeatureFlags],
                                        paxFeedSourceOrder: List[FeedSource],
                                        userPreferences: Pot[UserPreferences]
                                       )

  val component: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("PortDashboard")
    .render_P(p => {
      val selectedPeriod = Try {
        p.dashboardPage.period.getOrElse(1)
      }.getOrElse(1)

      val rangeOptions = List(15, 30, 60, 120, 180)

      val modelRCP = SPACircuit.connect(rm => PortDashboardModel(rm.airportConfig, rm.portStatePot, rm.featureFlags, rm.paxFeedSourceOrder, rm.userPreferences))

      modelRCP { modelMP: ModelProxy[PortDashboardModel] =>
        val portDashboardModel: PortDashboardModel = modelMP()
        <.div(^.className := "terminal-summary-dashboard",
          MuiTypography(variant = "h1")(s"Dashboard ${p.dashboardPage.portCodeStr} (${p.dashboardPage.portConfig.portName})"),
          portDashboardModel.airportConfig.renderReady(portConfig => {
            portDashboardModel.userPreferences.renderReady(userPreferences => {

              val selectedTimeRange = userPreferences.portDashboardIntervalMinutes

              val querySelectedTerminal: Set[Terminals.Terminal] = userPreferences.portDashboardTerminals
                .filter(_.nonEmpty)
                .map(Terminals.Terminal.apply)

              val (queues, paxTypeAndQueueOrder, terminals) = (portConfig.queuesByTerminal, portConfig.terminalPaxSplits, portConfig.terminals)

              val selectedTerminals: List[String] = if (querySelectedTerminal.isEmpty) {
                terminals.map(t => s"${t.toString}").toList
              } else {
                terminals.map(t => if (querySelectedTerminal.contains(t)) s"${t.toString}" else s"")
              }.toList

              val currentPeriodStart = DashboardTerminalSummary.windowStart(SDate.now(), selectedTimeRange)
              val periods = Map(1 -> DisplayPeriod(currentPeriodStart, selectedTimeRange),
                2 -> DisplayPeriod(currentPeriodStart.addMinutes(selectedTimeRange), selectedTimeRange),
                3 -> DisplayPeriod(currentPeriodStart.addMinutes(2 * selectedTimeRange), selectedTimeRange),
              )

              def displayPeriod = periods(p.dashboardPage.period.getOrElse(1))

              def switchDashboardPeriod(event: ReactEventFromInput) = {
                val period = event.target.value.toInt
                GoogleEventTracker.sendEvent("dashboard", "Switch Period", period.toString)
                p.router.set(p.dashboardPage.copy(period = Option(period)))
              }

              def handleTimeRangeChange(event: ReactEventFromInput): Callback = {
                val newRange = event.target.value.toInt // Assuming the value is a timestamp
                GoogleEventTracker.sendEvent("dashboard", "Time Range", newRange.toString)
                Callback(SPACircuit.dispatch(UpdateUserPreferences(userPreferences.copy(portDashboardIntervalMinutes = newRange)))).runNow()
                p.router.set(p.dashboardPage.copy(subMode = newRange))
              }

              def handleTerminalChange(event: ReactEventFromInput): Callback = {
                val terminal = Terminals.Terminal(event.target.value)
                val isChecked = event.target.checked

                val updatedQueryParams: Set[String] = if (querySelectedTerminal.isEmpty) {
                  if (isChecked)
                    userPreferences.portDashboardTerminals ++ selectedTerminals
                  else
                    selectedTerminals.filterNot(_ == terminal.toString).toSet
                } else {
                  if (isChecked)
                    userPreferences.portDashboardTerminals + terminal.toString
                  else
                    userPreferences.portDashboardTerminals.filterNot(_ == terminal.toString)
                }

                GoogleEventTracker.sendEvent("dashboard", "Terminals", updatedQueryParams.mkString(","))
                Callback(SPACircuit.dispatch(UpdateUserPreferences(userPreferences.copy(portDashboardTerminals = updatedQueryParams)))).runNow()
                p.router.set(p.dashboardPage)
              }

              <.div(
                <.div(^.className := "port-dashboard-period",
                  <.div(^.className := "port-dashboard-title",
                    <.div(
                      <.label(^.htmlFor := "period-select", <.strong("Select Period:")),
                      <.div(^.className := "port-dashboard-select",
                        <.select(
                          ^.className := "form-control dynamic-width",
                          ^.value := selectedTimeRange,
                          ^.onChange ==> handleTimeRangeChange,
                        )(
                          rangeOptions.map { range =>
                            <.option(^.value := range, s"$range minutes")
                          }.toTagMod
                        ),
                        <.select(
                          ^.className := "form-control dynamic-width",
                          ^.value := selectedPeriod.toString,
                          ^.onChange ==> switchDashboardPeriod
                        )(
                          periods.map { case (k, v) =>
                            <.option(^.value := k, v.displayPeriodString)
                          }.toTagMod
                        )
                      )),
                    <.span(^.className := "separator"),
                    <.div(
                      <.label(^.htmlFor := "time-range-select", <.strong("Terminals:")),
                      <.div(^.className := "port-dashboard-select",
                        terminals.map { terminal =>
                          <.label(^.className := "terminal-checkbox-label",
                            <.input(
                              ^.`type` := "checkbox",
                              ^.name := "terminal",
                              ^.value := terminal.toString,
                              ^.checked := selectedTerminals.contains(s"${terminal.toString}"),
                              ^.onChange ==> handleTerminalChange
                            ),
                            s"Terminal $terminal"
                          )
                        }.toTagMod
                      )
                    ))),
                <.div(
                  <.h3(s"Arrivals"),
                  <.div(^.className := "port-dashboard-selection",
                    <.span(<.strong("Filters applied:")),
                    <.span(s"Time period : ${selectedTimeRange} minutes period (${displayPeriod.start.prettyTime} - ${displayPeriod.end.prettyTime})"),
                    <.span(^.className := "selection-separator"),
                    <.span(s"Terminals selected: ${selectedTerminals.filter(_.nonEmpty).mkString(", ")}"),
                  )
                ),

                terminals.filter(t => selectedTerminals.map(Terminal(_)).contains(t)).map { terminalName =>
                  val terminal = terminalName
                  <.div(
                    <.h3(s"Terminal $terminal"),
                    portDashboardModel.portState.renderReady(portState => {
                      portDashboardModel.featureFlags.renderReady(_ => {
                        val portStateForDashboard = portState.windowWithTerminalFilter(
                          displayPeriod.start,
                          displayPeriod.end,
                          portConfig.queuesByTerminal.view.filterKeys(_ == terminal).toMap,
                          portDashboardModel.paxFeedSourceOrder,
                        )
                        val scheduledFlightsInTerminal = portStateForDashboard
                          .flights
                          .values
                          .filterNot(_.apiFlight.isCancelled)
                          .toList
                        val terminalCrunchMinutes = portStateForDashboard.crunchMinutes.values.toList
                        val terminalStaffMinutes = portStateForDashboard.staffMinutes.values.toList
                        val terminalQueuesInOrder = Queues.inOrder(queues.getOrElse(terminal, Seq()))
                        portDashboardModel.featureFlags.renderReady { _ =>
                          DashboardTerminalSummary(
                            DashboardTerminalSummary.Props(
                              scheduledFlightsInTerminal,
                              terminalCrunchMinutes,
                              terminalStaffMinutes,
                              terminal,
                              paxTypeAndQueueOrder(terminal).splits.map(_.paxType),
                              terminalQueuesInOrder,
                              displayPeriod.start,
                              displayPeriod.end,
                              portDashboardModel.paxFeedSourceOrder,
                              selectedTimeRange
                            )
                          )
                        }
                      })
                    })
                  )
                }.toTagMod
              )
            })
          }))
      }
    })
    .build

  def apply(router: RouterCtl[Loc], dashboardPage: PortDashboardLoc = PortDashboardLoc(None)): VdomElement = component(Props(router, dashboardPage))
}
