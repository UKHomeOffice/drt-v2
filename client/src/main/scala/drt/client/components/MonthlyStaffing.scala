package drt.client.components

import diode.data.{Empty, Pot, Ready}
import drt.client.SPAMain.{Loc, TerminalPageTabLoc, UrlDateParameter}
import drt.client.actions.Actions.UpdateShifts
import drt.client.components.TerminalPlanningComponent.defaultStartDate
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.modules.GoogleEventTracker
import drt.client.services.JSDateConversions.SDate
import drt.client.services.handlers.{SaveMinStaff, TerminalMinStaff}
import drt.client.services.{JSDateConversions, SPACircuit}
import drt.shared._
import io.kinoplan.scalajs.react.material.ui.core.{MuiGrid, MuiSwipeableDrawer}
import japgolly.scalajs.react.{CtorType, _}
import japgolly.scalajs.react.callback.Callback
import japgolly.scalajs.react.component.Scala.{Component, Unmounted}
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.HtmlAttrs.onClick.Event
import japgolly.scalajs.react.vdom.TagOf
import japgolly.scalajs.react.vdom.html_<^._
import moment.Moment
import org.scalajs.dom.html.{Div, Select}
import org.scalajs.dom.window.confirm
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.SDateLike

import scala.collection.mutable
import scala.scalajs.js
import scala.util.Try


object MonthlyStaffing {

  case class TimeSlotDay(timeSlot: Int, day: Int) {
    def key: (Int, Int) = (timeSlot, day)
  }

  case class State(timeSlots: Pot[Seq[Seq[Any]]],
                   colHeadings: Seq[String],
                   rowHeadings: Seq[String],
                   changes: Map[(Int, Int), Int],
                   showEditStaffForm: Boolean,
                   showShiftStaffForm: Boolean,
                   showMinStaffForm: Boolean,
                   showMinStaffSuccess: Boolean,
                   terminalMinStaff: Pot[Option[Int]],
                   shifts: ShiftAssignments,
                   shiftsLastLoaded: Option[Long] = None
                  )

  val log: Logger = LoggerFactory.getLogger(getClass.getName)

  case class Props(portCode: PortCode,
                   terminalPageTab: TerminalPageTabLoc,
                   router: RouterCtl[Loc],
                  ) {
    def timeSlotMinutes: Int = Try(terminalPageTab.subMode.toInt).toOption.getOrElse(15)
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
    <.select(^.className := "form-control", ^.defaultValue := defaultValue,
      ^.onChange ==> callback,
      valueNames.map {
        case (value, name) => <.option(^.value := value, s"$name")
      }.toTagMod)
  }

