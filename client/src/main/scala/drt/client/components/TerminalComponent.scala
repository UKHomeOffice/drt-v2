package drt.client.components

import diode.data.{Pending, Pot}
import drt.client.SPAMain.{Loc, TerminalPageTabLoc}
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.services.JSDateConversions.SDate
import drt.client.services._
import drt.shared.CrunchApi.{CrunchState, ForecastPeriodWithHeadlines}
import drt.shared._
import japgolly.scalajs.react.extra.Reusability
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Callback, ScalaComponent}

import scala.collection.immutable

object TerminalComponent {

  val log: Logger = LoggerFactory.getLogger("TerminalComponent")

  case class Props(terminalPageTab: TerminalPageTabLoc, router: RouterCtl[Loc])

  case class TerminalModel(
                            crunchStatePot: Pot[CrunchState],
                            forecastPeriodPot: Pot[ForecastPeriodWithHeadlines],
                            potShifts: Pot[String],
                            potMonthOfShifts: Pot[MonthOfRawShifts],
                            potFixedPoints: Pot[String],
                            potStaffMovements: Pot[immutable.Seq[StaffMovement]],
                            airportConfig: Pot[AirportConfig],
                            airportInfos: Pot[AirportInfo],
                            loadingState: LoadingState,
                            showActuals: Boolean,
                            userRoles: Pot[List[String]],
                            viewMode: ViewMode,
                            minuteTicker: Int
                          )

  implicit val pageReuse: Reusability[TerminalPageTabLoc] = Reusability.derive[TerminalPageTabLoc]
  implicit val propsReuse: Reusability[Props] = Reusability.by(p =>
    (p.terminalPageTab, p.router)
  )

