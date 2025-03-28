package drt.client.components

import diode.AnyAction.aType
import diode.data.{Empty, Pot, Ready}
import drt.client.SPAMain.{Loc, TerminalPageTabLoc}
import drt.client.actions.Actions.{UpdateShifts, UpdateStaffShifts}
import drt.client.components.MonthlyStaffingUtil._
import drt.client.components.StaffingUtil.{consecutiveDayForWeek, consecutiveDaysInMonth, dateRangeDays}
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.services.JSDateConversions.SDate
import drt.client.services.SPACircuit
import drt.client.services.handlers.UpdateUserPreferences
import drt.client.util.DateRange
import drt.shared._
import io.kinoplan.scalajs.react.material.ui.core.MuiButton.Color
import io.kinoplan.scalajs.react.material.ui.core._
import io.kinoplan.scalajs.react.material.ui.core.system.SxProps
import japgolly.scalajs.react.callback.Callback
import japgolly.scalajs.react.component.Scala.{Component, Unmounted}
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{CtorType, _}
import moment.Moment
import org.scalajs.dom.html.Div
import uk.gov.homeoffice.drt.ports.AirportConfig
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{LocalDate, SDateLike}

import scala.scalajs.js
import scala.util.Try

object MonthlyStaffing {

  case class TimeSlotDay(timeSlot: Int, day: Int) {
    def key: (Int, Int) = (timeSlot, day)
  }

  case class ColumnHeader(day: String, dayOfWeek: String)

  case class State(timeSlots: Pot[Seq[Seq[Any]]],
                   colHeadings: Seq[ColumnHeader],
                   rowHeadings: Seq[String],
                   changes: Map[(Int, Int), Int],
                   showEditStaffForm: Boolean,
                   showStaffSuccess: Boolean,
                   addShiftForm: Boolean,
                   shifts: ShiftAssignments,
                   shiftsLastLoaded: Option[Long] = None,
                  )

  val log: Logger = LoggerFactory.getLogger(getClass.getName)

  case class Props(terminalPageTab: TerminalPageTabLoc,
                   router: RouterCtl[Loc],
                   airportConfig: AirportConfig,
                   isStaffShiftPage: Boolean,
                   userPreferences: UserPreferences,
                  ) {
    def timeSlotMinutes: Int = Try(terminalPageTab.subMode.toInt).toOption.getOrElse(60)

  }

  implicit val propsReuse: Reusability[Props] = Reusability((a, b) => a == b)
  implicit val stateReuse: Reusability[State] = Reusability((a, b) => a == b)

