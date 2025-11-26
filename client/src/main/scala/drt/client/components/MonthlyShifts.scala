package drt.client.components

import diode.AnyAction.aType
import diode.data.{Pending, Pot, Ready}
import diode.react.ReactConnectProxy
import drt.client.SPAMain.{Loc, TerminalPageTabLoc}
import drt.client.actions.Actions.{GetAllShiftAssignments, GetForecast, UpdateShiftAssignments}
import drt.client.components.MonthlyShiftsUtil.{updateShiftSummaries, updateTableEntries}
import drt.client.components.MonthlyStaffingUtil.slotsInDay
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.modules.GoogleEventTracker
import drt.client.services.JSDateConversions.SDate
import drt.client.services.SPACircuit
import drt.client.services.handlers.{GetShifts, UpdateUserPreferences}
import drt.client.util.DateRange
import drt.shared.CrunchApi.{ForecastPeriod, ForecastPeriodWithHeadlines, MillisSinceEpoch}
import drt.shared._
import io.kinoplan.scalajs.react.material.ui.core.MuiButton.Color
import io.kinoplan.scalajs.react.material.ui.core._
import io.kinoplan.scalajs.react.material.ui.core.system.SxProps
import japgolly.scalajs.react.callback.Callback
import japgolly.scalajs.react.component.Scala.{Component, Unmounted}
import japgolly.scalajs.react.component.builder.Lifecycle.ComponentDidMount
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.all.{div, p}
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
                   shiftSummariesPot: Pot[Seq[ShiftSummaryStaffing]],
                   changedAssignments: Seq[StaffTableEntry],
                   intervalMinutes: Int,
                   isBelowRecommended: Boolean,
                  )

  val log: Logger = LoggerFactory.getLogger(getClass.getName)

  case class Props(terminalPageTab: TerminalPageTabLoc,
                   router: RouterCtl[Loc],
                   airportConfig: AirportConfig,
                   userPreferences: UserPreferences,
                   shifts: Pot[Seq[Shift]],
                   recommendedStaff: Map[Long, Int],
                   shiftAssignments: Pot[ShiftAssignments],
                   viewMode: Boolean,
                   warningsEnabled: Boolean,
                  ) {
    def timeSlotMinutes: Int = Try(terminalPageTab.queryParams("timeInterval").toInt).toOption.getOrElse(60)
  }

  implicit val propsReuse: Reusability[Props] = Reusability((a, b) => a == b)
  implicit val stateReuse: Reusability[State] = Reusability((a, b) => a == b)

  class Backend(scope: BackendScope[Props, State]) {

    def populateShiftSummaries(recs: Map[MillisSinceEpoch, Int], props: MonthlyShifts.Props, viewingDate: SDateLike, shifts: Seq[Shift], shiftAssignments: ShiftAssignments): Seq[ShiftSummaryStaffing] =
      MonthlyShiftsUtil.generateShiftSummaries(
        viewingDate,
        props.terminalPageTab.dayRangeType.getOrElse("monthly"),
        props.terminalPageTab.terminal,
        shifts,
        ShiftAssignments(shiftAssignments.forTerminal(props.terminalPageTab.terminal)),
        recs,
        props.timeSlotMinutes,
      )

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

      case class Model(forecastPot: Pot[ForecastPeriod])

      val forecastRCP: ReactConnectProxy[Model] = SPACircuit.connect(m => Model(m.forecastPeriodPot.map(_.forecast)))

      <.div(
        forecastRCP { modelMP =>
          val model = modelMP()
          <.div {
            for {
              forecast <- model.forecastPot
              shifts <- props.shifts
              shiftAssignments <- props.shiftAssignments
            } {
              if (state.shiftSummariesPot.isEmpty || state.intervalMinutes != forecast.intervalMinutes) {
                scope.modState { s =>
                  val recommendationsBySlotTime = forecast.days.values.flatMap(_.map(slot => (slot.startMillis, slot.required))).toMap
                  val staffings = populateShiftSummaries(recommendationsBySlotTime, props, viewingDate, shifts, shiftAssignments)
                  val belowRecommendedExists = staffings.flatMap(_.staffTableEntries).exists { entry =>
                    entry.staffNumber < entry.staffRecommendation
                  }
                  s.copy(shiftSummariesPot = Ready(staffings), intervalMinutes = forecast.intervalMinutes, isBelowRecommended = belowRecommendedExists)
                }.runNow()
              }
            }

            <.div(
              props.shifts.renderReady { shifts =>
                <.div(
                  <.div(
                    if (state.showStaffSuccess)
                      StaffUpdateSuccess(IStaffUpdateSuccess(0, "The staff numbers have been successfully updated for your chosen dates and times", () => {
                        scope.modState(state => state.copy(showStaffSuccess = false)).runNow()
                      })) else EmptyVdom,
                  ),
                  <.div(^.className := "staffing-container",
                    MuiTypography(variant = "h2")(s"Staffing"),
                    if (shifts.isEmpty && !props.viewMode) {
                      <.div(^.style := js.Dictionary("padding-top" -> "10px"), AddShiftBarComponent(IAddShiftBarComponentProps(
                        gotToCreateShifts(props),
                        goToViewShifts(props),
                      )))
                    } else {
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
                          confirmAndSave = ConfirmAndSaveForMonthlyShifts(state.shiftSummariesPot.getOrElse(Seq.empty), state.changedAssignments, props, state, scope),
                          noExistingShifts = shifts.isEmpty,
                          userPreferences = props.userPreferences,
                          isShiftFeatureEnabled = true
                        ),
                        if (props.warningsEnabled && state.isBelowRecommended)
                          MuiAlert(variant = MuiAlert.Variant.standard, severity = "error")(
                            <.div(
                              MuiTypography(variant = "h3")("Risk of queue breach"),
                              MuiTypography()("There are shifts that may need more staff"),
                            )
                          )
                        else EmptyVdom,
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
                        state.shiftSummariesPot.renderPending { _ =>
                          <.div(^.style := js.Dictionary("display" -> "flex", "justifyContent" -> "center", "paddingTop" -> "50px"),
                            MuiCircularProgress()()
                          )
                        },
                        state.shiftSummariesPot.renderReady { shiftSummaries =>
                          <.div(^.className := "shifts-table",
                            <.div(^.className := "shifts-table-content",
                              ShiftHotTableViewComponent(ShiftHotTableViewProps(
                                shiftDate = ShiftDate(year = viewingDate.getFullYear, month = viewingDate.getMonth, day = viewingDate.getDate),
                                viewPeriod = props.terminalPageTab.dayRangeType.getOrElse("monthly"),
                                interval = props.timeSlotMinutes,
                                shiftSummaries = shiftSummaries,
                                handleSaveChanges = (shifts: Seq[ShiftSummaryStaffing], changedAssignments: Seq[StaffTableEntry]) => {
                                  val updatedTableEntries = updateTableEntries(state.changedAssignments, changedAssignments)
                                  val updatedShiftSummaries = updateShiftSummaries(shifts, updatedTableEntries, 15)
                                  scope.modState(state => state.copy(
                                    shiftSummariesPot = Ready(updatedShiftSummaries),
                                    changedAssignments = updatedTableEntries
                                  ))
                                },
                                handleEditShift = (shiftSummary: ShiftSummary) => {
                                  props.router.set(props.terminalPageTab.copy(subMode = "editShifts",
                                    queryParams = props.terminalPageTab.queryParams ++ Map(
                                      "shiftName" -> s"${shiftSummary.name}",
                                      "shiftDate" -> s"${shiftSummary.startDate.year}-${shiftSummary.startDate.month}-${shiftSummary.startDate.day}"
                                    )
                                  )).runNow()
                                },
                                handleRemoveShift = (shiftSummary: ShiftSummary) => {
                                  props.router.set(props.terminalPageTab.copy(subMode = "removeShift",
                                    queryParams = props.terminalPageTab.queryParams ++ shiftQueryParameters(shiftSummary)
                                  )).runNow()
                                },
                                sendAnalyticsEvent = GoogleEventTracker.sendEvent,
                                warningsEnabled = props.warningsEnabled,
                              ))
                            ),
                            <.div(^.className := "terminal-staffing-content-footer",
                              MuiButton(color = Color.secondary, variant = "contained")
                              (<.span("Edit staff"),
                                VdomAttr("data-cy") := "edit-staff-button",
                                ^.onClick ==> handleShiftEditForm),
                              if (shifts.isEmpty)
                                MuiButton(color = Color.secondary, variant = "contained")
                                (<.span("Create shift pattern"),
                                  ^.onClick --> props.router.set(TerminalPageTabLoc(props.terminalPageTab.terminalName, "shifts", "createShifts")))
                              else
                                EmptyVdom,
                              MuiButton(color = Color.primary, variant = "contained")
                              (<.span("Save staff updates"),
                                ^.onClick ==> confirmAndSaveShifts(shiftSummaries, state.changedAssignments, props, state, scope))
                            )
                          )
                        }
                      )
                    }
                  )
                )
              }
            )
          }
        }
      )
    }
  }

  private def shiftQueryParameters(shiftSummary: ShiftSummary) = {
    Map(
      "shiftName" -> s"${shiftSummary.name}",
      "shiftDate" -> s"${shiftSummary.startDate.year}-${shiftSummary.startDate.month}-${shiftSummary.startDate.day}",
      "shiftStartTime" -> s"${shiftSummary.startTime}"
    )
  }

  private def goToViewShifts(props: Props) = {
    () => {
      if (props.userPreferences.showStaffingShiftView) {
        SPACircuit.dispatch(UpdateUserPreferences(props.userPreferences.copy(showStaffingShiftView = !props.userPreferences.showStaffingShiftView)))
      }
      props.router.set(TerminalPageTabLoc(props.terminalPageTab.terminalName, "shifts", "viewShifts")).runNow()
    }
  }

  private def gotToCreateShifts(props: Props) =
    () => props.router.set(TerminalPageTabLoc(props.terminalPageTab.terminalName, "shifts", "createShifts")).runNow()


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
    .initialState(
      State(
        showEditStaffForm = false,
        showStaffSuccess = false,
        addShiftForm = false,
        shiftSummariesPot = Pending(),
        changedAssignments = Seq.empty,
        intervalMinutes = 0,
        isBelowRecommended = false,
      )
    )
    .renderBackend[Backend]
    .configure(Reusability.shouldComponentUpdate)
    .componentDidMount { m =>
      val intervalMinutes = Try(m.props.terminalPageTab.queryParams("timeInterval").toInt).toOption.getOrElse(60)
      val maxDaysInMonth = 31
      Callback(SPACircuit.dispatch(GetForecast(m.props.terminalPageTab.dateFromUrlOrNow, maxDaysInMonth, Terminal(m.props.terminalPageTab.terminalName), intervalMinutes)))
    }
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
            shiftCreated: Boolean,
            viewMode: Boolean,
            recommendedStaff: Map[Long, Int],
            shiftsPot: Pot[Seq[Shift]],
            shiftAssignmentsPot: Pot[ShiftAssignments],
            warningsEnabled: Boolean,
           ): Unmounted[Props, State, Backend] = {
    if (shiftCreated) {
      val newQueryParams = terminalPageTab.queryParams - "shifts"
      Callback(SPACircuit.dispatch(GetShifts)).runNow()
      router.set(terminalPageTab.copy(queryParams = newQueryParams)).runNow()
    }
    component(Props(terminalPageTab, router, airportConfig, userPreferences, shiftsPot, recommendedStaff, shiftAssignmentsPot, viewMode, warningsEnabled))
  }
}
