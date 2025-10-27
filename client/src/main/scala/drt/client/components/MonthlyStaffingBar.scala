package drt.client.components

import diode.AnyAction.aType
import drt.client.SPAMain.{Loc, TerminalPageTabLoc, UrlDateParameter, UrlDayRangeType}
import drt.client.actions.Actions.UpdateShiftAssignments
import drt.client.components.MonthlyStaffingUtil._
import drt.client.components.StaffingUtil.navigationDates
import drt.client.modules.GoogleEventTracker
import drt.client.services.JSDateConversions.SDate
import drt.client.services.SPACircuit
import drt.client.services.handlers.UpdateUserPreferences
import drt.shared.StaffAssignment
import io.kinoplan.scalajs.react.material.ui.core.{MuiButton, MuiFormControl, MuiSwitch, MuiTypography}
import io.kinoplan.scalajs.react.material.ui.core.MuiButton.Color
import io.kinoplan.scalajs.react.material.ui.core.system.SxProps
import io.kinoplan.scalajs.react.material.ui.icons.MuiIcons
import io.kinoplan.scalajs.react.material.ui.icons.MuiIconsModule.{ChevronLeft, ChevronRight, Groups}
import japgolly.scalajs.react.callback.Callback
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.TagOf
import japgolly.scalajs.react.vdom.html_<^.{<, _}
import japgolly.scalajs.react.{BackendScope, CtorType, ReactEventFromInput, Reusability, ScalaComponent}
import org.scalajs.dom.html.{Div, Select}
import org.scalajs.dom.window.confirm
import uk.gov.homeoffice.drt.models.UserPreferences
import uk.gov.homeoffice.drt.ports.AirportConfig
import uk.gov.homeoffice.drt.time.SDateLike

import scala.scalajs.js
import scala.util.Try

sealed trait ConfirmAndSave {
  def apply(): ReactEventFromInput => Callback
}

case class ConfirmAndSaveForMonthlyStaffing(viewingDate: SDateLike,
                                            timeSlots: Seq[Seq[Any]],
                                            props: MonthlyStaffing.Props,
                                            state: MonthlyStaffing.State,
                                            scope: BackendScope[MonthlyStaffing.Props, MonthlyStaffing.State]) extends ConfirmAndSave {
  override def apply(): ReactEventFromInput => Callback = _ => Callback {
    val initialTimeSlots: Seq[Seq[Any]] = slotsFromShiftAssignments(state.shiftAssignments,
      props.terminalPageTab.terminal,
      viewingDate,
      props.timeSlotMinutes,
      props.terminalPageTab.dayRangeType.getOrElse("monthly"))

    val quarterHourlyChanges = getQuarterHourlySlotChanges(props.timeSlotMinutes, state.changes)
    val updatedTimeSlots: Seq[Seq[Any]] = applyRecordedChangesToShiftState(timeSlots, state.changes)
    val saveAsTimeSlotMinutes = 15

    val changedShiftSlots: Seq[StaffAssignment] = MonthlyStaffing.updatedShiftAssignments(
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
      if (props.isStaffShiftPage)
        SPACircuit.dispatch(UpdateShiftAssignments(changedShiftSlots))
      else
        SPACircuit.dispatch(UpdateShiftAssignments(changedShiftSlots))
    }
  }
}

case class ConfirmAndSaveForMonthlyShifts(shiftsData: Seq[ShiftSummaryStaffing],
                                          changedAssignments: Seq[StaffTableEntry],
                                          props: MonthlyShifts.Props,
                                          state: MonthlyShifts.State,
                                          scope: BackendScope[MonthlyShifts.Props, MonthlyShifts.State]) extends ConfirmAndSave {
  override def apply(): ReactEventFromInput => Callback = _ => Callback {
    val changedShifts: Seq[StaffAssignment] = shiftsData
      .flatMap(_.staffTableEntries.toSeq.map(ShiftAssignmentConverter.toStaffAssignment(_, props.terminalPageTab.terminal)))

    val changedShiftSlots: Seq[StaffAssignment] = MonthlyShifts.updatedConvertedShiftAssignments(
      changedShifts,
      props.terminalPageTab.terminal)

    val updatedMonth = props.terminalPageTab.dateFromUrlOrNow.getMonthString

    if (confirm(s"You have updated staff for ${MonthlyStaffingBar.whatDayChanged(changedAssignments)} $updatedMonth - do you want to save these changes?")) {
      GoogleEventTracker.sendEvent(s"${props.terminalPageTab.terminal}",
        "Save monthly staffing",
        s"updated staff for ${MonthlyStaffingBar.whatDayChanged(changedAssignments)} $updatedMonth")
      SPACircuit.dispatch(UpdateShiftAssignments(changedShiftSlots))
      scope.modState(state => state.copy(changedAssignments = Seq.empty[StaffTableEntry])).runNow()
    }
  }
}