  class Backend(scope: BackendScope[Props, State]) {
    def render(props: Props, state: State): VdomTagOf[Div] = {
      def handleShiftViewToggle(): Callback = {
        Callback(SPACircuit.dispatch(UpdateUserPreferences(props.userPreferences.copy(showStaffingShiftView = !props.userPreferences.showStaffingShiftView))))
      }

      val handleShiftEditForm = (e: ReactEventFromInput) => Callback {
        e.preventDefault()
        scope.modState(state => state.copy(showEditStaffForm = true)).runNow()
      }

      def confirmAndSaveStaffing(viewingDate: SDateLike, timeSlots: Seq[Seq[Any]], props: MonthlyStaffing.Props, state: MonthlyStaffing.State, scope: BackendScope[MonthlyStaffing.Props, MonthlyStaffing.State]): ReactEventFromInput => Callback = {
        ConfirmAndSaveForMonthlyStaffing(viewingDate, timeSlots, props, state, scope)()
      }

      val viewingDate = props.terminalPageTab.dateFromUrlOrNow

      case class Model(monthOfStaffShiftsPot: Pot[ShiftAssignments], monthOfShiftsPot: Pot[ShiftAssignments])
      val staffRCP = SPACircuit.connect(m => Model(m.allStaffAssignments, m.allShifts))

      val modelChangeDetection = staffRCP { modelMP =>
        val model = modelMP()
        val content = for {
          monthOfShifts <- if (props.isStaffShiftPage) model.monthOfStaffShiftsPot else model.monthOfShiftsPot
        } yield {
          if (monthOfShifts != state.shifts || state.timeSlots.isEmpty) {
            val slots = slotsFromShifts(monthOfShifts,
              props.terminalPageTab.terminal,
              viewingDate,
              props.timeSlotMinutes,
              props.terminalPageTab.dayRangeType.getOrElse("monthly"))
            scope.modState(state => state.copy(shifts = monthOfShifts, timeSlots = Ready(slots),
              shiftsLastLoaded = Option(SDate.now().millisSinceEpoch))).runNow()
          }
          <.div()
        }
        <.div(content.render(identity))
      }

      <.div(<.input.button(^.value := "staff updates",
        ^.className := "btn btn-primary",
        ^.onClick ==> handleShiftEditForm
      ))
      <.div(
        <.div(^.style := js.Dictionary("display" -> "flex", "justify-content" -> "flex-start", "gap" -> "40px", "align-items" -> "center"),
          <.div(<.h1("Staffing")),
          if (props.isStaffShiftPage) {
            <.div(^.className := "staffing-controls-toggle",
              <.div(^.style := js.Dictionary("display" -> "flex", "flexDirection" -> "row", "alignItems" -> "center", "paddingTop" -> "15px"))(
                MuiTypography()("Show shifts"),
                MuiFormControl()(
                  MuiSwitch(
                    defaultChecked = props.userPreferences.showStaffingShiftView,
                    color = Color.primary,
                    inputProps = js.Dynamic.literal("aria-label" -> "primary checkbox"),
                  )(^.onChange --> handleShiftViewToggle()),
                ), MuiTypography(sx = SxProps(Map("paddingRight" -> "10px")))(if (props.userPreferences.showStaffingShiftView) "On" else "Off")
              ))
          } else EmptyVdom),
        if (!props.userPreferences.showStaffingShiftView) {
          <.div(
            modelChangeDetection,
            <.div(
              if (state.showStaffSuccess)
                StaffUpdateSuccess(IStaffUpdateSuccess(0, "The staff numbers have been successfully updated for your chosen dates and times", () => {
                  scope.modState(state => state.copy(showStaffSuccess = false)).runNow()
                })) else EmptyVdom,
            ),
            state.timeSlots.render(timeSlots =>
              <.div(^.className := "staffing-container",
                <.div(^.className := "staffing-controls",
                  maybeClockChangeDate(viewingDate).map { clockChangeDate =>
                    val prettyDate = s"${clockChangeDate.getDate} ${clockChangeDate.getMonthString}"
                    <.div(^.className := "staff-daylight-month-warning", MuiGrid(container = true, direction = "column", spacing = 1)(
                      MuiGrid(item = true)(<.span(s"BST is changing to GMT on $prettyDate", ^.style := js.Dictionary("fontWeight" -> "bold"))),
                      MuiGrid(item = true)(<.span("Please ensure no staff are entered in the cells with a dash '-'. They are there to enable you to " +
                        s"allocate staff in the additional hour on $prettyDate.")),
                      MuiGrid(item = true)(<.span("If pasting from TAMS, " +
                        "one solution is to first paste into a separate spreadsheet, then copy and paste the first 2 hours, and " +
                        "then the rest of the hours in 2 separate steps", ^.style := js.Dictionary("marginBottom" -> "15px", "display" -> "block")))
                    ))
                  },
                  MonthlyStaffingBar(
                    viewingDate = viewingDate,
                    terminalPageTab = props.terminalPageTab,
                    router = props.router,
                    airportConfig = props.airportConfig,
                    timeSlots = timeSlots,
                    handleShiftEditForm = handleShiftEditForm,
                    confirmAndSave = ConfirmAndSaveForMonthlyStaffing(viewingDate, timeSlots, props, state, scope)
                  ),
                  MuiSwipeableDrawer(open = state.showEditStaffForm,
                    anchor = "right",
                    PaperProps = js.Dynamic.literal(
                      "style" -> js.Dynamic.literal(
                        "width" -> "400px",
                        "transform" -> "translateY(-50%)"
                      )
                    ),
                    onClose = (_: ReactEventFromHtml) => Callback {
                      scope.modState(state => state.copy(showEditStaffForm = false)).runNow()
                    },
                    onOpen = (_: ReactEventFromHtml) => Callback {})(
                    <.div(UpdateStaffForTimeRangeForm(IUpdateStaffForTimeRangeForm(
                      ustd = IUpdateStaffForTimeRangeData(startDayAt = Moment.utc(), startTimeAt = Moment.utc(), endTimeAt = Moment.utc(), endDayAt = Moment.utc(), actualStaff = "0"),
                      interval = props.timeSlotMinutes,
                      handleSubmit = (ssf: IUpdateStaffForTimeRangeData) => {
                        if (props.isStaffShiftPage)
                          SPACircuit.dispatch(UpdateStaffShifts(staffAssignmentsFromForm(ssf, props.terminalPageTab.terminal)))
                        else
                          SPACircuit.dispatch(UpdateShifts(staffAssignmentsFromForm(ssf, props.terminalPageTab.terminal)))
                        scope.modState(state => {
                          val newState = state.copy(showEditStaffForm = false, showStaffSuccess = true)
                          newState
                        }).runNow()
                      },
                      cancelHandler = () => {
                        scope.modState(state => state.copy(showEditStaffForm = false)).runNow()
                      })))),
                  <.div(^.className := "staffing-table",
                    state.shiftsLastLoaded.map(lastLoaded =>
                      <.div(^.className := "staffing-table-content",
                        HotTable(HotTable.Props(
                          timeSlots,
                          colHeadings = state.colHeadings.map(h => s"<div style='text-align: left;'>${h.day}<br>${h.dayOfWeek}</div>"),
                          rowHeadings = state.rowHeadings,
                          changeCallback = (row, col, value) => {
                            scope.modState { state =>
                              state.copy(changes = state.changes.updated(TimeSlotDay(row, col).key, value))
                            }.runNow()
                          },
                          lastDataRefresh = lastLoaded
                        ))
                      )
                    ),
                    <.div(^.className := "terminal-staffing-content-header",
                      MuiButton(color = Color.primary, variant = "contained")
                      (<.span(^.style := js.Dictionary("paddingLeft" -> "5px"), "Save staff updates"),
                        ^.onClick ==> confirmAndSaveStaffing(viewingDate, timeSlots, props, state, scope))
                    )
                  )
                )
              )
            ))
        } else {
          <.div(^.style := js.Dictionary("padding-top" -> "10px"), AddShiftBarComponent(IAddShiftBarComponentProps(() => {
            props.router.set(TerminalPageTabLoc(props.terminalPageTab.terminalName, "shifts", "createShifts")).runNow()
          })))
        },
      )
    }
  }