  val component = ScalaComponent.builder[Props]("Terminal")
    .render_P(props => {
      val modelRCP = SPACircuit.connect(model => TerminalModel(
        model.crunchStatePot,
        model.forecastPeriodPot,
        model.shiftsRaw,
        model.monthOfShifts,
        model.fixedPointsRaw,
        model.staffMovements,
        model.airportConfig,
        model.airportInfos.getOrElse(props.terminalPageTab.terminal, Pending()),
        model.loadingState,
        model.showActualIfAvailable,
        model.userRoles,
        model.viewMode,
        model.minuteTicker
      ))
      modelRCP(modelMP => {
        val model = modelMP()
        <.div(model.airportConfig.render(airportConfig => {

          val terminalContentProps = TerminalContentComponent.Props(
            model.crunchStatePot,
            model.potShifts,
            model.potFixedPoints,
            model.potStaffMovements,
            airportConfig,
            props.terminalPageTab,
            model.airportInfos,
            if (model.viewMode == ViewLive())
              CurrentWindow()
            else
              WholeDayWindow(),
            props.router,
            model.showActuals,
            model.viewMode,
            model.userRoles,
            model.minuteTicker
          )

          val currentClass = if (props.terminalPageTab.mode == "current") "active" else ""
          val snapshotDataClass = if (props.terminalPageTab.mode == "snapshot") "active" else ""
          val planningClass = if (props.terminalPageTab.mode == "planning") "active" else ""
          val staffingClass = if (props.terminalPageTab.mode == "staffing") "active" else ""

          val currentContentClass = if (props.terminalPageTab.mode == "current") "fade in active" else "fade out"
          val snapshotContentClass = if (props.terminalPageTab.mode == "snapshot") "fade in active" else "fade out"
          val planningContentClass = if (props.terminalPageTab.mode == "planning") "fade in active" else "fade out"
          val staffingContentClass = if (props.terminalPageTab.mode == "staffing") "fade in active" else "fade out"

          val subMode = if (props.terminalPageTab.mode == "staffing") "desksAndQueues" else props.terminalPageTab.subMode

          <.div(
            <.ul(^.className := "nav nav-tabs",
              <.li(^.className := currentClass, <.a(VdomAttr("data-toggle") := "tab", "Current"), ^.onClick --> {
                props.router.set(props.terminalPageTab.copy(
                  mode = "current",
                  subMode = subMode,
                  date = None,
                  timeRangeStartString = props.terminalPageTab.timeRangeStartString,
                  timeRangeEndString = props.terminalPageTab.timeRangeEndString
                ))
              }),
              <.li(^.className := snapshotDataClass,
                <.a(VdomAttr("data-toggle") := "tab", "Snapshot"), ^.onClick --> {
                  props.router.set(props.terminalPageTab.copy(
                    mode = "snapshot",
                    subMode = subMode,
                    date = None,
                    timeRangeStartString = props.terminalPageTab.timeRangeStartString,
                    timeRangeEndString = props.terminalPageTab.timeRangeEndString
                  ))
                }
              ),
              <.li(^.className := planningClass,
                <.a(VdomAttr("data-toggle") := "tab", "Planning"), ^.onClick --> {
                  props.router.set(props.terminalPageTab.copy(mode = "planning", subMode = subMode, date = None))
                }
              ),
              model.userRoles.render(
                r => if (r.contains("staff:edit"))
                  <.li(^.className := staffingClass,
                    <.a(VdomAttr("data-toggle") := "tab", "Monthly Staffing"), ^.onClick --> {
                      props.router.set(props.terminalPageTab.copy(mode = "staffing", subMode = "15", date = None))
                    }
                  ) else ""
              )
            ),
            <.div(^.className := "tab-content",
              <.div(^.id := "current", ^.className := s"tab-pane $currentContentClass", {
                if (props.terminalPageTab.mode == "current") <.div(
                  <.h2(props.terminalPageTab.dateFromUrlOrNow match {
                    case date: SDateLike if date.ddMMyyString == SDate.now().ddMMyyString => "Live View"
                    case date: SDateLike if date.millisSinceEpoch < SDate.now().millisSinceEpoch => "Historic View"
                    case date: SDateLike if date.millisSinceEpoch > SDate.now().millisSinceEpoch => "Forecast View"
                    case _ => "Live View"
                  }),
                  <.div(^.className := "content-head",
                    PcpPaxSummariesComponent(terminalContentProps.crunchStatePot, terminalContentProps.viewMode, props.terminalPageTab.terminal, model.minuteTicker),
                    DatePickerComponent(DatePickerComponent.Props(props.router,
                      props.terminalPageTab,
                      model.loadingState,
                      model.minuteTicker
                    ))
                  ),
                  TerminalContentComponent(terminalContentProps)
                ) else ""
              }),
              <.div(^.id := "snapshot", ^.className := s"tab-pane $snapshotContentClass", {
                if (props.terminalPageTab.mode == "snapshot") <.div(
                  <.h2("Snapshot View"),
                  SnapshotSelector(props.router, props.terminalPageTab, model.loadingState),
                  TerminalContentComponent(terminalContentProps)
                ) else ""
              }),
              <.div(^.id := "planning", ^.className := s"tab-pane $planningContentClass", {
                if (props.terminalPageTab.mode == "planning") {
                  <.div(
                    <.div(model.forecastPeriodPot.render(fp => {
                      TerminalPlanningComponent(TerminalPlanningComponent.Props(fp, props.terminalPageTab, props.router))
                    }))
                  )
                } else ""
              }),
              model.userRoles.render(
                r => if (r.contains("staff:edit"))
                  <.div(^.id := "staffing", ^.className := s"tab-pane terminal-staffing-container $staffingContentClass",
                    if (props.terminalPageTab.mode == "staffing") {
                      model.potMonthOfShifts.render(ms => {
                        TerminalStaffingV2(ms.shifts, props.terminalPageTab, props.router)
                      })
                    } else ""
                  ) else "")
            )
          )
        }))
      })
    })
    .componentDidMount((p) => Callback.log("TerminalComponent did mount"))
    .configure(Reusability.shouldComponentUpdate)
    .build

  def apply(props: Props): VdomElement = component(props)
}
