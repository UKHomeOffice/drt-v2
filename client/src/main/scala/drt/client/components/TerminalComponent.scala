package drt.client.components

import diode.UseValueEq
import diode.data.Pot
import drt.client.SPAMain.TerminalPageModes._
import drt.client.SPAMain.{Loc, TerminalPageTabLoc, UrlDateParameter}
import drt.client.components.ToolTips._
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.modules.GoogleEventTracker
import drt.client.services.JSDateConversions.SDate
import drt.client.services._
import drt.shared.CrunchApi.ForecastPeriodWithHeadlines
import drt.shared._
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{CtorType, ScalaComponent}
import org.scalajs.dom.html.UList
import uk.gov.homeoffice.drt.arrivals.UniqueArrival
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.auth.Roles.StaffEdit
import uk.gov.homeoffice.drt.ports.{AirportConfig, PortCode}
import uk.gov.homeoffice.drt.redlist.RedListUpdates
import uk.gov.homeoffice.drt.time.SDateLike

import scala.collection.immutable.HashSet

object TerminalComponent {

  val log: Logger = LoggerFactory.getLogger("TerminalComponent")

  case class Props(terminalPageTab: TerminalPageTabLoc, router: RouterCtl[Loc]) extends UseValueEq

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
                           maybeStaffAdjustmentsPopoverState: Option[StaffAdjustmentDialogueState],
                           featureFlags: Pot[FeatureFlags],
                           arrivalSources: Option[(UniqueArrival, Pot[List[Option[FeedSourceArrival]]])],
                           redListPorts: Pot[HashSet[PortCode]],
                           redListUpdates: Pot[RedListUpdates],
                          ) extends UseValueEq

  private val activeClass = "active"

  val component: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("Terminal")
    .render_P(props => {
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
        model.maybeStaffDeploymentAdjustmentPopoverState,
        model.featureFlags,
        model.arrivalSources,
        model.redListPorts,
        model.redListUpdates,
      ))

      val dialogueStateRCP = SPACircuit.connect(_.maybeStaffDeploymentAdjustmentPopoverState)


      <.div(
        dialogueStateRCP(dialogueStateMP => <.div(dialogueStateMP().map(dialogueState => StaffAdjustmentDialogue(dialogueState)()).whenDefined)),
        modelRCP(modelMP => {
          val model = modelMP()
          <.div(model.airportConfig.render(airportConfig => {

            val timeRangeHours = if (model.viewMode == ViewLive) CurrentWindow() else WholeDayWindow()

            val currentContentClass = if (props.terminalPageTab.mode == Current) "fade in " + activeClass else "fade out"
            val snapshotContentClass = if (props.terminalPageTab.mode == Snapshot) "fade in " + activeClass else "fade out"
            val planningContentClass = if (props.terminalPageTab.mode == Planning) "fade in " + activeClass else "fade out"
            val staffingContentClass = if (props.terminalPageTab.mode == Staffing) "fade in " + activeClass else "fade out"
            val dashboardContentClass = if (props.terminalPageTab.mode == Dashboard) "fade in " + activeClass else "fade out"

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
                )
                <.div(
                  <.div(^.className := "terminal-nav-wrapper", terminalTabs(props, loggedInUser)),
                  <.div(^.className := "tab-content",
                    <.div(^.id := "dashboard", ^.className := s"tab-pane terminal-dashboard-container $dashboardContentClass",
                      if (props.terminalPageTab.mode == Dashboard) {
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
                          )
                        )
                      } else ""
                    ),
                    <.div(^.id := "current", ^.className := s"tab-pane $currentContentClass", {
                      if (props.terminalPageTab.mode == Current) <.div(
                        <.div(
                          ^.className := "current-view-header",
                          <.div(
                            <.h2(props.terminalPageTab.dateFromUrlOrNow match {
                              case date: SDateLike if date.ddMMyyString == SDate.now().ddMMyyString => "Live View"
                              case date: SDateLike if date.millisSinceEpoch < SDate.now().millisSinceEpoch => "Historic View"
                              case date: SDateLike if date.millisSinceEpoch > SDate.now().millisSinceEpoch => "Forecast View"
                              case _ => "Live View"
                            }),
                            DaySelectorComponent(DaySelectorComponent.Props(props.router,
                              props.terminalPageTab,
                              model.loadingState,
                              model.minuteTicker
                            ))
                          ),
                          <.div(^.className := "content-head",
                            PcpPaxSummariesComponent(terminalContentProps.portStatePot, terminalContentProps.viewMode, props.terminalPageTab.terminal, model.minuteTicker),
                          )
                        ),
                        TerminalContentComponent(terminalContentProps)
                      ) else ""
                    }),
                    <.div(^.id := "snapshot", ^.className := s"tab-pane $snapshotContentClass", {
                      if (props.terminalPageTab.mode == Snapshot) <.div(
                        <.h2("Snapshot View"),
                        SnapshotSelector(props.router, props.terminalPageTab, model.loadingState),
                        TerminalContentComponent(terminalContentProps)
                      ) else ""
                    }),
                    <.div(^.id := "planning", ^.className := s"tab-pane $planningContentClass", {
                      if (props.terminalPageTab.mode == Planning) {
                        <.div(
                          <.div(model.forecastPeriodPot.render(fp => {
                            TerminalPlanningComponent(TerminalPlanningComponent.Props(fp, props.terminalPageTab, props.router))
                          }))
                        )
                      } else ""
                    }),
                    if (loggedInUser.roles.contains(StaffEdit))
                      <.div(^.id := "staffing", ^.className := s"tab-pane terminal-staffing-container $staffingContentClass",
                        if (props.terminalPageTab.mode == Staffing) {
                          model.potMonthOfShifts.render(ms => {
                            MonthlyStaffing(ms.shifts, props.terminalPageTab, props.router)
                          })
                        } else ""
                      ) else ""
                  )
                )
              }
            }
          }))
        })
      )
    })
    .build

  private def terminalTabs(props: Props, loggedInUser: LoggedInUser): VdomTagOf[UList] = {
    val terminalName = props.terminalPageTab.terminal.toString

    val subMode = if (props.terminalPageTab.mode != Current && props.terminalPageTab.mode != Snapshot)
      "desksAndQueues"
    else
      props.terminalPageTab.subMode

    val currentClass = if (props.terminalPageTab.mode == Current) activeClass else ""
    val snapshotDataClass = if (props.terminalPageTab.mode == Snapshot) activeClass else ""
    val planningClass = if (props.terminalPageTab.mode == Planning) activeClass else ""
    val staffingClass = if (props.terminalPageTab.mode == Staffing) activeClass else ""
    val terminalDashboardClass = if (props.terminalPageTab.mode == Dashboard) activeClass else ""

    <.ul(^.className := "nav nav-tabs",
      <.li(^.className := terminalDashboardClass,
        <.a(^.id := "terminalDashboardTab", VdomAttr("data-toggle") := "tab", s"$terminalName Dashboard"), ^.onClick --> {
          GoogleEventTracker.sendEvent(terminalName, "click", "Terminal Dashboard")
          props.router.set(
            props.terminalPageTab.update(
              mode = Dashboard,
              subMode = "summary",
              queryParams = props.terminalPageTab.withUrlParameters(UrlDateParameter(None)).queryParams)
          )
        }
      ),
      <.li(^.className := currentClass,
        <.a(^.id := "currentTab", VdomAttr("data-toggle") := "tab", "Current", " ", currentTooltip), ^.onClick --> {
          GoogleEventTracker.sendEvent(terminalName, "click", "Current")
          props.router.set(props.terminalPageTab.update(
            mode = Current,
            subMode = subMode,
            queryParams = props.terminalPageTab.withUrlParameters(UrlDateParameter(None)).queryParams
          ))
        }),
      <.li(^.className := snapshotDataClass,
        <.a(^.id := "snapshotTab", VdomAttr("data-toggle") := "tab", "Snapshot", " ", snapshotTooltip),
        ^.onClick --> {
          GoogleEventTracker.sendEvent(terminalName, "click", "Snapshot")
          props.router.set(props.terminalPageTab.update(
            mode = Snapshot,
            subMode = subMode,
            queryParams = props.terminalPageTab.withUrlParameters(UrlDateParameter(None)).queryParams
          ))
        }
      ),
      <.li(^.className := planningClass,
        <.a(^.id := "planningTab", VdomAttr("data-toggle") := "tab", "Planning"),
        ^.onClick --> {
          GoogleEventTracker.sendEvent(terminalName, "click", "Planning")
          props.router.set(props.terminalPageTab.update(mode = Planning, subMode = subMode, queryParams = props.terminalPageTab.withUrlParameters(UrlDateParameter(None)).queryParams))
        }
      ),
      if (loggedInUser.roles.contains(StaffEdit))
        <.li(^.className := staffingClass,
          <.a(^.id := "monthlyStaffingTab", VdomAttr("data-toggle") := "tab", "Monthly Staffing", " ", monthlyStaffingTooltip),
          ^.onClick --> {
            GoogleEventTracker.sendEvent(terminalName, "click", "Monthly Staffing")
            props.router.set(props.terminalPageTab.update(mode = Staffing, subMode = "15", queryParams = props.terminalPageTab.withUrlParameters(UrlDateParameter(None)).queryParams))
          }
        ) else ""
    )
  }

  def apply(props: Props): VdomElement = component(props)
}
