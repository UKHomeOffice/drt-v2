package drt.client.components

import diode.UseValueEq
import diode.data.Pot
import diode.react.ModelProxy
import drt.client.SPAMain.{Loc, PortDashboardLoc}
import drt.client.modules.GoogleEventTracker
import drt.client.services.JSDateConversions.SDate
import drt.client.services.SPACircuit
import drt.client.services.handlers.UpdateUserPreferences
import drt.shared._
import io.kinoplan.scalajs.react.material.ui.core.MuiTypography
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Callback, CtorType, ReactEventFromInput, ScalaComponent}
import uk.gov.homeoffice.drt.models.UserPreferences
import uk.gov.homeoffice.drt.ports.Queues.{EGate, QueueDesk}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.Terminals.Terminal.numberString
import uk.gov.homeoffice.drt.ports.{AirportConfig, FeedSource, Queues, Terminals}
import uk.gov.homeoffice.drt.time.SDateLike

import scala.collection.mutable.LinkedHashMap
import scala.util.Try

object PortDashboardPage {

  case class Props(router: RouterCtl[Loc], dashboardPage: PortDashboardLoc) extends UseValueEq

  case class DisplayPeriod(start: SDateLike, end: SDateLike) {
    def duration: Int = ((end.millisSinceEpoch - start.millisSinceEpoch) / 1000).toInt

    def displayPeriodString = s"${start.prettyTime} to ${end.prettyTime}"
  }

  private object DisplayPeriod {
    def apply(start: SDateLike, minutes: Int): DisplayPeriod = DisplayPeriod(start, start.addMinutes(minutes))
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

      val rangeOptions = LinkedHashMap(
        15 -> "15 minutes",
        30 -> "30 minutes",
        60 -> "1 hour",
        120 -> "2 hours",
        180 -> "3 hours")

      val modelRCP = SPACircuit.connect(rm => PortDashboardModel(rm.airportConfig, rm.portStatePot, rm.featureFlags, rm.paxFeedSourceOrder, rm.userPreferences))