object MonthlyStaffingBar {
  def whatDayChanged(changedAssignments: Seq[StaffTableEntry]): String = {
    changedAssignments.map(_.startTime.day).sortBy(_.toInt).distinct.mkString(", ")
  }

  private def navigationArrows(props: Props, previousWeekDate: SDateLike, nextWeekDate: SDateLike) = {
    <.div(
      MuiButton(color = Color.secondary, variant = "contained",
        sx = SxProps(Map("height" -> "40px", "backgroundColor" -> "white")))(MuiIcons(ChevronLeft)(fontSize = "small"),
        ^.onClick --> props.router.set(props.terminalPageTab.withUrlParameters(UrlDateParameter(Some(previousWeekDate.toISODateOnly))))),
      MuiButton(color = Color.secondary, variant = "contained",
        sx = SxProps(Map("height" -> "40px", "backgroundColor" -> "white")))(MuiIcons(ChevronRight)(fontSize = "small"),
        ^.onClick --> props.router.set(props.terminalPageTab.withUrlParameters(UrlDateParameter(Some(nextWeekDate.toISODateOnly)))))
    )
  }

  def handleNavigation(props: Props, viewingDate: SDateLike): VdomTagOf[Div] = {
    val (previousDate, nextDate) = navigationDates(viewingDate, props.terminalPageTab.dayRangeType.getOrElse("monthly"), () => SDate.now())
    navigationArrows(props, previousDate, nextDate)
  }

  def sixMonthsFromFirstOfMonth(date: SDateLike): Seq[SDateLike] = (0 to 5)
    .map(i => SDate.firstDayOfMonth(date).addMonths(i))

  private val monthOptions: Seq[SDateLike] = sixMonthsFromFirstOfMonth(SDate.now())

  case class Props(viewingDate: SDateLike,
                   terminalPageTab: TerminalPageTabLoc,
                   router: RouterCtl[Loc],
                   airportConfig: AirportConfig,
                   timeSlots: Seq[Seq[Any]],
                   handleShiftEditForm: ReactEventFromInput => Callback,
                   confirmAndSave: ConfirmAndSave,
                   isShiftsEmpty: Boolean,
                   userPreferences: UserPreferences
                  ) {
    def timeSlotMinutes: Int = Try(terminalPageTab.subMode.toInt).toOption.getOrElse(60)

    def dayRangeType: String = terminalPageTab.dayRangeType match {
      case Some(dayRange) => dayRange
      case None => "monthly"
    }
  }

  implicit val propsReuse: Reusability[Props] = Reusability((a, b) => a == b)

