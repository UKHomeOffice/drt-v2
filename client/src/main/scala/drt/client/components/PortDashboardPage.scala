package drt.client.components

import diode.UseValueEq
import diode.data.Pot
import diode.react.ModelProxy
import drt.client.SPAMain.{Loc, PortDashboardLoc}
import drt.client.components.TerminalComponent.TerminalModel
import drt.client.modules.GoogleEventTracker
import drt.client.services.JSDateConversions.SDate
import drt.client.services.{LoadingState, SPACircuit, StaffMovementMinute, ViewMode}
import drt.shared.CrunchApi.StaffMinute
import drt.shared._
import drt.shared.api.{FlightManifestSummary, WalkTimes}
import io.kinoplan.scalajs.react.material.ui.core.{MuiButton, MuiTypography}
import io.kinoplan.scalajs.react.material.ui.core.MuiButton._
import io.kinoplan.scalajs.react.material.ui.lab.MuiToggleButtonGroup
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Callback, CtorType, ReactEventFromInput, ScalaComponent}
import org.scalajs.dom.document
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, UniqueArrival}
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.model.CrunchMinute
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.config.slas.SlaConfigs
import uk.gov.homeoffice.drt.ports.{AirportConfig, FeedSource, PaxTypeAndQueue, PortCode, Queues}
import uk.gov.homeoffice.drt.redlist.RedListUpdates
import uk.gov.homeoffice.drt.time.SDateLike

import scala.collection.immutable.HashSet

object PortDashboardPage {

  case class Props(router: RouterCtl[Loc], dashboardPage: PortDashboardLoc, airportConfig: Pot[AirportConfig]) extends UseValueEq

  case class DisplayPeriod(start: SDateLike, end: SDateLike)

  private object DisplayPeriod {
    def apply(start: SDateLike, hours: Int = 3): DisplayPeriod = DisplayPeriod(start, start.addHours(hours))
  }

  //  private case class PortDashboardModel(airportConfig: Pot[AirportConfig],
  //                                        portState: Pot[PortState],
  //                                        featureFlags: Pot[FeatureFlags],
  //                                        paxFeedSourceOrder: List[FeedSource],
  //                                       )

  private case class PortDashboardModel(portState: Pot[PortState],
                                        userPreferencesPot: Pot[UserPreferences],
                                        dayOfShiftAssignmentsPot: Pot[ShiftAssignments],
                                        fixedPointsPot: Pot[FixedPointAssignments],
                                        staffMovementsPot: Pot[StaffMovements],
                                        removedStaffMovements: Set[String],
                                        airportConfigPot: Pot[AirportConfig],
                                        slaConfigsPot: Pot[SlaConfigs],
                                        loadingState: LoadingState,
                                        showActuals: Boolean,
                                        loggedInUserPot: Pot[LoggedInUser],
                                        viewMode: ViewMode,
                                        featureFlagsPot: Pot[FeatureFlags],
                                        redListPortsPot: Pot[HashSet[PortCode]],
                                        redListUpdatesPot: Pot[RedListUpdates],
                                        timeMachineEnabled: Boolean,
                                        walkTimesPot: Pot[WalkTimes],
                                        paxFeedSourceOrder: List[FeedSource],
                                        shiftsPot: Pot[Seq[Shift]],
                                        addedStaffMovementMinutes: Map[TM, Seq[StaffMovementMinute]],
                                        flightHighlight: FlightHighlight,
                                        flightManifestSummaries: Map[ManifestKey, FlightManifestSummary],
                                        arrivalSources: Option[(UniqueArrival, Pot[List[Option[FeedSourceArrival]]])],
                                       ) extends UseValueEq

