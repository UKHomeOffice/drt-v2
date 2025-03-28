package drt.client.components

import diode.AnyAction.aType
import diode.data.{Empty, Pot, Ready}
import drt.client.SPAMain.{Loc, ShiftViewDisabled, TerminalPageTabLoc}
import drt.client.actions.Actions.{UpdateShifts, UpdateStaffShifts}
import drt.client.components.StaffingUtil.{consecutiveDayForWeek, consecutiveDaysInMonth, dateRangeDays}
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.services.JSDateConversions.SDate
import drt.client.services.{JSDateConversions, SPACircuit}
import drt.client.util.DateRange
import drt.shared._
import io.kinoplan.scalajs.react.material.ui.core.MuiButton.Color
import io.kinoplan.scalajs.react.material.ui.core.system.SxProps
import io.kinoplan.scalajs.react.material.ui.core._
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

import scala.collection.mutable
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
                   hideAddShifts: Boolean,
                   isStaffShiftPage: Boolean,
                   isShiftsEmpty: Boolean
                  ) {
    def timeSlotMinutes: Int = Try(terminalPageTab.subMode.toInt).toOption.getOrElse(60)

  }

  def slotsInDay(date: SDateLike, slotDurationMinutes: Int): Seq[SDateLike] = {
    val startOfDay = SDate.midnightOf(date)
    val slots = minutesInDay(date) / slotDurationMinutes
    List.tabulate(slots)(i => {
      val minsToAdd = i * slotDurationMinutes
      startOfDay.addMinutes(minsToAdd)
    })
  }

  def timeZoneSafeTimeSlots(slots: Seq[SDateLike], slotMinutes: Int): Seq[Option[SDateLike]] = {
    val slotsPerHour = 60 / slotMinutes
    val slotsInRegularDay = slotsPerHour * 24

    if (itsARegularDayInOctober(slots, slotsInRegularDay)) {
      handleBstToUtcChange(slots, slotsPerHour)
    }
    else if (itsTheDayWeSwitchToBst(slots, slotsInRegularDay))
      handleUtcToBstDay(slots, slotsPerHour)
    else
      slots.map(Option(_))
  }

  private def itsTheDayWeSwitchToBst(slots: Seq[SDateLike], slotsInRegularDay: Int): Boolean = slots.size < slotsInRegularDay

  private def itsARegularDayInOctober(slots: Seq[SDateLike], slotsInRegularDay: Int): Boolean =
    slots.head.getMonth == 10 && slots.size == slotsInRegularDay

  private def handleBstToUtcChange(slots: Seq[SDateLike], slotsPerHour: Int): Seq[Option[SDateLike]] =
    slots.map(Option(_)).patch(2 * slotsPerHour, List.fill(slotsPerHour)(None), 0)

  private def handleUtcToBstDay(slots: Seq[SDateLike], slotsPerHour: Int): Seq[Option[SDateLike]] =
    slots.sliding(2).flatMap(dates =>
      if (dates.head.getTimeZoneOffsetMillis < dates(1).getTimeZoneOffsetMillis)
        Option(dates.head) :: List.fill(slotsPerHour)(None)
      else
        Option(dates.head) :: Nil
    ).toSeq ++ Seq(Option(slots.last))

  private def minutesInDay(date: SDateLike): Int = {
    val startOfDay = SDate.midnightOf(date)
    val endOfDay = SDate.midnightOf(date.addDays(1))
    (endOfDay.millisSinceEpoch - startOfDay.millisSinceEpoch).toInt / 1000 / 60
  }

  def applyRecordedChangesToShiftState(staffTimeSlotDays: Seq[Seq[Any]], changes: Map[(Int, Int), Int]): Seq[Seq[Any]] =
    changes.foldLeft(staffTimeSlotDays) {
      case (staffSoFar, ((slotIdx, dayIdx), newStaff)) =>
        staffSoFar.updated(slotIdx, staffSoFar(slotIdx).updated(dayIdx, newStaff))
    }

  def whatDayChanged(startingSlots: Seq[Seq[Any]], updatedSlots: Seq[Seq[Any]]): Set[Int] =
    toDaysWithIndexSet(updatedSlots)
      .diff(toDaysWithIndexSet(startingSlots)).map { case (_, dayIndex) => dayIndex }

  private def toDaysWithIndexSet(updatedSlots: Seq[Seq[Any]]): Set[(Seq[Any], Int)] = updatedSlots
    .transpose
    .zipWithIndex
    .toSet

  def dateListToString(dates: List[String]): String = dates.map(_.toInt).sorted match {
    case Nil => ""
    case head :: Nil => head.toString
    case dateList => dateList.dropRight(1).mkString(", ") + " and " + dateList.last
  }

  def getQuarterHourlySlotChanges(timeSlotMinutes: Int, changes: Map[(Int, Int), Int]): Map[(Int, Int), Int] = {
    timeSlotMinutes match {
      case 15 => changes
      case 30 => halfHourlyToQuarterHourlySlots(changes)
      case 60 => hourlyToQuarterHourlySlots(changes)
    }
  }

  implicit val propsReuse: Reusability[Props] = Reusability((a, b) => a == b)
  implicit val stateReuse: Reusability[State] = Reusability((a, b) => a == b)

  class Backend(scope: BackendScope[Props, State]) {
    def render(props: Props, state: State): VdomTagOf[Div] = {

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
      val shiftViewDisabled = props.terminalPageTab.queryParams.get(ShiftViewDisabled.paramName).exists(_.toBoolean)

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
                    defaultChecked = !shiftViewDisabled,
                    color = Color.primary,
                    inputProps = js.Dynamic.literal("aria-label" -> "primary checkbox"),
                  )(^.onChange --> props.router.set(props.terminalPageTab.withUrlParameters(ShiftViewDisabled(!shiftViewDisabled)))),
                ), MuiTypography(sx = SxProps(Map("paddingRight" -> "10px")))(if (shiftViewDisabled) "Off" else "On")
              ))
          } else EmptyVdom),
        if (shiftViewDisabled) {
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

  private def hourlyToQuarterHourlySlots(slots: Map[(Int, Int), Int]): Map[(Int, Int), Int] = slots.flatMap {
    case ((slotIdx, dayIdx), staff) =>
      (0 to 3).map(offset => (((slotIdx * 4) + offset, dayIdx), staff))
  }

  private def halfHourlyToQuarterHourlySlots(slots: Map[(Int, Int), Int]): Map[(Int, Int), Int] = slots.flatMap {
    case ((slotIdx, dayIdx), staff) =>
      (0 to 1).map(offset => (((slotIdx * 2) + offset, dayIdx), staff))
  }

  private def memoize[I, O](f: I => O): I => O = new mutable.HashMap[I, O]() {
    override def apply(key: I): O = getOrElseUpdate(key, f(key))
  }

  lazy val dayTimeSlot: ((SDateLike, Int)) => Seq[Option[SDateLike]] = memoize {
    case (viewingDate, timeSlotMinutes) => timeZoneSafeTimeSlots(slotsInDay(viewingDate, timeSlotMinutes), timeSlotMinutes)
  }

  lazy val daysInWeekByTimeSlot: ((SDateLike, Int)) => Seq[Seq[Option[SDateLike]]] = memoize {
    case (viewingDate, timeSlotMinutes) => daysInWeekByTimeSlotCalc(viewingDate, timeSlotMinutes)
  }

  private def daysInWeekByTimeSlotCalc(viewingDate: SDateLike, timeSlotMinutes: Int): Seq[Seq[Option[SDateLike]]] = {
    val daysInWeek: Seq[Seq[Option[SDateLike]]] = StaffingUtil.consecutiveDayForWeek(viewingDate)
      .map { day =>
        timeZoneSafeTimeSlots(
          slotsInDay(day._1, timeSlotMinutes),
          timeSlotMinutes
        )
      }

    val maxLength = daysInWeek.map(_.length).max

    val paddedDaysInWeek = daysInWeek.map { seq =>
      seq.padTo(maxLength, None)
    }

    try {
      paddedDaysInWeek.transpose
    } catch {
      case e: Throwable =>
        throw e
    }

  }

  lazy val daysInMonthByTimeSlot: ((SDateLike, Int)) => Seq[Seq[Option[SDateLike]]] = memoize {
    case (viewingDate, timeSlotMinutes) => daysInMonthByTimeSlotCalc(viewingDate, timeSlotMinutes)
  }

  private def daysInMonthByTimeSlotCalc(viewingDate: SDateLike, timeSlotMinutes: Int): Seq[Seq[Option[SDateLike]]] =
    consecutiveDaysInMonth(SDate.firstDayOfMonth(viewingDate), SDate.lastDayOfMonth(viewingDate))
      .map { day =>
        timeZoneSafeTimeSlots(
          slotsInDay(day._1, timeSlotMinutes),
          timeSlotMinutes
        )
      }
      .transpose

  private def maybeClockChangeDate(viewingDate: SDateLike): Option[SDateLike] = {
    val lastDay = SDate.lastDayOfMonth(viewingDate)
    (0 to 10).map(offset => lastDay.addDays(-1 * offset)).find(date => slotsInDay(date, 60).length == 25)
  }

  def slotsFromShifts(shifts: StaffAssignmentsLike, terminal: Terminal, viewingDate: SDateLike, timeSlotMinutes: Int, dayRange: String): Seq[Seq[Any]] =
    dayRange match {
      case "monthly" => daysInMonthByTimeSlot((viewingDate, timeSlotMinutes)).map(_.map {
        case Some(slotDateTime) =>
          shifts.terminalStaffAt(terminal, slotDateTime, JSDateConversions.longToSDateLocal)
        case None => "-"
      })

      case "weekly" => daysInWeekByTimeSlot((viewingDate, timeSlotMinutes)).map(_.map {
        case Some(slotDateTime) =>
          shifts.terminalStaffAt(terminal, slotDateTime, JSDateConversions.longToSDateLocal)
        case None => "-"
      })

      case "daily" => dayTimeSlot((viewingDate, timeSlotMinutes)).map {
        case Some(slotDateTime) =>
          shifts.terminalStaffAt(terminal, slotDateTime, JSDateConversions.longToSDateLocal)
        case None => "-"
      }.map(Seq(_))
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
            hideAddShifts: Boolean,
            showShiftsStaffing: Boolean,
            isShiftEmpty: Boolean
           ): Unmounted[Props, State, Backend] = component(Props(terminalPageTab, router, airportConfig, hideAddShifts, showShiftsStaffing, isShiftEmpty))
}
