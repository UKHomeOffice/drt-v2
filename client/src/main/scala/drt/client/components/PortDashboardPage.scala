package drt.client.components

import diode.UseValueEq
import diode.data.Pot
import diode.react.ModelProxy
import drt.client.SPAMain.{ Loc, PortDashboardLoc }
import drt.client.components.govuk.{
  CheckboxOption,
  Checkboxes,
  CheckboxesLegendSize,
  CheckboxesProps,
  Select,
  SelectOption,
  SelectProps
}
import drt.client.modules.GoogleEventTracker
import drt.client.services.JSDateConversions.SDate
import drt.client.services.SPACircuit
import drt.client.services.handlers.UpdateUserPreferences
import drt.client.util.AirportName.getAirportByCode
import drt.shared._
import io.kinoplan.scalajs.react.material.ui.core.MuiTypography
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{ CtorType, ScalaComponent }
import uk.gov.homeoffice.drt.models.UserPreferences
import uk.gov.homeoffice.drt.ports.Queues.QueueDesk
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.Terminals.Terminal.numberString
import uk.gov.homeoffice.drt.ports.{ AirportConfig, FeedSource }
import uk.gov.homeoffice.drt.service.QueueConfig
import uk.gov.homeoffice.drt.time.SDateLike

import scala.concurrent.duration.DurationInt
import scala.scalajs.js
import scala.scalajs.js.JSConverters.JSRichIterableOnce
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

  private case class PortDashboardModel(
      airportConfig: Pot[AirportConfig],
      portState: Pot[PortState],
      featureFlags: Pot[FeatureFlags],
      paxFeedSourceOrder: List[FeedSource],
      userPreferences: Pot[UserPreferences]
  )

  val component: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("PortDashboard")
    .render_P { p =>
      val selectedPeriod = Try {
        p.dashboardPage.period.getOrElse(1)
      }.getOrElse(1)

      val rangeOptions = Seq(
        45 -> "45 minutes",
        60 -> "60 minutes",
        90 -> "90 minutes",
        180 -> "3 hours",
        360 -> "6 hours",
        540 -> "9 hours"
      )

      val modelRCP = SPACircuit.connect(rm =>
        PortDashboardModel(
          rm.airportConfig,
          rm.portStatePot,
          rm.featureFlags,
          rm.paxFeedSourceOrder,
          rm.userPreferences
        )
      )

      def noTerminalSelected(userHasTerminalPreference: Option[Set[String]]): Boolean =
        userHasTerminalPreference.contains(Set.empty[String])

      modelRCP { modelMP: ModelProxy[PortDashboardModel] =>
        val portDashboardModel: PortDashboardModel = modelMP()
        <.div(
          ^.className := "terminal-summary-dashboard",
          MuiTypography(variant = "h1")(s"Dashboard: ${p.dashboardPage.portCodeStr} (${
              getAirportByCode(p.dashboardPage.portCodeStr)
                .getOrElse(p.dashboardPage.portConfig.portName)
            })"),
          portDashboardModel.airportConfig.renderReady { portConfig =>
            val portName = portConfig.portCode.iata.toLowerCase
            portDashboardModel.userPreferences.renderReady { userPreferences =>
              val selectedPeriodLengthMinutes =
                Try(userPreferences.portDashboardIntervalMinutes.getOrElse(portName, 180)).getOrElse(180)
              val userHasTerminalPreference: Option[Set[String]] = userPreferences.portDashboardTerminals.get(portName)
              val paxTypeAndQueueOrder = portConfig.terminalPaxSplits
              val terminals = portConfig.terminalsForDate(SDate.now().toLocalDate)

              val selectedTerminals: List[String] = if (userHasTerminalPreference.isEmpty) {
                terminals.map(t => s"${t.toString}").toList
              } else {
                userPreferences.portDashboardTerminals.getOrElse(portName, Set.empty[String]).toList
              }

              val currentPeriodStart = DashboardTerminalSummary.windowStart(SDate.now(), selectedPeriodLengthMinutes)
              val periods = Map(
                1 -> DisplayPeriod(currentPeriodStart, selectedPeriodLengthMinutes),
                2 -> DisplayPeriod(
                  currentPeriodStart.addMinutes(selectedPeriodLengthMinutes),
                  selectedPeriodLengthMinutes
                ),
                3 -> DisplayPeriod(
                  currentPeriodStart.addMinutes(2 * selectedPeriodLengthMinutes),
                  selectedPeriodLengthMinutes
                )
              )

              def displayPeriod: DisplayPeriod = periods(p.dashboardPage.period.getOrElse(1))

              val queuesForDateAndTerminal = QueueConfig.queuesForDateAndTerminal(portConfig.queuesByTerminal)

              def switchDashboardPeriod(value: String): Unit = {
                val period = value.toInt
                GoogleEventTracker.sendEvent("dashboard", "Switch Period", period.toString)
                p.router.set(p.dashboardPage.copy(period = Option(period))).runNow()
              }

              def handleTimeRangeChange(value: String): Unit = {
                val newRange = value.toInt
                GoogleEventTracker.sendEvent("dashboard", "Time Range", newRange.toString)
                SPACircuit.dispatch(
                  UpdateUserPreferences(
                    userPreferences.copy(portDashboardIntervalMinutes =
                      userPreferences.portDashboardIntervalMinutes + (portName -> newRange)
                    )
                  )
                )
                p.router.set(p.dashboardPage).runNow()
              }

              def handleTerminalChange(values: js.Array[String]): Unit = {
                val currentTerminalValues = terminals.map(_.toString).toSet
                val updatedQueryParams =
                  (userHasTerminalPreference.getOrElse(Set.empty) -- currentTerminalValues) ++ values.toSet

                GoogleEventTracker.sendEvent("dashboard", "Terminals", updatedQueryParams.mkString(","))
                SPACircuit.dispatch(
                  UpdateUserPreferences(
                    userPreferences.copy(portDashboardTerminals =
                      userPreferences.portDashboardTerminals + (portName -> updatedQueryParams)
                    )
                  )
                )
                p.router.set(p.dashboardPage).runNow()
              }

              val displayPeriodDisplay = selectedPeriodLengthMinutes % 60 match {
                case 0 => s"${selectedPeriodLengthMinutes.minutes.toHours} hours"
                case _ => s"$selectedPeriodLengthMinutes minutes"
              }

              <.div(
                <.h2(s"Filter upcoming arrivals"),
                <.div(
                  ^.className := "port-dashboard-period",
                  <.div(
                    ^.className := "port-dashboard-title",
                    <.div(
                      <.div(
                        ^.className := "port-dashboard-select",
                        <.div(
                          ^.className := "port-dashboard-select-primary",
                          Select(
                            SelectProps.withVisibleLabel(
                              name = "time-range-select",
                              id = "time-range-select",
                              options = rangeOptions.map { case (range, display) =>
                                SelectOption(range.toString, display)
                              }.toJSArray,
                              label = "Time period:",
                              labelClassName = "govuk-label--m",
                              value = selectedPeriodLengthMinutes.toString,
                              onChange = ((value: String) => handleTimeRangeChange(value)): js.Function1[String, Unit],
                              className = "dynamic-width"
                            )
                          )
                        ),
                        <.div(
                          ^.className := "port-dashboard-select-secondary",
                          Select(
                            SelectProps.withAriaLabel(
                              name = "period-select",
                              id = "period-select",
                              options = periods.toSeq.sortBy(_._1).map { case (period, displayPeriod) =>
                                SelectOption(period.toString, displayPeriod.displayPeriodString)
                              }.toJSArray,
                              ariaLabel = "Choose upcoming arrivals period",
                              value = selectedPeriod.toString,
                              onChange = ((value: String) => switchDashboardPeriod(value)): js.Function1[String, Unit],
                              className = "dynamic-width"
                            )
                          )
                        )
                      )
                    ),
                    if (terminals.size > 1) {
                      <.span(^.className := "separator")
                      <.div(
                        ^.className := "port-dashboard-terminal",
                        Checkboxes(
                          CheckboxesProps(
                            name = "terminals",
                            idPrefix = "terminal",
                            options = terminals.map { terminal =>
                              CheckboxOption(terminal.toString, s"Terminal ${numberString(terminal)}")
                            }.toJSArray,
                            label = "Terminals:",
                            legendSize = CheckboxesLegendSize.Medium,
                            value = selectedTerminals.toJSArray,
                            onChange =
                              ((values: js.Array[String]) => handleTerminalChange(values)): js.Function1[
                                js.Array[String],
                                Unit
                              ],
                            inline = true,
                            small = true
                          )
                        )
                      )
                    } else ""
                  )
                ),
                <.div(
                  <.h2(s"Arrivals"),
                  <.div(
                    ^.className := "port-dashboard-selection",
                    <.span(<.strong("Filters applied:")),
                    <.span(
                      s"Time period: $displayPeriodDisplay (${displayPeriod.start.prettyTime} to ${displayPeriod.end.prettyTime})"
                    ),
                    if (terminals.size > 1) {
                      <.span(^.className := "selection-separator")
                      <.span(s"Terminals: ${selectedTerminals.filter(_.nonEmpty).sorted.mkString(", ")}")
                    } else ""
                  )
                ),
                <.div(
                  if (noTerminalSelected(userHasTerminalPreference)) {
                    <.div(
                      <.h3("No terminal selected"),
                      <.p("Select all that apply to filter the dashboard by terminal.")
                    )
                  } else {
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
                        portDashboardModel.portState.renderReady { portState =>
                          portDashboardModel.featureFlags.renderReady { _ =>
                            val portStateForDashboard = portState.windowWithTerminalFilter(
                              displayPeriod.start,
                              displayPeriod.start.addMinutes(selectedPeriodLengthMinutes),
                              QueueConfig.terminalsForDateRange(portConfig.queuesByTerminal),
                              QueueConfig.queuesForDateRangeAndTerminal(portConfig.queuesByTerminal),
                              portDashboardModel.paxFeedSourceOrder
                            )
                            val scheduledFlightsInTerminal = portStateForDashboard
                              .flights
                              .filter(_._2.apiFlight.Terminal == terminal)
                              .values
                              .filterNot(_.apiFlight.isCancelled)
                              .toList

                            val terminalCrunchMinutes =
                              portStateForDashboard.crunchMinutes.filter(_._1.terminal == terminal).values.toList
                            val terminalStaffMinutes =
                              portStateForDashboard.staffMinutes.filter(_._1.terminal == terminal).values.toList
                            val terminalQueuesInOrder =
                              queuesForDateAndTerminal(displayPeriod.start.toLocalDate, terminal)
                            portDashboardModel.featureFlags.renderReady { _ =>
                              val queues = QueueConfig.queuesForDateAndTerminal(portConfig.queuesByTerminal)(
                                displayPeriod.start.toLocalDate,
                                terminalName
                              )
                              DashboardTerminalSummary(
                                DashboardTerminalSummary.Props(
                                  flights = scheduledFlightsInTerminal,
                                  crunchMinutes = terminalCrunchMinutes,
                                  staffMinutes = terminalStaffMinutes,
                                  terminal = terminal,
                                  paxTypeAndQueues = paxTypeAndQueueOrder(terminal).splits.map(_.paxType),
                                  queues = terminalQueuesInOrder,
                                  timeWindowStart = displayPeriod.start,
                                  paxFeedSourceOrder = portDashboardModel.paxFeedSourceOrder,
                                  periodLengthMinutes = selectedPeriodLengthMinutes / 3,
                                  terminalHasSingleDeskQueue = queues.contains(QueueDesk)
                                )
                              )
                            }
                          }
                        }
                      )
                    }.toTagMod
                  }
                )
              )
            }
          }
        )
      }
    }.build

  def apply(router: RouterCtl[Loc], dashboardPage: PortDashboardLoc = PortDashboardLoc(None)): VdomElement =
    component(Props(router, dashboardPage))
}