  def drawSelect(values: Seq[String],
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

  val component: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("MonthlyStaffingBar")
    .render_P { props =>
      def handleShiftViewToggle(): Callback = {
        Callback(GoogleEventTracker.sendEvent(s"${props.terminalPageTab.terminal}", "Shift View Toggle", s"${!props.userPreferences.showStaffingShiftView}")) >>
        Callback(SPACircuit.dispatch(UpdateUserPreferences(props.userPreferences.copy(showStaffingShiftView = !props.userPreferences.showStaffingShiftView))))
      }

      <.div(^.className := "staffing-bar",
        <.div(^.className := "staffing-controls-save",
          <.div(^.style := js.Dictionary("display" -> "flex", "justify-content" -> "space-between", "align-items" -> "center"),
            <.span(^.className := "staffing-controls-title",
              <.strong(props.terminalPageTab.dayRangeType match {
                case Some("monthly") => s"${props.viewingDate.getMonthString} ${props.viewingDate.getFullYear}"
                case Some("weekly") =>
                  val firstDayOfWeek = SDate.firstDayOfWeek(props.viewingDate)
                  val lastDayOfWeek = SDate.lastDayOfWeek(props.viewingDate)
                  if (firstDayOfWeek.getFullYear == lastDayOfWeek.getFullYear) {
                    val length = firstDayOfWeek.`shortDayOfWeek-DD-MMM-YYYY`.length
                    s"${firstDayOfWeek.`shortDayOfWeek-DD-MMM-YYYY`.substring(0, length - 4)} to ${SDate.lastDayOfWeek(props.viewingDate).`shortDayOfWeek-DD-MMM-YYYY`}"
                  } else
                    s"${SDate.firstDayOfWeek(props.viewingDate).`shortDayOfWeek-DD-MMM-YYYY`} to ${SDate.lastDayOfWeek(props.viewingDate).`shortDayOfWeek-DD-MMM-YYYY`}"
                case Some("daily") => s"${props.viewingDate.`dayOfWeek-DD-MMM-YYYY`}"
                case _ => s"${props.viewingDate.getMonthString} ${props.viewingDate.getFullYear}"
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
                    defaultValue = SDate.firstDayOfMonth(props.viewingDate).toISOString,
                    callback = (e: ReactEventFromInput) => {
                      props.router.set(props.terminalPageTab.withUrlParameters(UrlDateParameter(Option(SDate(e.target.value).toISODateOnly))))
                    }
                  ))
              } else EmptyVdom,
              <.div(^.className := "staffing-controls-navigation ",
                handleNavigation(props, props.viewingDate)
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
              if (props.isShiftsEmpty)
                EmptyVdom
              else
                <.div(^.style := js.Dictionary("display" -> "flex", "flexDirection" -> "row", "alignItems" -> "center"))(
                  MuiTypography(sx = SxProps(Map("paddingTop" -> "5px")))("Show shifts"),
                  MuiFormControl(sx = SxProps(Map("paddingBottom" -> "10px")))(
                    MuiSwitch(
                      defaultChecked = props.userPreferences.showStaffingShiftView,
                      color = Color.primary,
                      inputProps = js.Dynamic.literal("aria-label" -> "primary checkbox"),
                    )(^.onChange --> handleShiftViewToggle),
                  ),
                )
            ),
          ),
        ), <.div(^.style := js.Dictionary("paddingLeft" -> "10px", "paddingTop" -> "20px", "paddingBottom" -> "10px", "gap" -> "15px", "display" -> "flex", "align-items" -> "center"),
          MuiButton(color = Color.secondary, variant = "contained")
          (<.span("Edit staff"),
            VdomAttr("data-cy") := "edit-staff-button",
            ^.onClick ==> props.handleShiftEditForm),
          if (props.isShiftsEmpty)
            MuiButton(color = Color.secondary, variant = "contained")
            (<.span("Create shift pattern"),
              ^.onClick --> props.router.set(TerminalPageTabLoc(props.terminalPageTab.terminalName, "shifts", "createShifts")))
          else
            EmptyVdom,
          MuiButton(color = Color.primary, variant = "contained")
          (<.span("Save staff updates"),
            ^.onClick ==> props.confirmAndSave())
        ))
    }
    .configure(Reusability.shouldComponentUpdate)
    .build

  def apply(viewingDate: SDateLike,
            terminalPageTab: TerminalPageTabLoc,
            router: RouterCtl[Loc],
            airportConfig: AirportConfig,
            timeSlots: Seq[Seq[Any]],
            handleShiftEditForm: ReactEventFromInput => Callback,
            confirmAndSave: ConfirmAndSave,
            noExistingShifts: Boolean,
            userPreferences: UserPreferences
           ) = component(Props(viewingDate, terminalPageTab, router, airportConfig, timeSlots, handleShiftEditForm, confirmAndSave, noExistingShifts, userPreferences))
}