      modelRCP { modelMP: ModelProxy[PortDashboardModel] =>
        val portDashboardModel: PortDashboardModel = modelMP()
        <.div(^.className := "terminal-summary-dashboard",
          MuiTypography(variant = "h1")(s"Dashboard ${p.dashboardPage.portCodeStr} (${p.dashboardPage.portConfig.portName})"),
          portDashboardModel.airportConfig.renderReady(portConfig => {
            val portName = portConfig.portCode.iata.toLowerCase
            portDashboardModel.userPreferences.renderReady(userPreferences => {
              val selectedPeriodLengthMinutes = Try(userPreferences.portDashboardIntervalMinutes.getOrElse(portName, 60)).getOrElse(60)

              val userHasTerminalPreference: Boolean = userPreferences.portDashboardTerminals.get(portName).exists(_.nonEmpty)

              val (queues, paxTypeAndQueueOrder, terminals) = (portConfig.queuesByTerminal, portConfig.terminalPaxSplits, portConfig.terminals)

              val selectedTerminals: List[String] = if (!userHasTerminalPreference) {
                terminals.map(t => s"${t.toString}").toList
              } else {
                userPreferences.portDashboardTerminals.getOrElse(portName, Set.empty[String]).toList
              }

              val currentPeriodStart = DashboardTerminalSummary.windowStart(SDate.now(), selectedPeriodLengthMinutes)
              val periods = Map(1 -> DisplayPeriod(currentPeriodStart, selectedPeriodLengthMinutes),
                2 -> DisplayPeriod(currentPeriodStart.addMinutes(selectedPeriodLengthMinutes), selectedPeriodLengthMinutes),
                3 -> DisplayPeriod(currentPeriodStart.addMinutes(2 * selectedPeriodLengthMinutes), selectedPeriodLengthMinutes),
              )

              def displayPeriod = periods(p.dashboardPage.period.getOrElse(1))

              def switchDashboardPeriod(event: ReactEventFromInput) = {
                val period = event.target.value.toInt
                GoogleEventTracker.sendEvent("dashboard", "Switch Period", period.toString)
                p.router.set(p.dashboardPage.copy(period = Option(period)))
              }

              def handleTimeRangeChange(event: ReactEventFromInput): Callback = {
                val newRange = event.target.value.toInt
                GoogleEventTracker.sendEvent("dashboard", "Time Range", newRange.toString)
                Callback(
                  SPACircuit.dispatch(
                    UpdateUserPreferences(
                      userPreferences.copy(portDashboardIntervalMinutes = userPreferences.portDashboardIntervalMinutes + (portName -> newRange))))).runNow()
                p.router.set(p.dashboardPage)
              }

              def handleTerminalChange(event: ReactEventFromInput): Callback = {
                val terminal = Terminals.Terminal(event.target.value)
                val isChecked = event.target.checked

                val preferenceTerminals: Set[String] = Try(
                  userPreferences.portDashboardTerminals.getOrElse(portName, Set.empty[String])).getOrElse(Set.empty[String])
                val updatedQueryParams: Set[String] = if (!userHasTerminalPreference)
                  selectedTerminals.filterNot(_ == terminal.toString).toSet
                else {
                  if (isChecked)
                    preferenceTerminals + terminal.toString
                  else
                    preferenceTerminals.filterNot(_ == terminal.toString)
                }

                GoogleEventTracker.sendEvent("dashboard", "Terminals", updatedQueryParams.mkString(","))
                Callback(SPACircuit.dispatch(
                  UpdateUserPreferences(
                    userPreferences.copy(portDashboardTerminals = userPreferences.portDashboardTerminals + (portName -> updatedQueryParams))))).runNow()
                p.router.set(p.dashboardPage)
              }

              <.div(<.h3(s"Filter upcoming arrivals"),
                <.div(^.className := "port-dashboard-period",
                  <.div(^.className := "port-dashboard-title",
                    <.div(
                      <.label(^.htmlFor := "period-select", <.strong("Time:")),
                      <.div(^.className := "port-dashboard-select",
                        <.select(
                          ^.className := "form-control dynamic-width",
                          ^.value := selectedPeriodLengthMinutes,
                          ^.onChange ==> handleTimeRangeChange,
                        )(
                          rangeOptions.map { case (range, display) =>
                            <.option(^.value := range, display)
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
                      <.div(^.className := "port-dashboard-terminal",
                        terminals.map { terminal =>
                          <.label(^.className := "terminal-checkbox-label",
                            <.input(
                              ^.`type` := "checkbox",
                              ^.name := "terminal",
                              ^.value := terminal.toString,
                              ^.checked := selectedTerminals.contains(s"${terminal.toString}"),
                              ^.onChange ==> handleTerminalChange
                            ),
                            s"Terminal ${numberString(terminal)}"
                          )
                        }.toTagMod
                      )
                    ))),
                <.div(
                  <.h3(s"Arrivals"),
                  <.div(^.className := "port-dashboard-selection",
                    <.span(<.strong("Filters applied:")),
                    <.span(s"Time period : ${selectedPeriodLengthMinutes} minutes period (${displayPeriod.start.prettyTime} to ${displayPeriod.end.prettyTime})"),
                    <.span(^.className := "selection-separator"),
                    <.span(s"Terminals: ${selectedTerminals.filter(_.nonEmpty).sortBy(_.toString).mkString(", ")}"),
                  )
                ),

                terminals.filter(t => selectedTerminals.map(Terminal(_)).contains(t)).map { terminalName =>
                  val terminal: Terminal = terminalName
                  <.div(
                    <.h3(
                      <.a(
                        ^.href := s"/#terminal/${terminal.toString}/current/arrivals/",
                        ^.className := "terminal-link",
                        s"Terminal ${Terminal.numberString(terminal)}"
                      )
                    ),
                    portDashboardModel.portState.renderReady(portState => {
                      portDashboardModel.featureFlags.renderReady(_ => {
                        val portStateForDashboard = portState.windowWithTerminalFilter(
                          displayPeriod.start,
                          displayPeriod.start.addMinutes(selectedPeriodLengthMinutes * 3),
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
                              displayPeriod.start.addMinutes(selectedPeriodLengthMinutes * 3),
                              portDashboardModel.paxFeedSourceOrder,
                              selectedPeriodLengthMinutes,
                              portConfig.queuesByTerminal.get(terminalName).exists(_.contains(QueueDesk))
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
