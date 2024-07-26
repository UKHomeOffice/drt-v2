package drt.client.components

import diode.data.Pot
import diode.{FastEqLowPri, UseValueEq}
import drt.client.SPAMain
import drt.client.SPAMain._
import drt.client.components.TerminalDesksAndQueues.Ideal
import drt.client.components.ToolTips._
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.modules.GoogleEventTracker
import drt.client.services._
import drt.client.services.handlers.GetUserPreferenceIntervalMinutes
import drt.client.spa.TerminalPageMode
import drt.client.spa.TerminalPageModes._
import drt.shared._
import drt.shared.api.WalkTimes
import japgolly.scalajs.react.callback.Callback
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{CtorType, ReactEventFromInput, Reusability, ScalaComponent}
import org.scalajs.dom.html.UList
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.auth.Roles.StaffEdit
import uk.gov.homeoffice.drt.ports.config.slas.SlaConfigs
import uk.gov.homeoffice.drt.ports.{AirportConfig, FeedSource, PortCode}
import uk.gov.homeoffice.drt.redlist.RedListUpdates

import scala.collection.immutable.HashSet

object TerminalComponent {

  val log: Logger = LoggerFactory.getLogger("TerminalComponent")

  case class Props(terminalPageTab: TerminalPageTabLoc, router: RouterCtl[Loc]) extends FastEqLowPri

  implicit val propsReuse: Reusability[Props] = Reusability((a, b) => a.terminalPageTab == b.terminalPageTab)

  private case class TerminalModel(userSelectedPlanningTimePeriod: Pot[Int],
                                   potShifts: Pot[ShiftAssignments],
                                   potMonthOfShifts: Pot[MonthOfShifts],
                                   potFixedPoints: Pot[FixedPointAssignments],
                                   potStaffMovements: Pot[StaffMovements],
                                   airportConfig: Pot[AirportConfig],
                                   slaConfigs: Pot[SlaConfigs],
                                   loadingState: LoadingState,
                                   showActuals: Boolean,
                                   loggedInUserPot: Pot[LoggedInUser],
                                   viewMode: ViewMode,
                                   minuteTicker: Int,
                                   featureFlags: Pot[FeatureFlags],
                                   redListPorts: Pot[HashSet[PortCode]],
                                   redListUpdates: Pot[RedListUpdates],
                                   timeMachineEnabled: Boolean,
                                   walkTimes: Pot[WalkTimes],
                                   paxFeedSourceOrder: List[FeedSource],
                                  ) extends UseValueEq

  private val activeClass = "active"

  val component: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("TerminalComponent")
    .render_P { props =>
      val modelRCP = SPACircuit.connect(model => TerminalModel(
        userSelectedPlanningTimePeriod = model.userSelectedPlanningTimePeriod,
        potShifts = model.shifts,
        potMonthOfShifts = model.monthOfShifts,
        potFixedPoints = model.fixedPoints,
        potStaffMovements = model.staffMovements,
        airportConfig = model.airportConfig,
        slaConfigs = model.slaConfigs,
        loadingState = model.loadingState,
        showActuals = model.showActualIfAvailable,
        loggedInUserPot = model.loggedInUserPot,
        viewMode = model.viewMode,
        minuteTicker = model.minuteTicker,
        featureFlags = model.featureFlags,
        redListPorts = model.redListPorts,
        redListUpdates = model.redListUpdates,
        timeMachineEnabled = model.maybeTimeMachineDate.isDefined,
        walkTimes = model.gateStandWalkTime,
        paxFeedSourceOrder = model.paxFeedSourceOrder,
      ))

      val dialogueStateRCP = SPACircuit.connect(_.maybeStaffDeploymentAdjustmentPopoverState)

      <.div(
        dialogueStateRCP(dialogueStateMP => <.div(dialogueStateMP().map(dialogueState => StaffAdjustmentDialogue(dialogueState)()).whenDefined)),
        modelRCP(modelMP => {
          val model: TerminalModel = modelMP()
          for {
            airportConfig <- model.airportConfig
            loggedInUser <- model.loggedInUserPot
            redListUpdates <- model.redListUpdates
            slaConfigs <- model.slaConfigs
          } yield {
            val timeRangeHours = if (model.viewMode == ViewLive) CurrentWindow() else WholeDayWindow()

            val terminalContentProps = TerminalContentComponent.Props(
              potShifts = model.potShifts,
              potFixedPoints = model.potFixedPoints,
              potStaffMovements = model.potStaffMovements,
              airportConfig = airportConfig,
              slaConfigs = slaConfigs,
              terminalPageTab = props.terminalPageTab,
              defaultTimeRangeHours = timeRangeHours,
              router = props.router,
              showActuals = model.showActuals,
              viewMode = model.viewMode,
              loggedInUser = loggedInUser,
              minuteTicker = model.minuteTicker,
              featureFlags = model.featureFlags,
              redListPorts = model.redListPorts,
              redListUpdates = model.redListUpdates,
              walkTimes = model.walkTimes,
              paxFeedSourceOrder = model.paxFeedSourceOrder,
            )
            <.div(
              <.div(^.className := "terminal-nav-wrapper", terminalTabs(props, loggedInUser, airportConfig, model.timeMachineEnabled)),
              <.div(^.className := "tab-content",
                props.terminalPageTab.mode match {
                  case Current =>
                    val headerClass = if (model.timeMachineEnabled) "terminal-content-header__time-machine" else ""
                    <.div(
                      <.div(^.className := s"terminal-content-header $headerClass",
                        DaySelectorComponent(
                          DaySelectorComponent.Props(
                            router = props.router,
                            terminalPageTab = props.terminalPageTab,
                            loadingState = model.loadingState,
                            minuteTicker = model.minuteTicker,
                          )),
                        PcpPaxSummariesComponent(terminalContentProps.viewMode, model.minuteTicker)
                      ),
                      TerminalContentComponent(terminalContentProps)
                    )
                  case Dashboard =>
                    TerminalDashboardComponent(
                      terminalPageTabLoc = props.terminalPageTab,
                      airportConfig = terminalContentProps.airportConfig,
                      slaConfigs = terminalContentProps.slaConfigs,
                      router = props.router,
                      featureFlags = model.featureFlags,
                      loggedInUser = loggedInUser,
                      redListPorts = model.redListPorts,
                      redListUpdates = redListUpdates,
                      walkTimes = model.walkTimes,
                      paxFeedSourceOrder = model.paxFeedSourceOrder,
                    )

                  case Planning =>
                    <.div(model.userSelectedPlanningTimePeriod.render { timePeriod =>
                      TerminalPlanningComponent(TerminalPlanningComponent.Props(props.terminalPageTab, props.router, timePeriod))
                    })
                  case Staffing if loggedInUser.roles.contains(StaffEdit) =>
                    model.potMonthOfShifts.render { ms =>
                      MonthlyStaffing(ms.shifts, props.terminalPageTab, props.router)
                    }
                }
              )
            )
          }
        }.getOrElse(LoadingOverlay())
        ))
    }
    .componentDidMount(_ =>
      Callback(SPACircuit.dispatch(GetUserPreferenceIntervalMinutes()))
    )
    .configure(Reusability.shouldComponentUpdate)
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
        <.a(^.id := "planning-tab", VdomAttr("data-toggle") := "tab", "Planning"),
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
          <.a(^.id := "monthlyStaffingTab", ^.className := "flex-forizontally", VdomAttr("data-toggle") := "tab", "Monthly Staffing", " ", monthlyStaffingTooltip),
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