  val component: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("PortDashboard")
    .render_P(p => {

      //      val modelRCP = SPACircuit.connect(rm => PortDashboardModel(rm.airportConfig, rm.portStatePot, rm.featureFlags, rm.paxFeedSourceOrder))


      val modelRCP = SPACircuit.connect(model => PortDashboardModel(
        portState = model.portStatePot,
        userPreferencesPot = model.userPreferences,
        dayOfShiftAssignmentsPot = model.dayOfShiftAssignments,
        fixedPointsPot = model.fixedPoints,
        staffMovementsPot = model.staffMovements,
        removedStaffMovements = model.removedStaffMovements,
        airportConfigPot = model.airportConfig,
        slaConfigsPot = model.slaConfigs,
        loadingState = model.loadingState,
        showActuals = model.showActualIfAvailable,
        loggedInUserPot = model.loggedInUserPot,
        viewMode = model.viewMode,
        featureFlagsPot = model.featureFlags,
        redListPortsPot = model.redListPorts,
        redListUpdatesPot = model.redListUpdates,
        timeMachineEnabled = model.maybeTimeMachineDate.isDefined,
        walkTimesPot = model.gateStandWalkTime,
        paxFeedSourceOrder = model.paxFeedSourceOrder,
        shiftsPot = model.shifts,
        addedStaffMovementMinutes = model.addedStaffMovementMinutes,
        flightHighlight = model.flightHighlight,
        flightManifestSummaries = model.flightManifestSummaries,
        arrivalSources = model.arrivalSources,
      ))

      modelRCP { modelMP: ModelProxy[PortDashboardModel] =>
        val portDashboardModel: PortDashboardModel = modelMP()
        <.div(^.className := "terminal-summary-dashboard",
          MuiTypography(variant = "h1")(s"Dashboard ${p.dashboardPage.portCodeStr} (${p.dashboardPage.portConfig.portName})"),
          portDashboardModel.airportConfigPot.renderReady(portConfig => {

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
                    portDashboardModel.featureFlagsPot.render(_ => {
                      portDashboardModel.loggedInUserPot.render { loggedInUser =>
                        portDashboardModel.userPreferencesPot.render { userPreferences =>
                          portDashboardModel.redListUpdatesPot.render { redListUpdates =>
                            val portStateForDashboard = portState.windowWithTerminalFilter(
                              displayPeriod.start,
                              displayPeriod.end,
                              portConfig.queuesByTerminal.view.filterKeys(_ == terminalName).toMap,
                              portDashboardModel.paxFeedSourceOrder,
                            )
                            val scheduledFlightsInTerminal: Seq[ApiFlightWithSplits] = portStateForDashboard
                              .flights
                              .values
                              .filterNot(_.apiFlight.isCancelled)
                              .toList
                            val terminalCrunchMinutes = portStateForDashboard.crunchMinutes.values.toList
                            val terminalStaffMinutes = portStateForDashboard.staffMinutes.values.toList
                            val terminalQueuesInOrder = Queues.inOrder(queues.getOrElse(terminalName, Seq()))

                            DashboardTerminalSummary(
                              DashboardTerminalSummary.Props(
                                portDashboardLoc = p.dashboardPage,
                                airportConfig = portConfig,
                                //                   slaConfigs: Pot[SlaConfigs],
                                router = p.router,
                                featureFlags = portDashboardModel.featureFlagsPot,
                                loggedInUser = loggedInUser,
                                redListPorts = portDashboardModel.redListPortsPot,
                                redListUpdates = redListUpdates,
                                walkTimes = portDashboardModel.walkTimesPot,
                                portState = portDashboardModel.portState,
                                flightManifestSummaries = portDashboardModel.flightManifestSummaries,
                                arrivalSources = portDashboardModel.arrivalSources,
                                flightHighlight = portDashboardModel.flightHighlight,
                                userPreferences = userPreferences,
                                flights = scheduledFlightsInTerminal.toList,
                                crunchMinutes = terminalCrunchMinutes,
                                staffMinutes = terminalStaffMinutes,
                                terminal = terminalName,
                                paxTypeAndQueues = paxTypeAndQueueOrder(terminalName).splits.map(_.paxType),
                                queues = terminalQueuesInOrder,
                                timeWindowStart = displayPeriod.start,
                                timeWindowEnd = displayPeriod.end,
                                paxFeedSourceOrder = portDashboardModel.paxFeedSourceOrder
                              )
                            )
                          }
                        }
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

  def apply(router: RouterCtl[Loc], dashboardPage: PortDashboardLoc = PortDashboardLoc(None), airportConfig: Pot[AirportConfig]): VdomElement =
    component(Props(router, dashboardPage, airportConfig))
}
