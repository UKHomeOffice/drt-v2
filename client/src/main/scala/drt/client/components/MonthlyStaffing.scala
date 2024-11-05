package drt.client.components

import diode.data.{Empty, Pot, Ready}
import drt.client.SPAMain.{Loc, TerminalPageTabLoc, UrlDateParameter, UrlDayRangeType}
import drt.client.actions.Actions.UpdateShifts
import drt.client.components.StaffingUtil.{consecutiveDayForWeek, consecutiveDaysInMonth, dateRangeDays, navigationDates}
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.modules.GoogleEventTracker
import drt.client.services.JSDateConversions.SDate
import drt.client.services.{JSDateConversions, SPACircuit}
import drt.shared._
import io.kinoplan.scalajs.react.material.ui.core.MuiButton.Color
import io.kinoplan.scalajs.react.material.ui.core.system.SxProps
import io.kinoplan.scalajs.react.material.ui.core.{MuiButton, MuiGrid, MuiSwipeableDrawer}
import io.kinoplan.scalajs.react.material.ui.icons.MuiIcons
import io.kinoplan.scalajs.react.material.ui.icons.MuiIconsModule.{ChevronLeft, ChevronRight, Groups}
import japgolly.scalajs.react.callback.Callback
import japgolly.scalajs.react.component.Scala.{Component, Unmounted}
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.HtmlAttrs.onClick.Event
import japgolly.scalajs.react.vdom.TagOf
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{CtorType, _}
import moment.Moment
import org.scalajs.dom.html.{Div, Select}
import org.scalajs.dom.window.confirm
import uk.gov.homeoffice.drt.ports.AirportConfig
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.SDateLike

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
                   shifts: ShiftAssignments,
                   shiftsLastLoaded: Option[Long] = None,
                  )

  val log: Logger = LoggerFactory.getLogger(getClass.getName)

  case class Props(terminalPageTab: TerminalPageTabLoc,
                   router: RouterCtl[Loc],
                   airportConfig: AirportConfig,
                   enableStaffPlanningChanges: Boolean
                  ) {
    def timeSlotMinutes: Int = Try(terminalPageTab.subMode.toInt).toOption.getOrElse(60)

    def dayRangeType: String = terminalPageTab.dayRangeType match {
      case Some(dayRange) => dayRange
      case None => "monthly"
    }
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

  def drawSelect(
                  values: Seq[String],
                  names: Seq[String],
                  defaultValue: String,
                  callback: ReactEventFromInput => Callback
                ): TagOf[Select] = {
    val valueNames = values.zip(names)
    <.select(^.className := "form-control dynamic-width", ^.defaultValue := defaultValue,
      ^.onChange ==> callback,
      valueNames.map {
        case (value, name) => <.option(^.value := value, s"$name")
      }.toTagMod)
  }


  def sixMonthsFromFirstOfMonth(date: SDateLike): Seq[SDateLike] = (0 to 5)
    .map(i => SDate.firstDayOfMonth(date).addMonths(i))

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

  private val monthOptions: Seq[SDateLike] = sixMonthsFromFirstOfMonth(SDate.now())

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
      val handleShiftEditForm = (e: Event) => Callback {
        e.preventDefault()
        scope.modState(state => state.copy(showEditStaffForm = true)).runNow()
      }

      def handleNavigation(props: Props, viewingDate: SDateLike): VdomTagOf[Div] = {
        val (previousDate, nextDate) = navigationDates(viewingDate, props.terminalPageTab.dayRangeType.getOrElse("monthly"), () => SDate.now())
        navigationArrows(props, previousDate, nextDate)
      }

      def confirmAndSave(viewingDate: SDateLike, timeSlots: Seq[Seq[Any]]): ReactEventFromInput => Callback = (_: ReactEventFromInput) =>
        Callback {
          val initialTimeSlots: Seq[Seq[Any]] = slotsFromShifts(state.shifts,
            props.terminalPageTab.terminal,
            viewingDate,
            props.timeSlotMinutes,
            props.terminalPageTab.dayRangeType.getOrElse("monthly"))

          val quarterHourlyChanges = getQuarterHourlySlotChanges(props.timeSlotMinutes, state.changes)
          val updatedTimeSlots: Seq[Seq[Any]] = applyRecordedChangesToShiftState(timeSlots, state.changes)
          val saveAsTimeSlotMinutes = 15

          val changedShiftSlots: Seq[StaffAssignment] = updatedShiftAssignments(
            quarterHourlyChanges,
            viewingDate,
            props.terminalPageTab.terminal,
            saveAsTimeSlotMinutes,
            props.terminalPageTab.dayRangeType.getOrElse("monthly"))

          val updatedMonth = props.terminalPageTab.dateFromUrlOrNow.getMonthString
          val changedDays = whatDayChanged(initialTimeSlots, updatedTimeSlots).map(d => state.colHeadings(d)).toList

          if (confirm(s"You have updated staff for ${dateListToString(changedDays.map(_.day))} $updatedMonth - do you want to save these changes?")) {
            GoogleEventTracker.sendEvent(s"${props.terminalPageTab.terminal}",
              "Save Monthly Staffing",
              s"updated staff for ${dateListToString(changedDays.map(_.day))} $updatedMonth")
            SPACircuit.dispatch(UpdateShifts(changedShiftSlots))
          }
        }

      val viewingDate = props.terminalPageTab.dateFromUrlOrNow

      case class Model(monthOfShiftsPot: Pot[ShiftAssignments])
      val staffRCP = SPACircuit.connect(m => Model(m.allShifts))

      val modelChangeDetection = staffRCP { modelMP =>
        val model = modelMP()
        val content = for {
          monthOfShifts <- model.monthOfShiftsPot
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
        modelChangeDetection,
        <.div(
          if (state.showStaffSuccess)
            StaffUpdateSuccess(IStaffSuccess(0, "The staff numbers have been successfully updated for your chosen dates and times", () => {
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
              <.div(^.className := "staffing-controls-save",
                <.div(^.style := js.Dictionary("display" -> "flex", "justify-content" -> "space-between", "align-items" -> "center"),
                  <.span(^.className := "staffing-controls-title",
                    <.strong(props.terminalPageTab.dayRangeType match {
                      case Some("monthly") => s"Staff numbers: ${viewingDate.getMonthString} ${viewingDate.getFullYear}"
                      case Some("weekly") =>
                        val firstDayOfWeek = SDate.firstDayOfWeek(viewingDate)
                        val lastDayOfWeek = SDate.lastDayOfWeek(viewingDate)
                        if (firstDayOfWeek.getFullYear == lastDayOfWeek.getFullYear) {
                          val length = firstDayOfWeek.`shortDayOfWeek-DD-MMM-YYYY`.length
                          s"Staff numbers: ${firstDayOfWeek.`shortDayOfWeek-DD-MMM-YYYY`.substring(0, length - 4)} to ${SDate.lastDayOfWeek(viewingDate).`shortDayOfWeek-DD-MMM-YYYY`}"
                        } else
                          s"Staff numbers: ${SDate.firstDayOfWeek(viewingDate).`shortDayOfWeek-DD-MMM-YYYY`} to ${SDate.lastDayOfWeek(viewingDate).`shortDayOfWeek-DD-MMM-YYYY`}"
                      case Some("daily") => s"Staff numbers: ${viewingDate.`dayOfWeek-DD-MMM-YYYY`}"
                      case _ => s"Staff numbers in ${viewingDate.getMonthString} ${viewingDate.getFullYear}"
                    })),
                  <.span(^.className := "staffing-controls-title-options",
                    if (props.enableStaffPlanningChanges)
                      <.div(^.className := "staffing-controls-select",
                        drawSelect(
                          values = Seq("monthly", "weekly", "daily"),
                          names = Seq("View: Monthly", "View: Weekly", "View: Daily"),
                          defaultValue = s"${props.dayRangeType}",
                          callback = (e: ReactEventFromInput) =>
                            props.router.set(props.terminalPageTab.withUrlParameters(UrlDayRangeType(Some(e.target.value))))
                        )
                      ) else EmptyVdom,
                    if (props.dayRangeType != "weekly" && props.dayRangeType != "daily") {
                      <.div(^.className := "staffing-controls-select",
                        drawSelect(
                          values = monthOptions.map(_.toISOString),
                          names = monthOptions.map(d => s"${d.getMonthString} ${d.getFullYear}"),
                          defaultValue = SDate.firstDayOfMonth(viewingDate).toISOString,
                          callback = (e: ReactEventFromInput) => {
                            props.router.set(props.terminalPageTab.withUrlParameters(UrlDateParameter(Option(SDate(e.target.value).toISODateOnly))))
                          }
                        ))
                    } else EmptyVdom,
                    if (props.enableStaffPlanningChanges) <.div(^.className := "staffing-controls-navigation ",
                      handleNavigation(props, viewingDate)
                    ) else EmptyVdom,
                    <.div(^.className := "staffing-controls-select",
                      drawSelect(
                        values = Seq("15", "30", "60"),
                        names = Seq("Display: Every 15 mins", "Display: Every 30 mins", "Display: Hourly"),
                        defaultValue = s"${props.timeSlotMinutes}",
                        callback = (e: ReactEventFromInput) =>
                          props.router.set(props.terminalPageTab.copy(subMode = s"${e.target.value}"))
                      )
                    ),
                    if (props.enableStaffPlanningChanges)
                      MuiButton(color = Color.primary, variant = "outlined", size = "small", sx = SxProps(Map("backgroundColor" -> "white")))
                      (MuiIcons(Groups)(fontSize = "small"),
                        <.span(^.style := js.Dictionary("paddingLeft" -> "5px"), "Edit staff"),
                        ^.onClick ==> handleShiftEditForm)
                    else EmptyVdom,
                    MuiButton(color = Color.primary, variant = "contained")
                    (<.span(^.style := js.Dictionary("paddingLeft" -> "5px"), "Save staff updates"),
                      ^.onClick ==> confirmAndSave(viewingDate, timeSlots))
                  ))
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
                onOpen = (_: ReactEventFromHtml) => Callback {
                  print("open drawer")
                })(
                <.div(UpdateStaffForTimeRangeForm(IEditShiftStaffForm(
                  editShiftStaff = IEditShiftStaff(startDayAt = Moment.utc(), startTimeAt = Moment.utc(), endTimeAt = Moment.utc(), endDayAt = Moment.utc(), actualStaff = "0"),
                  interval = props.timeSlotMinutes,
                  handleSubmit = (ssf: IEditShiftStaff) => {
                    val dayInMilliseconds = 1000 * 60 * 60 * 24
                    val startDay = ssf.startDayAt.utc().toDate().getTime().toLong / dayInMilliseconds // Convert to days
                    val endDay = ssf.endDayAt.utc().toDate().getTime().toLong / dayInMilliseconds // Convert to days
                    val staffAssignments: Seq[StaffAssignment] = (startDay to endDay).map { day =>
                      val dayAt = Moment(day * dayInMilliseconds) // Convert back to milliseconds
                      val ssfDay = IEditShiftStaff(dayAt, ssf.startTimeAt, ssf.endTimeAt, dayAt, ssf.actualStaff)
                      IEditShiftStaff.toStaffAssignment(ssfDay, props.terminalPageTab.terminal)
                    }
                    SPACircuit.dispatch(UpdateShifts(staffAssignments))
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
                ),
                <.div(^.className := "terminal-staffing-content-header",
                  MuiButton(color = Color.primary, variant = "contained")
                  (<.span(^.style := js.Dictionary("paddingLeft" -> "5px"), "Save staff updates"),
                    ^.onClick ==> confirmAndSave(viewingDate, timeSlots))
                )
              )
            )
          )
        )
      )
    }
  }

  private def navigationArrows(props: Props, previousWeekDate: SDateLike, nextWeekDate: SDateLike) = {
    <.div(
      MuiButton(color = Color.primary, variant = "outlined",
        sx = SxProps(Map("height" -> "40px", "backgroundColor" -> "white")))(MuiIcons(ChevronLeft)(fontSize = "medium"),
        ^.onClick --> props.router.set(props.terminalPageTab.withUrlParameters(UrlDateParameter(Some(previousWeekDate.toISODateOnly))))),
      MuiButton(color = Color.primary, variant = "outlined",
        sx = SxProps(Map("height" -> "40px", "backgroundColor" -> "white")))(MuiIcons(ChevronRight)(fontSize = "medium"),
        ^.onClick --> props.router.set(props.terminalPageTab.withUrlParameters(UrlDateParameter(Some(nextWeekDate.toISODateOnly)))))
    )
  }

  val component: Component[Props, State, Backend, CtorType.Props] = ScalaComponent.builder[Props]("StaffingV2")
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

  private def slotsFromShifts(shifts: StaffAssignmentsLike, terminal: Terminal, viewingDate: SDateLike, timeSlotMinutes: Int, dayRange: String): Seq[Seq[Any]] =
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
      ShiftAssignments.empty,
      None)
  }

  def apply(terminalPageTab: TerminalPageTabLoc,
            router: RouterCtl[Loc],
            airportConfig: AirportConfig,
            enableStaffPlanningChange: Boolean
           ): Unmounted[Props, State, Backend] = component(Props(terminalPageTab, router, airportConfig, enableStaffPlanningChange))
}
