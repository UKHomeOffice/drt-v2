package drt.client.components

import diode.AnyAction.aType
import diode.data.Pot
import drt.client.SPAMain.{Loc, TerminalPageTabLoc, UrlDateParameter, UrlDayRangeType}
import drt.client.actions.Actions.UpdateStaffShifts
import drt.client.components.StaffingUtil.{consecutiveDayForWeek, consecutiveDaysInMonth, dateRangeDays, navigationDates}
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.modules.GoogleEventTracker
import drt.client.services.JSDateConversions.SDate
import drt.client.services.{JSDateConversions, SPACircuit}
import drt.client.util.DateRange
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
import uk.gov.homeoffice.drt.time.{LocalDate, SDateLike}

import scala.collection.mutable
import scala.scalajs.js
import scala.scalajs.js.Date
import scala.util.Try


object MonthlyShifts {

//  case class TimeSlotDay(timeSlot: Int, day: Int) {
//    def key: (Int, Int) = (timeSlot, day)
//  }

  case class ColumnHeader(day: String, dayOfWeek: String)

  case class State(
                    //                    timeSlots: Pot[Seq[Seq[Any]]],
                    //                   colHeadings: Seq[ColumnHeader],
                    //                   rowHeadings: Seq[String],
                    //                   changes: Map[(Int, Int), Int],
                    showEditStaffForm: Boolean,
                    showStaffSuccess: Boolean,
                    addShiftForm: Boolean,
                    shifts: ShiftAssignments,
                    shiftsLastLoaded: Option[Long] = None,
                    shiftsData: Seq[ShiftData] = Seq.empty,
                    changedAssignments: Seq[ShiftAssignment] = Seq.empty
                  )

  val log: Logger = LoggerFactory.getLogger(getClass.getName)

