package drt.client.components

import diode.AnyAction.aType
import diode.data.Pot
import drt.client.SPAMain.{Loc, TerminalPageTabLoc}
import drt.client.actions.Actions.UpdateShiftAssignments
import drt.client.components.MonthlyShiftsUtil.{updateAssignments, updateChangeAssignment}
import drt.client.components.MonthlyStaffingUtil.slotsInDay
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.services.JSDateConversions.SDate
import drt.client.services.SPACircuit
import drt.client.services.handlers.{GetShifts, UpdateUserPreferences}
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
import uk.gov.homeoffice.drt.Shift
import uk.gov.homeoffice.drt.models.UserPreferences
import uk.gov.homeoffice.drt.ports.AirportConfig
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{LocalDate, SDateLike}

import scala.scalajs.js
import scala.util.Try


object MonthlyShifts {

  case class State(showEditStaffForm: Boolean,
                   showStaffSuccess: Boolean,
                   addShiftForm: Boolean,
                   shifts: Seq[Shift],
                   shiftAssignments: ShiftAssignments,
                   shiftSummaries: Seq[ShiftSummaryStaffing] = Seq.empty,
                   changedAssignments: Seq[StaffTableEntry] = Seq.empty,
                  )

  val log: Logger = LoggerFactory.getLogger(getClass.getName)

  case class Props(terminalPageTab: TerminalPageTabLoc,
                   router: RouterCtl[Loc],
                   airportConfig: AirportConfig,
                   userPreferences: UserPreferences,
                   shifts: Seq[Shift]
                  ) {
    def timeSlotMinutes: Int = Try(terminalPageTab.subMode.toInt).toOption.getOrElse(60)
  }

  implicit val propsReuse: Reusability[Props] = Reusability((a, b) => a == b)
  implicit val stateReuse: Reusability[State] = Reusability((a, b) => a == b)

  class Backend(scope: BackendScope[Props, State]) {