  private def staffAssignmentsFromForm(ssf: IUpdateStaffForTimeRangeData, terminal: Terminal): Seq[StaffAssignment] = {
    val startDayLocal = LocalDate(ssf.startDayAt.year(), ssf.startDayAt.month() + 1, ssf.startDayAt.date())
    val endDayLocal = LocalDate(ssf.endDayAt.year(), ssf.endDayAt.month() + 1, ssf.endDayAt.date())

    val startHour = ssf.startTimeAt.hour()
    val startMinute = ssf.startTimeAt.minute()

    val endHour = ssf.endTimeAt.hour()
    val endMinute = ssf.endTimeAt.minute()

    DateRange(startDayLocal, endDayLocal).map { date =>
      val startDateTime = SDate(date.year, date.month, date.day, startHour, startMinute)
      val endDateTime = SDate(date.year, date.month, date.day, endHour, endMinute)
      StaffAssignment(startDateTime.toISOString, terminal, startDateTime.millisSinceEpoch, endDateTime.millisSinceEpoch, ssf.actualStaff.toInt, None)
    }
  }

  val component: Component[Props, State, Backend, CtorType.Props] = ScalaComponent.builder[Props]("MonthShiftStaffing")
    .initialStateFromProps(stateFromProps)
    .renderBackend[Backend]
    .configure(Reusability.shouldComponentUpdate)
    .build

  def updatedShiftAssignments(changes: Map[(Int, Int), Int],
                              viewingDate: SDateLike,
                              terminalName: Terminal,
                              timeSlotMinutes: Int,
                              dayRange: String
                             ): Seq[StaffAssignment] = changes.toSeq.map {
    case ((slotIdx, dayIdx), staff) =>
      val timeSlots: Seq[Seq[Option[SDateLike]]] =
        dayRange match {
          case "monthly" => daysInMonthByTimeSlot((viewingDate, timeSlotMinutes))
          case "weekly" => daysInWeekByTimeSlot((viewingDate, timeSlotMinutes))
          case "daily" => dayTimeSlot((viewingDate, timeSlotMinutes)).map(Seq(_))
        }
      timeSlots(slotIdx)(dayIdx).map((slotStart: SDateLike) => {
        val startMd = slotStart.millisSinceEpoch
        val endMd = slotStart.addMinutes(timeSlotMinutes - 1).millisSinceEpoch
        StaffAssignment(slotStart.toISOString, terminalName, startMd, endMd, staff, None)
      })
  }.collect {
    case Some(a) => a
  }

  private def stateFromProps(props: Props): State = {
    import drt.client.services.JSDateConversions._

    val viewingDate = props.terminalPageTab.dateFromUrlOrNow

    val daysInDayRange: Seq[(SDateLike, String)] = props.terminalPageTab.dayRangeType match {
      case Some("weekly") => consecutiveDayForWeek(viewingDate)
      case Some("daily") => dateRangeDays(viewingDate, 1)
      case _ => consecutiveDaysInMonth(SDate.firstDayOfMonth(viewingDate), SDate.lastDayOfMonth(viewingDate))
    }

    val dayForRowLabels = if (viewingDate.getMonth != 10)
      viewingDate.startOfTheMonth
    else
      SDate.lastDayOfMonth(viewingDate).getLastSunday

    val rowHeadings = slotsInDay(dayForRowLabels, props.timeSlotMinutes).map(_.prettyTime)

    State(Empty,
      daysInDayRange.map(a => ColumnHeader(a._1.getDate.toString, a._2.substring(0, 3))),
      rowHeadings, Map.empty,
      showEditStaffForm = false,
      showStaffSuccess = false,
      addShiftForm = false,
      ShiftAssignments.empty,
      None)
  }

  def apply(terminalPageTab: TerminalPageTabLoc,
            router: RouterCtl[Loc],
            airportConfig: AirportConfig,
            showShiftsStaffing: Boolean,
            userPreferences: UserPreferences
           ): Unmounted[Props, State, Backend] = component(Props(terminalPageTab, router, airportConfig, showShiftsStaffing, userPreferences))
}