  def consecutiveDaysInMonth(startDay: SDateLike, endDay: SDateLike): Seq[SDateLike] = {
    val days = (endDay.getDate - startDay.getDate) + 1
    List.tabulate(days)(i => startDay.addDays(i))
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

      def confirmAndSave(startOfMonthMidnight: SDateLike, timeSlots: Seq[Seq[Any]]): ReactEventFromInput => Callback = (_: ReactEventFromInput) =>
        Callback {
          val initialTimeSlots: Seq[Seq[Any]] = slotsFromShifts(state.shifts, props.terminalPageTab.terminal, startOfMonthMidnight, props.timeSlotMinutes)

          val quarterHourlyChanges = getQuarterHourlySlotChanges(props.timeSlotMinutes, state.changes)
          val updatedTimeSlots: Seq[Seq[Any]] = applyRecordedChangesToShiftState(timeSlots, state.changes)
          val saveAsTimeSlotMinutes = 15

          val changedShiftSlots: Seq[StaffAssignment] = updatedShiftAssignments(
            quarterHourlyChanges,
            startOfMonthMidnight,
            props.terminalPageTab.terminal,
            saveAsTimeSlotMinutes)

          val updatedMonth = props.terminalPageTab.dateFromUrlOrNow.getMonthString
          val changedDays = whatDayChanged(initialTimeSlots, updatedTimeSlots).map(d => state.colHeadings(d)).toList

          if (confirm(s"You have updated staff for ${dateListToString(changedDays)} $updatedMonth - do you want to save these changes?")) {
            GoogleEventTracker.sendEvent(s"${props.terminalPageTab.terminal}", "Save Monthly Staffing", s"updated staff for ${dateListToString(changedDays)} $updatedMonth")
            SPACircuit.dispatch(UpdateShifts(changedShiftSlots))
          }
        }

      val viewingDate = SDate.firstDayOfMonth(props.terminalPageTab.dateFromUrlOrNow)

      def saveMinStaff(terminalMinStaff: TerminalMinStaff): Unit = {
        scope.modState(state => state.copy(terminalMinStaff = Ready(terminalMinStaff.minStaff), showMinStaffForm = false, showMinStaffSuccess = true)) >>
          Callback(SPACircuit.dispatch(SaveMinStaff(TerminalMinStaff(props.terminalPageTab.terminal, terminalMinStaff.minStaff))))
      }.runNow()


      val handleOnEdit = (e: Event) => Callback {
        e.preventDefault()
        scope.modState(state => state.copy(showMinStaffForm = true)).runNow()
      }

      case class Model(terminalMinStaffPot: Pot[Option[Int]], monthOfShiftsPot: Pot[ShiftAssignments])
      val minStaffRCP = SPACircuit.connect(m => Model(m.minStaff.map(_.minStaff), m.allShifts))

      val modelChangeDetection = minStaffRCP { modelMP =>
        val model = modelMP()

        val content = for {
          terminalMinStaff <- model.terminalMinStaffPot
          monthOfShifts <- model.monthOfShiftsPot
        } yield {
          if (monthOfShifts != state.shifts || state.timeSlots.isEmpty) {
            val slots = slotsFromShifts(monthOfShifts, props.terminalPageTab.terminal, viewingDate, props.timeSlotMinutes)
            scope.modState(state => state.copy(shifts = monthOfShifts, timeSlots = Ready(slots), shiftsLastLoaded = Option(SDate.now().millisSinceEpoch))).runNow()
          }
          if (terminalMinStaff != state.terminalMinStaff.getOrElse(None)) {
            scope.modState(state => state.copy(terminalMinStaff = Ready(terminalMinStaff))).runNow()
          }
          <.div()
        }
        <.div(content.render(identity))
      }

      <.div(<.input.button(^.value := "staff updates",
        ^.className := "btn btn-primary",
        ^.onClick ==> handleShiftEditForm
      ))
      //      if (state.showShiftStaffForm) {
      //        var formData:IShiftStaffForm = IShiftStaffForm(
      //          port = props.portCode.toString,
      //          terminal = props.terminalPageTab.terminal.toString,
      //          shiftName = "",
      //          startAt = null,
      //          periodInMinutes = 0,
      //          endAt = null,
      //          frequency = null,
      //          actualStaff = 0,
      //          minimumRosteredStaff = 0,
      //          email = "dontknow@email.com")
      //
      //        var resultFormData : IShiftStaffForm = IShiftStaffForm(
      //          port = props.portCode.toString,
      //          terminal = props.terminalPageTab.terminal.toString,
      //          shiftName = "",
      //          startAt = null,
      //          periodInMinutes = 0,
      //          endAt = null,
      //          frequency = null,
      //          actualStaff = 0,
      //          minimumRosteredStaff = 0,
      //          email = "")
      //        <.div(^.className := "",
      //          StaffShiftFormComponent(ShiftStaffFormData(shiftStaffForm = formData,
      //            handleSubmit = (resultFormData) =>  {
      //              println(s"submit ${IShiftStaffForm.toShiftStaffDataJson(resultFormData)}")
      //            },
      //            cancelHandler = () => {
      //              scope.modState(state => state.copy(showShiftStaffForm = false)).runNow()
      //              println("cancel")
      //            })))
      //      } else if (state.showMinStaffForm) {
      //        val message = if (state.terminalMinStaff.getOrElse(None).isEmpty)
      //          "All future dates with 0 staff in the table below will be updated."
      //        else
      //          "All future dates with the previously entered minimum staff number will be updated."
      //
      //        val form: IMinStaffForm = IMinStaffForm(
      //          port = props.portCode.toString,
      //          terminal = props.terminalPageTab.terminal.toString,
      //          message = message,
      //          minStaffNumber = 0,
      //          handleSubmit = minStaff => saveMinStaff(TerminalMinStaff(props.terminalPageTab.terminal, Option(minStaff))),
      //          cancelHandler = () => scope.modState(state => state.copy(showMinStaffSuccess = false, showMinStaffForm = false)).runNow()
      //        )
      //        <.div(^.className := "terminal-staffing-header", MinStaffForm(form))
      //      } else {
      <.div(
        modelChangeDetection,
        //          state.terminalMinStaff.render { minStaff =>
        <.div(
          if (state.showMinStaffSuccess)
            StaffSuccess(IStaffSuccess(0, "You updated the staff number for selected date and time", () => {
              scope.modState(state => state.copy(showMinStaffSuccess = false)).runNow()
            })) else EmptyVdom,
          //              if (minStaff.isEmpty)
          //                <.div(^.className := "terminal-staffing-content-header",
          //                  MinStaffWarning(IMinStaffWarning("Your minimum staff cover in DRT is ",
          //                    "You can now more accurately reflect your minimum staff cover in DRT",
          //                    minStaff, () => {
          //                      scope.modState(state => state.copy(showMinStaffForm = true)).runNow()
          //                    }))
          //                )
          //              else <.div(^.className := "terminal-staffing-header",
          //                <.div(^.className := "terminal-min-staffing", s"Minimum staff: ${minStaff.getOrElse(0)}"),
          //                <.span(s"Update latest min staff ", <.a(^.href := "#", Icon.edit, " Edit", ^.onClick ==> handleOnEdit))
          //              ),
          //              <.div(^.className := "terminal-staffing-content-header",
          //                <.div(^.className := "staffing-controls-wrapper",
          //                  <.div(^.className := "staffing-controls-row",
          //                    <.label("Choose Month", ^.className := "staffing-controls-label"),
          //                    <.div(^.className := "staffing-controls-select",
          //                      drawSelect(
          //                        values = monthOptions.map(_.toISOString),
          //                        names = monthOptions.map(d => s"${d.getMonthString} ${d.getFullYear}"),
          //                        defaultValue = viewingDate.toISOString,
          //                        callback = (e: ReactEventFromInput) => {
          //                          props.router.set(props.terminalPageTab.withUrlParameters(UrlDateParameter(Option(SDate(e.target.value).toISODateOnly))))
          //                        })
          //                    ),
          //                  ),
          //                  <.div(^.className := "staffing-controls-row",
          //                    <.label("Time Resolution", ^.className := "staffing-controls-label"),
          //                    <.div(^.className := "staffing-controls-select",
          //                      drawSelect(
          //                        values = Seq("15", "30", "60"),
          //                        names = Seq("Quarter-hourly", "Half-hourly", "Hourly"),
          //                        defaultValue = s"${props.timeSlotMinutes}",
          //                        callback = (e: ReactEventFromInput) =>
          //                          props.router.set(props.terminalPageTab.copy(subMode = e.target.value))
          //                      )
          //                    ),
          //                  ))
          //              )
        ),
        //          },
        state.timeSlots.render(timeSlots =>
          <.div(
            <.div(^.className := "staffing-controls",
              //                <.div(<.strong(s"Staff numbers in  ${props.terminalPageTab.dateFromUrlOrNow.getMonthString} ${props.terminalPageTab.dateFromUrlOrNow.getFullYear}")),
              //                <.div("Staff numbers can be entered into the table below. Numbers can be duplicated by selecting and dragging a cell."),
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
                <.div(^.style := js.Dictionary("padding-top" -> "5px", "padding-left" -> "10px"),
                  <.strong(s"Staff numbers in ${props.terminalPageTab.dateFromUrlOrNow.getMonthString} ${props.terminalPageTab.dateFromUrlOrNow.getFullYear}")),
                <.div(^.className := "staffing-controls-select",
                  drawSelect(
                    values = monthOptions.map(_.toISOString),
                    names = monthOptions.map(d => s"${d.getMonthString} ${d.getFullYear}"),
                    defaultValue = viewingDate.toISOString,
                    callback = (e: ReactEventFromInput) => {
                      props.router.set(props.terminalPageTab.withUrlParameters(UrlDateParameter(Option(SDate(e.target.value).toISODateOnly))))
                    })
                ),
                <.div(^.className := "staffing-controls-select",
                  drawSelect(
                    values = Seq("15", "30", "60"),
                    names = Seq("Quarter-hourly", "Half-hourly", "Hourly"),
                    defaultValue = s"${props.timeSlotMinutes}",
                    callback = (e: ReactEventFromInput) =>
                      props.router.set(props.terminalPageTab.copy(subMode = e.target.value))
                  )
                ),
                <.input.button(^.value := "Edit staff", ^.className := "btn btn-secondary", ^.onClick ==> handleShiftEditForm),
                <.input.button(^.value := "Save staff updates", ^.className := "btn btn-primary", ^.onClick ==> confirmAndSave(viewingDate, timeSlots)
                ))
            ),
            MuiSwipeableDrawer(open = state.showEditStaffForm,
              anchor = "right",
              PaperProps = js.Dynamic.literal(
                "style" -> js.Dynamic.literal(
                  "width" -> "350px",
                  "height" -> "370px",
                  "top" -> "25%",
                  "transform" -> "translateY(-50%)"
                )
              ),
              onClose = (_: ReactEventFromHtml) => Callback {
                scope.modState(state => state.copy(showEditStaffForm = false)).runNow()
              },
              onOpen = (_: ReactEventFromHtml) => Callback {
                print("open drawer")
              })(
              <.div(EditShiftStaffForm(IEditShiftStaffForm(
                editShiftStaff = IEditShiftStaff(dayAt = Moment.utc(), startTime = Moment.utc(), endTime = js.undefined, actualStaff = js.undefined),
                handleSubmit = (ssf: IEditShiftStaff) => {
                  val shiftAssignment = IEditShiftStaff.toStaffAssignment(ssf, props.terminalPageTab.terminal)
                  println(s"submit ${shiftAssignment}")
                  SPACircuit.dispatch(UpdateShifts(Seq(shiftAssignment)))
                  println("Dispatching UpdateShifts")
                  scope.modState(state => {
                      val newState = state.copy(showEditStaffForm = false, showMinStaffSuccess = true)
                      println("New State: " + newState)
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
                  colHeadings = state.colHeadings,
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
                <.input.button(^.value := "Save staff updates",
                  ^.className := "btn btn-primary",
                  ^.onClick ==> confirmAndSave(viewingDate, timeSlots)
                )
              )
            )
          )
        )
      )
      //      }
    }
  }

  val component: Component[Props, State, Backend, CtorType.Props] = ScalaComponent.builder[Props]("StaffingV2")
    .initialStateFromProps(stateFromProps)
    .renderBackend[Backend]
    .configure(Reusability.shouldComponentUpdate)
    .componentDidMount(p =>
      Callback(GoogleEventTracker.sendPageView(s"${p.props.terminalPageTab.terminal}/planning/${defaultStartDate(p.props.terminalPageTab.dateFromUrlOrNow).toISODateOnly}/${p.props.terminalPageTab.subMode}"))
    )
    .build

  def updatedShiftAssignments(changes: Map[(Int, Int), Int],
                              startOfMonthMidnight: SDateLike,
                              terminalName: Terminal,
                              timeSlotMinutes: Int
                             ): Seq[StaffAssignment] = changes.toSeq.map {
    case ((slotIdx, dayIdx), staff) =>
      val timeSlots = daysInMonthByTimeSlot((startOfMonthMidnight, timeSlotMinutes))

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

  lazy val daysInMonthByTimeSlot: ((SDateLike, Int)) => Seq[Seq[Option[SDateLike]]] = memoize {
    case (viewingDate, timeSlotMinutes) => daysInMonthByTimeSlotCalc(viewingDate, timeSlotMinutes)
  }

  private def daysInMonthByTimeSlotCalc(viewingDate: SDateLike, timeSlotMinutes: Int): Seq[Seq[Option[SDateLike]]] =
    consecutiveDaysInMonth(SDate.firstDayOfMonth(viewingDate), SDate.lastDayOfMonth(viewingDate))
      .map { day =>
        timeZoneSafeTimeSlots(
          slotsInDay(day, timeSlotMinutes),
          timeSlotMinutes
        )
      }
      .transpose

  private def maybeClockChangeDate(viewingDate: SDateLike): Option[SDateLike] = {
    val lastDay = SDate.lastDayOfMonth(viewingDate)
    (0 to 10).map(offset => lastDay.addDays(-1 * offset)).find(date => slotsInDay(date, 60).length == 25)
  }

  private def slotsFromShifts(shifts: StaffAssignmentsLike, terminal: Terminal, viewingDate: SDateLike, timeSlotMinutes: Int): Seq[Seq[Any]] =
    daysInMonthByTimeSlot((viewingDate, timeSlotMinutes)).map(_.map {
      case Some(slotDateTime) => shifts.terminalStaffAt(terminal, slotDateTime, JSDateConversions.longToSDateLocal)
      case None => "-"
    })

  private def stateFromProps(props: Props): State = {
    import drt.client.services.JSDateConversions._

    val viewingDate = props.terminalPageTab.dateFromUrlOrNow
    val daysInMonth: Seq[SDateLike] = consecutiveDaysInMonth(SDate.firstDayOfMonth(viewingDate), SDate.lastDayOfMonth(viewingDate))


    val dayForRowLabels = if (viewingDate.getMonth != 10)
      viewingDate.startOfTheMonth
    else
      SDate.lastDayOfMonth(viewingDate).getLastSunday

    val rowHeadings = slotsInDay(dayForRowLabels, props.timeSlotMinutes).map(_.prettyTime)

    State(Empty, daysInMonth.map(_.getDate.toString), rowHeadings, Map.empty, showEditStaffForm = false, showShiftStaffForm = false, showMinStaffForm = false, showMinStaffSuccess = false, Empty, ShiftAssignments.empty)
  }

  def apply(portCode: PortCode,
            terminalPageTab: TerminalPageTabLoc,
            router: RouterCtl[Loc],
           ): Unmounted[Props, State, Backend] = component(Props(portCode, terminalPageTab, router))
}
