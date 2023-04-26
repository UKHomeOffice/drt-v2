package drt.client.components

import diode.data.Pot
import diode.{FastEqLowPri, UseValueEq}
import drt.client.SPAMain
import drt.client.SPAMain.TerminalPageModes._
import drt.client.SPAMain._
import drt.client.components.TerminalDesksAndQueues.Ideal
import drt.client.components.ToolTips._
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.modules.GoogleEventTracker
import drt.client.services._
import drt.shared.CrunchApi.ForecastPeriodWithHeadlines
import drt.shared._
import drt.shared.api.WalkTimes
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{CtorType, ReactEventFromInput, ScalaComponent}
import org.scalajs.dom.html.UList
import uk.gov.homeoffice.drt.arrivals.UniqueArrival
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.auth.Roles.StaffEdit
import uk.gov.homeoffice.drt.ports.{AirportConfig, PortCode}
import uk.gov.homeoffice.drt.redlist.RedListUpdates

import scala.collection.immutable.HashSet

object TerminalComponent {

  val log: Logger = LoggerFactory.getLogger("TerminalComponent")

  case class Props(terminalPageTab: TerminalPageTabLoc, router: RouterCtl[Loc]) extends FastEqLowPri

  case class TerminalModel(portStatePot: Pot[PortState],
                           forecastPeriodPot: Pot[ForecastPeriodWithHeadlines],
                           potShifts: Pot[ShiftAssignments],
                           potMonthOfShifts: Pot[MonthOfShifts],
                           potFixedPoints: Pot[FixedPointAssignments],
                           potStaffMovements: Pot[StaffMovements],
                           airportConfig: Pot[AirportConfig],
                           loadingState: LoadingState,
                           showActuals: Boolean,
                           loggedInUserPot: Pot[LoggedInUser],
                           viewMode: ViewMode,
                           minuteTicker: Int,
                           featureFlags: Pot[FeatureFlags],
                           arrivalSources: Option[(UniqueArrival, Pot[List[Option[FeedSourceArrival]]])],
                           redListPorts: Pot[HashSet[PortCode]],
                           redListUpdates: Pot[RedListUpdates],
                           timeMachineEnabled: Boolean,
                           walkTimes: Pot[WalkTimes],
                          ) extends UseValueEq

  private val activeClass = "active"

  val component: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("Terminal")
    .render_P { props =>
      val modelRCP = SPACircuit.connect(model => TerminalModel(
        model.portStatePot,
        model.forecastPeriodPot,
        model.shifts,
        model.monthOfShifts,
        model.fixedPoints,
        model.staffMovements,
        model.airportConfig,
        model.loadingState,
        model.showActualIfAvailable,
        model.loggedInUserPot,
        model.viewMode,
        model.minuteTicker,
        model.featureFlags,
        model.arrivalSources,
        model.redListPorts,
        model.redListUpdates,
        model.maybeTimeMachineDate.isDefined,
        model.gateStandWalkTime,
      ))

      val dialogueStateRCP = SPACircuit.connect(_.maybeStaffDeploymentAdjustmentPopoverState)

      <.div(
        dialogueStateRCP(dialogueStateMP => <.div(dialogueStateMP().map(dialogueState => StaffAdjustmentDialogue(dialogueState)()).whenDefined)),
        modelRCP(modelMP => {
          val model: TerminalModel = modelMP()

          <.div(model.airportConfig.render(airportConfig => {

            val timeRangeHours = if (model.viewMode == ViewLive) CurrentWindow() else WholeDayWindow()

            model.loggedInUserPot.render { loggedInUser =>
              model.redListUpdates.render { redListUpdates =>
                val terminalContentProps = TerminalContentComponent.Props(
                  portStatePot = model.portStatePot,
                  potShifts = model.potShifts,
                  potFixedPoints = model.potFixedPoints,
                  potStaffMovements = model.potStaffMovements,
                  airportConfig = airportConfig,
                  terminalPageTab = props.terminalPageTab,
                  defaultTimeRangeHours = timeRangeHours,
                  router = props.router,
                  showActuals = model.showActuals,
                  viewMode = model.viewMode,
                  loggedInUser = loggedInUser,
                  minuteTicker = model.minuteTicker,
                  featureFlags = model.featureFlags,
                  arrivalSources = model.arrivalSources,
                  redListPorts = model.redListPorts,
                  redListUpdates = model.redListUpdates,
                  walkTimes = model.walkTimes,
                )
                <.div(
                  <.div(^.className := "terminal-nav-wrapper", terminalTabs(props, loggedInUser, airportConfig, model.timeMachineEnabled)),
                  <.div(^.className := "tab-content",
                    props.terminalPageTab.mode match {
                      case Current =>
                        <.div(
                          DaySelectorComponent(DaySelectorComponent.Props(props.router,
                            props.terminalPageTab,
                            model.loadingState,
                            model.minuteTicker,
                            PcpPaxSummariesComponent(terminalContentProps.portStatePot, terminalContentProps.viewMode, props.terminalPageTab.terminal, model.minuteTicker),
                          )),
                          TerminalContentComponent(terminalContentProps)
                        )
                      case Dashboard =>
                        terminalContentProps.portStatePot.renderReady(ps =>
                          TerminalDashboardComponent(
                            terminalPageTabLoc = props.terminalPageTab,
                            airportConfig = terminalContentProps.airportConfig,
                            portState = ps,
                            router = props.router,
                            featureFlags = model.featureFlags,
                            loggedInUser = loggedInUser,
                            redListPorts = model.redListPorts,
                            redListUpdates = redListUpdates,
                            walkTimes = model.walkTimes,
                          )
                        )
                      case Planning =>
                        <.div(model.forecastPeriodPot.render(fp => {
                          TerminalPlanningComponent(TerminalPlanningComponent.Props(fp, props.terminalPageTab, props.router))
                        }))
                      case Staffing if loggedInUser.roles.contains(StaffEdit) =>
                        model.potMonthOfShifts.render { ms =>
                          MonthlyStaffing(ms.shifts, props.terminalPageTab, props.router)
                        }
                    }
                  )
                )
              }
            }
          }))
        })
      )
    }
    .build

