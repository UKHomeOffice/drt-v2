package drt.client.components

import diode.UseValueEq
import diode.data.Pot
import diode.react.ModelProxy
import drt.client.SPAMain.{Loc, PortDashboardLoc}
import drt.client.modules.GoogleEventTracker
import drt.client.services.JSDateConversions.SDate
import drt.client.services.SPACircuit
import drt.shared._
import io.kinoplan.scalajs.react.material.ui.core.{MuiButton, MuiTypography}
import io.kinoplan.scalajs.react.material.ui.core.MuiButton._
import io.kinoplan.scalajs.react.material.ui.lab.MuiToggleButtonGroup
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Callback, CtorType, ReactEventFromInput, ScalaComponent}
import org.scalajs.dom.document
import uk.gov.homeoffice.drt.ports.{AirportConfig, FeedSource, Queues}
import uk.gov.homeoffice.drt.time.SDateLike

object PortDashboardPage {

  case class Props(router: RouterCtl[Loc], dashboardPage: PortDashboardLoc, airportConfig: Pot[AirportConfig]) extends UseValueEq

  case class DisplayPeriod(start: SDateLike, end: SDateLike)

  private object DisplayPeriod {
    def apply(start: SDateLike, hours: Int = 3): DisplayPeriod = DisplayPeriod(start, start.addHours(hours))
  }

  private case class PortDashboardModel(airportConfig: Pot[AirportConfig],
                                        portState: Pot[PortState],
                                        featureFlags: Pot[FeatureFlags],
                                        paxFeedSourceOrder: List[FeedSource],
                                       )

  val component: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("PortDashboard")
    .render_P(p => {

      val modelRCP = SPACircuit.connect(rm => PortDashboardModel(rm.airportConfig, rm.portStatePot, rm.featureFlags, rm.paxFeedSourceOrder))

      modelRCP { modelMP: ModelProxy[PortDashboardModel] =>
        val portDashboardModel: PortDashboardModel = modelMP()
        <.div(^.className := "terminal-summary-dashboard",
          MuiTypography(variant = "h1")(s"Dashboard ${p.dashboardPage.portCodeStr} (${p.dashboardPage.portConfig.portName})"),
          portDashboardModel.airportConfig.renderReady(portConfig => {

            val (queues, paxTypeAndQueueOrder, terminals) = (portConfig.queuesByTerminal, portConfig.terminalPaxSplits, portConfig.terminals)

            val currentPeriodStart = DashboardTerminalSummary.windowStart(SDate.now())
            val periods = List(
              DisplayPeriod(currentPeriodStart),
              DisplayPeriod(currentPeriodStart.addHours(3)),
              DisplayPeriod(currentPeriodStart.addHours(6))
            )

            def displayPeriod = periods(p.dashboardPage.period.getOrElse(0))

            def switchDashboardPeriod(period: Int) = (_: ReactEventFromInput) => {
              GoogleEventTracker.sendEvent("dashboard", "Switch Period", period.toString)
              p.router.set(p.dashboardPage.copy(period = Option(period)))
            }

            <.div(
              <.div(
                MuiToggleButtonGroup(selected = true)(^.className := "btn-group no-gutters",
                  periods.zipWithIndex.map {
                    case (p, index) =>
                      MuiButton(color = Color.primary, variant = "outlined", size = "medium")(
                        s"${p.start.prettyTime}-${p.end.prettyTime}",
                        ^.className := s"btn btn-primary${if (p == displayPeriod) " active" else ""} muiFontSize",
                        ^.target := "_blank",
                        ^.onClick ==> switchDashboardPeriod(index))
                  }.toTagMod)),
              terminals.map { terminalName =>
                <.div(
                  <.h3(s"Terminal $terminalName"),
                  portDashboardModel.portState.render(portState => {
                    portDashboardModel.featureFlags.render(_ => {
                      val portStateForDashboard = portState.windowWithTerminalFilter(
                        displayPeriod.start,
                        displayPeriod.end,
                        portConfig.queuesByTerminal.view.filterKeys(_ == terminalName).toMap,
                        portDashboardModel.paxFeedSourceOrder,
                      )
                      val scheduledFlightsInTerminal = portStateForDashboard
                        .flights
                        .values
                        .filterNot(_.apiFlight.isCancelled)
                        .toList
                      val terminalCrunchMinutes = portStateForDashboard.crunchMinutes.values.toList
                      val terminalStaffMinutes = portStateForDashboard.staffMinutes.values.toList
                      val terminalQueuesInOrder = Queues.inOrder(queues.getOrElse(terminalName, Seq()))

                      portDashboardModel.featureFlags.renderReady { _ =>
                        DashboardTerminalSummary(
                          DashboardTerminalSummary.Props(
                            scheduledFlightsInTerminal,
                            terminalCrunchMinutes,
                            terminalStaffMinutes,
                            terminalName,
                            paxTypeAndQueueOrder(terminalName).splits.map(_.paxType),
                            terminalQueuesInOrder,
                            displayPeriod.start,
                            displayPeriod.end,
                            portDashboardModel.paxFeedSourceOrder,
                          )
                        )
                      }
                    })
                  })
                )
              }.toTagMod
            )

          }))
      }
    })
    .build

  def apply(router: RouterCtl[Loc], dashboardPage: PortDashboardLoc = PortDashboardLoc(None), airportConfig: Pot[AirportConfig]): VdomElement = component(Props(router, dashboardPage, airportConfig))
}