  case class Props(terminalPageTab: TerminalPageTabLoc,
                   router: RouterCtl[Loc],
                   airportConfig: AirportConfig,
                   enableStaffPlanningChanges: Boolean,
                   staffShifts: Seq[StaffShift]
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

    def generateShiftData(viewingDate: SDateLike, terminal: Terminal, staffShifts: Seq[StaffShift], shifts: ShiftAssignments, interval: Int): Seq[ShiftData] = {
      println("generateShiftData ...")

      def daysInMonth(year: Int, month: Int): Int = {
        val date = new Date(year, month, 0)
        date.getDate().toInt
      }

      val month = viewingDate.getMonth
      val year = viewingDate.getFullYear
      val numberOfDaysInMonth = daysInMonth(year, month)
      println("month - year", month, year)
      println("numberOfDaysInMonth", numberOfDaysInMonth)
      val shiftsData: Seq[ShiftData] = staffShifts.zipWithIndex.map { case (s, index) =>
        ShiftData(
          index = index,
          defaultShift = DefaultShift(s.shiftName, s.staffNumber, s.startTime, s.endTime),
          assignments = (1 to numberOfDaysInMonth).flatMap { day =>
            val Array(startHour, startMinute) = s.startTime.split(":").map(_.toInt)
            val Array(endHour, endMinute) = s.endTime.split(":").map(_.toInt)
            val start = SDate(year, month, day, startHour, startMinute).millisSinceEpoch
            val end = SDate(year, month, day, endHour, endMinute).millisSinceEpoch
            val assignments: Seq[StaffAssignment] = StaffAssignment(s.shiftName, terminal, start, end, s.staffNumber, None).splitIntoSlots(interval).sortBy(_.start)
            assignments.zipWithIndex.map { case (a, index) =>
              val startTime = SDate(a.start)
              val endTime = SDate(a.end)
              val matchingAssignments = shifts.assignments.filter(sa => sa.start >= a.start && sa.end <= a.end).sortBy(_.start)
              matchingAssignments.headOption match {
                case Some(sa) =>
                  ShiftAssignment(
                    column = day,
                    row = index + 1, // Start rowId from 1
                    name = sa.name,
                    staffNumber = sa.numberOfStaff,
                    startTime = ShiftDate(startTime.getFullYear, startTime.getMonth, startTime.getDate, startTime.getHours, startTime.getMinutes),
                    endTime = ShiftDate(endTime.getFullYear, endTime.getMonth, endTime.getDate, endTime.getHours, endTime.getMinutes)
                  )
                case None =>
                  ShiftAssignment(
                    column = day,
                    row = index + 1, // Start rowId from 1
                    name = s.shiftName,
                    staffNumber = a.numberOfStaff,
                    startTime = ShiftDate(startTime.getFullYear, startTime.getMonth, startTime.getDate, startTime.getHours, startTime.getMinutes),
                    endTime = ShiftDate(endTime.getFullYear, endTime.getMonth, endTime.getDate, endTime.getHours, endTime.getMinutes)
                  )
              }
            }
          }
        )
      }

//      shiftsData.foreach(sd =>
//        println(sd.index,
//          sd.defaultShift.startTime,
//          sd.defaultShift.name,
//          sd.assignments.length.toString)
//      )
      shiftsData
    }

    def render(props: Props, state: State): VdomTagOf[Div] = {

      val handleShiftEditForm = (e: Event) => Callback {
        e.preventDefault()
        scope.modState(state => state.copy(showEditStaffForm = true)).runNow()
      }

      def handleNavigation(props: Props, viewingDate: SDateLike): VdomTagOf[Div] = {
        val (previousDate, nextDate) = navigationDates(viewingDate, props.terminalPageTab.dayRangeType.getOrElse("monthly"), () => SDate.now())
        navigationArrows(props, previousDate, nextDate)
      }

      def whatDayChanged(changedAssignments: Seq[ShiftAssignment]) = {
        changedAssignments.map(_.startTime.day).distinct.mkString(", ")
      }

      def confirmAndSave(viewingDate: SDateLike, shiftsData: Seq[ShiftData], changedAssignments: Seq[ShiftAssignment]): ReactEventFromInput => Callback = (_: ReactEventFromInput) => Callback {
        println("confirmAndSave ...", shiftsData)
        val changedShifts: Seq[StaffAssignment] = shiftsData.flatMap(_.assignments.toSeq.map(ShiftAssignmentConverter.toStaffAssignment(_, props.terminalPageTab.terminal)))

        val changedShiftSlots: Seq[StaffAssignment] = updatedConvertedShiftAssignments(
          changedShifts,
          viewingDate,
          props.terminalPageTab.terminal,
          props.timeSlotMinutes,
          props.terminalPageTab.dayRangeType.getOrElse("monthly"))
        val updatedMonth = props.terminalPageTab.dateFromUrlOrNow.getMonthString

        if (confirm(s"You have updated staff for ${whatDayChanged(changedAssignments)} $updatedMonth - do you want to save these changes?")) {
          GoogleEventTracker.sendEvent(s"${props.terminalPageTab.terminal}",
            "Save Monthly Staffing",
            s"updated staff for ${whatDayChanged(changedAssignments)} $updatedMonth")
          SPACircuit.dispatch(UpdateStaffShifts(changedShiftSlots))
          scope.modState(state => state.copy(changedAssignments = Seq.empty[ShiftAssignment])).runNow()
        }
      }

      val viewingDate = props.terminalPageTab.dateFromUrlOrNow

      case class Model(monthOfStaffShiftsPot: Pot[ShiftAssignments])
      val staffRCP = SPACircuit.connect(m => Model(m.allStaffShifts))


      val modelChangeDetection = staffRCP { modelMP =>
        val model = modelMP()
        val content = for {
          monthOfShifts <- model.monthOfStaffShiftsPot
        } yield {
          if (monthOfShifts != state.shifts) {
            val initialShift: Seq[ShiftData] = generateShiftData(viewingDate, props.terminalPageTab.terminal, props.staffShifts, monthOfShifts, props.timeSlotMinutes)
            println("initialShift", initialShift)
            scope.modState(state => state.copy(shifts = monthOfShifts,
              shiftsLastLoaded = Option(SDate.now().millisSinceEpoch), shiftsData = initialShift)).runNow()
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
            StaffUpdateSuccess(IStaffUpdateSuccess(0, "The staff numbers have been successfully updated for your chosen dates and times", () => {
              scope.modState(state => state.copy(showStaffSuccess = false)).runNow()
            })) else EmptyVdom,
        ),
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
                    MuiButton(color = Color.primary,
                      variant = "outlined",
                      size = "small",
                      sx = SxProps(Map("backgroundColor" -> "white")))
                    (MuiIcons(Groups)(fontSize = "small"),
                      <.span(^.style := js.Dictionary("paddingLeft" -> "5px"), "Edit staff"),
                      VdomAttr("data-cy") := "edit-staff-button",
                      ^.onClick ==> handleShiftEditForm)
                  else EmptyVdom,
                  MuiButton(color = Color.primary, variant = "contained")
                  (<.span(^.style := js.Dictionary("paddingLeft" -> "5px"), "Save staff updates"),
                    ^.onClick ==> confirmAndSave(viewingDate, state.shiftsData ,state.changedAssignments))
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
              onOpen = (_: ReactEventFromHtml) => Callback {})(
              <.div(UpdateStaffForTimeRangeForm(IUpdateStaffForTimeRangeForm(
                ustd = IUpdateStaffForTimeRangeData(startDayAt = Moment.utc(), startTimeAt = Moment.utc(), endTimeAt = Moment.utc(), endDayAt = Moment.utc(), actualStaff = "0"),
                interval = props.timeSlotMinutes,
                handleSubmit = (ssf: IUpdateStaffForTimeRangeData) => {
                  SPACircuit.dispatch(UpdateStaffShifts(staffAssignmentsFromForm(ssf, props.terminalPageTab.terminal)))
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
                  ShiftHotTableViewComponent(ShiftHotTableViewProps(
                    month = viewingDate.getMonth,
                    year = viewingDate.getFullYear,
                    interval = props.timeSlotMinutes,
                    initialShifts = state.shiftsData,
                    handleSaveChanges = (shifts: Seq[ShiftData], changedAssignments:Seq[ShiftAssignment]) => {
                      println("handleSaveChanges shifts...", shifts)
                      println("handleSaveChanges changedAssignments...", state.changedAssignments ++ changedAssignments)
                      scope.modState(state => state.copy(shiftsData = shifts, changedAssignments = state.changedAssignments ++ changedAssignments)).runNow()
                    }))
                )
              ),
              <.div(^.className := "terminal-staffing-content-header",
                MuiButton(color = Color.primary, variant = "contained")
                (<.span(^.style := js.Dictionary("paddingLeft" -> "5px"), "Save staff updates"),
                  ^.onClick ==> confirmAndSave(viewingDate, state.shiftsData, state.changedAssignments))
              )
            )
          )
        )
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


  val component: Component[Props, State, Backend, CtorType.Props] = ScalaComponent.builder[Props]("ShiftStaffing")
    .initialStateFromProps(stateFromProps)
    .renderBackend[Backend]
    .configure(Reusability.shouldComponentUpdate)
    .build


  def updatedConvertedShiftAssignments(changes: Seq[StaffAssignment],
                                       viewingDate: SDateLike,
                                       terminalName: Terminal,
                                       timeSlotMinutes: Int,
                                       dayRange: String
                                      ): Seq[StaffAssignment] = changes.map { change =>
    //    val timeSlots: Seq[Seq[Option[SDateLike]]] =
    //      dayRange match {
    //        case "monthly" => daysInMonthByTimeSlot((viewingDate, timeSlotMinutes))
    //        case "weekly" => daysInWeekByTimeSlot((viewingDate, timeSlotMinutes))
    //        case "daily" => dayTimeSlot((viewingDate, timeSlotMinutes)).map(Seq(_))
    //      }
    val startMd = change.start
    val endMd = SDate(startMd).addMinutes(timeSlotMinutes - 1).millisSinceEpoch

    //        val startMd = slotStart.millisSinceEpoch
    //        val endMd = slotStart.addMinutes(timeSlotMinutes - 1).millisSinceEpoch
    StaffAssignment(change.name, terminalName, startMd, endMd, change.numberOfStaff, None)
  }


  //  def updatedShiftAssignments(changes: Map[(Int, Int), Int],
  //                              viewingDate: SDateLike,
  //                              terminalName: Terminal,
  //                              timeSlotMinutes: Int,
  //                              dayRange: String
  //                             ): Seq[StaffAssignment] = changes.toSeq.map {
  //    case ((slotIdx, dayIdx), staff) =>
  //      val timeSlots: Seq[Seq[Option[SDateLike]]] =
  //        dayRange match {
  //          case "monthly" => daysInMonthByTimeSlot((viewingDate, timeSlotMinutes))
  //          case "weekly" => daysInWeekByTimeSlot((viewingDate, timeSlotMinutes))
  //          case "daily" => dayTimeSlot((viewingDate, timeSlotMinutes)).map(Seq(_))
  //        }
  //      timeSlots(slotIdx)(dayIdx).map((slotStart: SDateLike) => {
  //        val startMd = slotStart.millisSinceEpoch
  //        val endMd = slotStart.addMinutes(timeSlotMinutes - 1).millisSinceEpoch
  //        StaffAssignment(slotStart.toISOString, terminalName, startMd, endMd, staff, None)
  //      })
  //  }.collect {
  //    case Some(a) => a
  //  }

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

    State(
      //      Empty,
      //      daysInDayRange.map(a => ColumnHeader(a._1.getDate.toString, a._2.substring(0, 3))),
      //      rowHeadings, Map.empty,
      showEditStaffForm = false,
      showStaffSuccess = false,
      addShiftForm = false,
      shifts = ShiftAssignments.empty,
      None)
  }

  def apply(terminalPageTab: TerminalPageTabLoc,
            router: RouterCtl[Loc],
            airportConfig: AirportConfig,
            enableStaffPlanningChange: Boolean,
            staffShifts: Seq[StaffShift]
           ): Unmounted[Props, State, Backend] = component(Props(terminalPageTab, router, airportConfig, enableStaffPlanningChange, staffShifts))
}