  private def terminalTabs(props: Props, loggedInUser: LoggedInUser, airportConfig: AirportConfig, timeMachineEnabled: Boolean): VdomTagOf[UList] = {
    val terminalName = props.terminalPageTab.terminal.toString

    val subMode = if (props.terminalPageTab.mode != Current && props.terminalPageTab.mode != Snapshot)
      "arrivals"
    else
      props.terminalPageTab.subMode

    def tabClass(mode: TerminalPageMode): String = if (props.terminalPageTab.mode == mode) activeClass else ""

    def viewTypeQueryParam: SPAMain.UrlParameter =
      if (airportConfig.idealStaffAsDefault)
        UrlViewType(Option(Ideal))
      else
        UrlViewType(None)

    val timeMachineClass = if (timeMachineEnabled) "time-machine-active" else ""

    <.ul(^.className := "nav nav-tabs",
      <.li(^.className := tabClass(Current) + " " + timeMachineClass,
        <.a(^.id := "currentTab", "Queues & Arrivals", VdomAttr("data-toggle") := "tab"),
        ^.onClick ==> { e: ReactEventFromInput =>
          e.preventDefault()
          GoogleEventTracker.sendEvent(terminalName, "click", "Queues & Arrivals")
          props.router.set(props.terminalPageTab.update(
            mode = Current,
            subMode = subMode,
            queryParams = props.terminalPageTab.withUrlParameters(UrlDateParameter(None), viewTypeQueryParam).queryParams
          ))
        }),
      <.li(^.className := tabClass(Planning),
        <.a(^.id := "planningTab", VdomAttr("data-toggle") := "tab", "Planning"),
        ^.onClick ==> { e: ReactEventFromInput =>
          e.preventDefault()
          GoogleEventTracker.sendEvent(terminalName, "click", "Planning")
          props.router.set(props.terminalPageTab.update(
            mode = Planning,
            subMode = subMode,
            queryParams = props.terminalPageTab.withUrlParameters(UrlDateParameter(None), UrlTimeMachineDateParameter(None)).queryParams
          ))
        }
      ),
      if (loggedInUser.roles.contains(StaffEdit))
        <.li(^.className := tabClass(Staffing),
          <.a(^.id := "monthlyStaffingTab", VdomAttr("data-toggle") := "tab", "Monthly Staffing", " ", monthlyStaffingTooltip),
          ^.onClick ==> { e: ReactEventFromInput =>
            e.preventDefault()
            GoogleEventTracker.sendEvent(terminalName, "click", "Monthly Staffing")
            props.router.set(props.terminalPageTab.update(
              mode = Staffing,
              subMode = "15",
              queryParams = props.terminalPageTab.withUrlParameters(UrlDateParameter(None), UrlTimeMachineDateParameter(None)).queryParams
            ))
          }
        ) else "",
      <.li(^.className := tabClass(Dashboard),
        <.a(^.id := "terminalDashboardTab", VdomAttr("data-toggle") := "tab", s"$terminalName Dashboard"), ^.onClick --> {
          GoogleEventTracker.sendEvent(terminalName, "click", "Terminal Dashboard")
          props.router.set(
            props.terminalPageTab.update(
              mode = Dashboard,
              subMode = "summary",
              queryParams = props.terminalPageTab.withUrlParameters(UrlDateParameter(None), UrlTimeMachineDateParameter(None)).queryParams)
          )
        }
      )
    )
  }

  def apply(props: Props): VdomElement = component(props)
}
