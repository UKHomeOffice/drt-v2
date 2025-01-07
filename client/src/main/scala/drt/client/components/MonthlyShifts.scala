package drt.client.components

import diode.AnyAction.aType
import diode.data.Pot
import drt.client.SPAMain.{Loc, TerminalPageTabLoc, ToggleShiftView, UrlDateParameter, UrlDayRangeType}
import drt.client.actions.Actions.{GetAllStaffShifts, UpdateStaffShifts}
import drt.client.components.MonthlyShiftsUtil.{generateShiftData, updateAssignments, updateChangeAssignment}
import drt.client.components.StaffingUtil.navigationDates
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.modules.GoogleEventTracker
import drt.client.services.JSDateConversions.SDate
import drt.client.services.SPACircuit
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

import scala.scalajs.js
import scala.util.Try


object MonthlyShifts {

  case class State(showEditStaffForm: Boolean,
                   showStaffSuccess: Boolean,
                   addShiftForm: Boolean,
                   shifts: ShiftAssignments,
                   shiftsData: Seq[ShiftData] = Seq.empty,
                   changedAssignments: Seq[ShiftAssignment] = Seq.empty
                  )

  val log: Logger = LoggerFactory.getLogger(getClass.getName)

  case class Props(terminalPageTab: TerminalPageTabLoc,
                   router: RouterCtl[Loc],
                   airportConfig: AirportConfig) {
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

  private val monthOptions: Seq[SDateLike] = sixMonthsFromFirstOfMonth(SDate.now())

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

      def whatDayChanged(changedAssignments: Seq[ShiftAssignment]) = {
        changedAssignments.map(_.startTime.day).distinct.mkString(", ")
      }

      def confirmAndSave(shiftsData: Seq[ShiftData], changedAssignments: Seq[ShiftAssignment]): ReactEventFromInput => Callback = (_: ReactEventFromInput) => Callback {
        val changedShifts: Seq[StaffAssignment] = shiftsData.flatMap(_.assignments.toSeq.map(ShiftAssignmentConverter.toStaffAssignment(_, props.terminalPageTab.terminal)))

        val changedShiftSlots: Seq[StaffAssignment] = updatedConvertedShiftAssignments(
          changedShifts,
          props.terminalPageTab.terminal,
          props.timeSlotMinutes)

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

      case class Model(monthOfStaffShiftsPot: Pot[ShiftAssignments], staffShiftsPot: Pot[Seq[StaffShift]])
      val staffRCP = SPACircuit.connect(m => Model(m.allStaffShifts, m.staffShifts))


      val modelChangeDetection = staffRCP { modelMP =>
        val model = modelMP()
        val content = for {
          monthOfShifts <- model.monthOfStaffShiftsPot
          staffShifts <- model.staffShiftsPot
        } yield {
          if (monthOfShifts != state.shifts) {
            val initialShift: Seq[ShiftData] = generateShiftData(viewingDate,
              props.terminalPageTab.dayRangeType.getOrElse("monthly"),
              props.terminalPageTab.terminal,
              staffShifts,
              ShiftAssignments(monthOfShifts.forTerminal(props.terminalPageTab.terminal)),
              props.timeSlotMinutes)
            scope.modState(state => state.copy(shifts = monthOfShifts,
              shiftsData = initialShift)).runNow()
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
                  <.div(^.className := "staffing-controls-select",
                    drawSelect(
                      values = Seq("monthly", "weekly", "daily"),
                      names = Seq("View: Monthly", "View: Weekly", "View: Daily"),
                      defaultValue = s"${props.dayRangeType}",
                      callback = (e: ReactEventFromInput) =>
                        props.router.set(props.terminalPageTab.withUrlParameters(UrlDayRangeType(Some(e.target.value))))
                    )
                  ),
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
                  <.div(^.className := "staffing-controls-navigation ",
                    handleNavigation(props, viewingDate)
                  ),
                  <.div(^.className := "staffing-controls-select",
                    drawSelect(
                      values = Seq("15", "30", "60"),
                      names = Seq("Display: Every 15 mins", "Display: Every 30 mins", "Display: Hourly"),
                      defaultValue = s"${props.timeSlotMinutes}",
                      callback = (e: ReactEventFromInput) =>
                        props.router.set(props.terminalPageTab.copy(subMode = s"${e.target.value}"))
                    )
                  ),
                  MuiButton(color = Color.primary,
                    variant = "outlined",
                    size = "small",
                    sx = SxProps(Map("backgroundColor" -> "white")))
                  (MuiIcons(Groups)(fontSize = "small"),
                    <.span(^.style := js.Dictionary("paddingLeft" -> "5px"), "Edit staff"),
                    VdomAttr("data-cy") := "edit-staff-button",
                    ^.onClick ==> handleShiftEditForm),
                  MuiButton(color = Color.primary, variant = "contained")
                  (<.span(^.style := js.Dictionary("paddingLeft" -> "5px"), "Save staff updates"),
                    ^.onClick ==> confirmAndSave(state.shiftsData, state.changedAssignments))
                ),
              ),
              <.div(^.className := "staffing-controls-toggle",
                MuiButton(color = Color.secondary, variant = "outlined")
                (<.span(^.style := js.Dictionary("paddingLeft" -> "5px"), "Toggle Shift view"),
                  ^.onClick --> props.router.set(props.terminalPageTab.withUrlParameters(ToggleShiftView(Some("table"))))
                )
              )
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
              <.div(^.className := "staffing-table-content",
                ShiftHotTableViewComponent(ShiftHotTableViewProps(
                  ViewDate(year = viewingDate.getFullYear, month = viewingDate.getMonth, day = viewingDate.getDate),
                  dayRange = props.terminalPageTab.dayRangeType.getOrElse("monthly"),
                  interval = props.timeSlotMinutes,
                  initialShifts = state.shiftsData,
                  handleSaveChanges = (shifts: Seq[ShiftData], changedAssignments: Seq[ShiftAssignment]) => {
                    val updateChanges = updateChangeAssignment(state.changedAssignments, changedAssignments)
                    val updateShifts = updateAssignments(shifts, updateChanges)
                    scope.modState(state => state.copy(
                      shiftsData = updateShifts,
                      changedAssignments = updateChanges
                    )).runNow()
                  }))
              ),
              <.div(^.className := "terminal-staffing-content-header",
                MuiButton(color = Color.primary, variant = "contained")
                (<.span(^.style := js.Dictionary("paddingLeft" -> "5px"), "Save staff updates"),
                  ^.onClick ==> confirmAndSave(state.shiftsData, state.changedAssignments))
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
    .initialStateFromProps(_ => State(showEditStaffForm = false,
      showStaffSuccess = false,
      addShiftForm = false,
      shifts = ShiftAssignments.empty))
    .renderBackend[Backend]
    .configure(Reusability.shouldComponentUpdate)
    .componentDidMount(_ => Callback(SPACircuit.dispatch(GetAllStaffShifts)))
    .build


  private def updatedConvertedShiftAssignments(changes: Seq[StaffAssignment],
                                               terminalName: Terminal,
                                               timeSlotMinutes: Int
                                              ): Seq[StaffAssignment] = changes.map { change =>
    val startMd = change.start
    val endMd = SDate(startMd).addMinutes(timeSlotMinutes - 1).millisSinceEpoch
    StaffAssignment(change.name, terminalName, startMd, endMd, change.numberOfStaff, None)
  }


  private def maybeClockChangeDate(viewingDate: SDateLike): Option[SDateLike] = {
    val lastDay = SDate.lastDayOfMonth(viewingDate)
    (0 to 10).map(offset => lastDay.addDays(-1 * offset)).find(date => slotsInDay(date, 60).length == 25)
  }

  def apply(terminalPageTab: TerminalPageTabLoc,
            router: RouterCtl[Loc],
            airportConfig: AirportConfig
           ): Unmounted[Props, State, Backend] = component(Props(terminalPageTab, router, airportConfig))
}