    def render(props: Props, state: State): VdomTagOf[Div] = {

      val handleShiftEditForm = (e: ReactEventFromInput) => Callback {
        e.preventDefault()
        scope.modState(state => state.copy(showEditStaffForm = true)).runNow()
      }

      val viewingDate = props.terminalPageTab.dateFromUrlOrNow

      def confirmAndSaveShifts(shiftsData: Seq[ShiftSummaryStaffing],
                               changedAssignments: Seq[StaffTableEntry],
                               props: MonthlyShifts.Props,
                               state: MonthlyShifts.State,
                               scope: BackendScope[MonthlyShifts.Props, MonthlyShifts.State]): ReactEventFromInput => Callback = {
        ConfirmAndSaveForMonthlyShifts(shiftsData, changedAssignments, props, state, scope)()
      }

      case class Model(shiftAssignmentsPot: Pot[ShiftAssignments], staffShiftsPot: Pot[Seq[Shift]])
      val staffRCP = SPACircuit.connect(m => Model(m.allShiftAssignments, m.shifts))

      val modelChangeDetection = staffRCP { modelMP =>
        val model = modelMP()
        val content = for {
          shiftAssignments <- model.shiftAssignmentsPot
          staffShifts <- model.staffShiftsPot
        } yield {
          if (shiftAssignments != state.shiftAssignments || staffShifts != state.shifts) {
            val shiftSummaries = MonthlyShiftsUtil.generateShiftSummaries(
              viewingDate,
              props.terminalPageTab.dayRangeType.getOrElse("monthly"),
              props.terminalPageTab.terminal,
              staffShifts,
              ShiftAssignments(shiftAssignments.forTerminal(props.terminalPageTab.terminal)),
              props.timeSlotMinutes)
            scope.modState(state => state.copy(shiftAssignments = shiftAssignments,
              shiftSummaries = shiftSummaries, shifts = staffShifts)).runNow()
          }
          <.div()
        }
        if (model.shiftAssignmentsPot.isReady && model.staffShiftsPot.isReady)
          <.div(content.render(identity))
        else
          <.div("loading...")
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
          MuiTypography(variant = "h2")(s"Staffing"),
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
              timeSlots = Seq.empty,
              handleShiftEditForm = handleShiftEditForm,
              confirmAndSave = ConfirmAndSaveForMonthlyShifts(state.shiftSummaries, state.changedAssignments, props, state, scope),
              isShiftsEmpty = false,
              userPreferences = props.userPreferences
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
                ustd = IUpdateStaffForTimeRangeData(
                  startDayAt = Moment.utc(),
                  startTimeAt = Moment.utc(),
                  endTimeAt = Moment.utc(),
                  endDayAt = Moment.utc(), actualStaff = "0"),
                interval = props.timeSlotMinutes,
                handleSubmit = (ssf: IUpdateStaffForTimeRangeData) => {
                  SPACircuit.dispatch(UpdateShiftAssignments(staffAssignmentsFromForm(ssf, props.terminalPageTab.terminal)))
                  scope.modState(state => {
                    val newState = state.copy(showEditStaffForm = false, showStaffSuccess = true)
                    newState
                  }).runNow()
                },
                cancelHandler = () => {
                  scope.modState(state => state.copy(showEditStaffForm = false)).runNow()
                })))),
            <.div(^.className := "shifts-table",
              <.div(^.className := "shifts-table-content",
                ShiftHotTableViewComponent(ShiftHotTableViewProps(
                  shiftDate = ShiftDate(year = viewingDate.getFullYear, month = viewingDate.getMonth, day = viewingDate.getDate),
                  dayRange = props.terminalPageTab.dayRangeType.getOrElse("monthly"),
                  interval = props.timeSlotMinutes,
                  initialShifts = state.shiftSummaries,
                  handleSaveChanges = (shifts: Seq[ShiftSummaryStaffing], changedAssignments: Seq[StaffTableEntry]) => {
                    val updateChanges = updateChangeAssignment(state.changedAssignments, changedAssignments)
                    val updateShifts = updateAssignments(shifts, updateChanges, 15)
                    scope.modState(state => state.copy(
                      shiftSummaries = updateShifts,
                      changedAssignments = updateChanges
                    )).runNow()
                  },
                  handleEditShift = (index: Int, shiftSummary: ShiftSummary) => {
                    props.router.set(props.terminalPageTab.copy(subMode = "editShifts",
                      queryParams = props.terminalPageTab.queryParams ++ Map(
                        "shiftName" -> s"${shiftSummary.name}",
                        "shiftDate" -> s"${shiftSummary.startDate.year}-${shiftSummary.startDate.month}-${shiftSummary.startDate.day}"
                      )
                    )).runNow()
                  }
                ))
              ),
              <.div(^.className := "terminal-staffing-content-header",
                MuiButton(color = Color.primary, variant = "contained")
                (<.span("Save staff updates"),
                  ^.onClick ==> confirmAndSaveShifts(state.shiftSummaries, state.changedAssignments, props, state, scope))
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

  val component: Component[Props, State, Backend, CtorType.Props] = ScalaComponent.builder[Props]("ShiftStaffing")
    .initialStateFromProps(p => State(showEditStaffForm = false,
      showStaffSuccess = false,
      addShiftForm = false,
      shifts = p.shifts,
      shiftAssignments = ShiftAssignments.empty))
    .renderBackend[Backend]
    .configure(Reusability.shouldComponentUpdate)
    .build

  def updatedConvertedShiftAssignments(changes: Seq[StaffAssignment],
                                       terminalName: Terminal): Seq[StaffAssignment] = changes.map { change =>
    StaffAssignment(change.name, terminalName, change.start, change.end, change.numberOfStaff, None)
  }

  private def maybeClockChangeDate(viewingDate: SDateLike): Option[SDateLike] = {
    val lastDay = SDate.lastDayOfMonth(viewingDate)
    (0 to 10).map(offset => lastDay.addDays(-1 * offset)).find(date => slotsInDay(date, 60).length == 25)
  }

  def apply(terminalPageTab: TerminalPageTabLoc,
            router: RouterCtl[Loc],
            airportConfig: AirportConfig,
            userPreferences: UserPreferences,
            shiftCreated: Boolean
           ): Unmounted[Props, State, Backend] = {
    if (shiftCreated) {
      val newQueryParams = terminalPageTab.queryParams - "shifts"
      Callback(SPACircuit.dispatch(GetShifts)).runNow()
      router.set(terminalPageTab.copy(queryParams = newQueryParams)).runNow()
    }
    component(Props(terminalPageTab, router, airportConfig, userPreferences, Seq.empty))
  }
}
