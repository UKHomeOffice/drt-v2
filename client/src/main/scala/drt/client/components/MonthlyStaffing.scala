package drt.client.components

import diode.UseValueEq
import drt.client.SPAMain.{Loc, TerminalPageTabLoc, UrlDateParameter}
import drt.client.actions.Actions.UpdateShifts
import drt.client.components.TerminalPlanningComponent.defaultStartDate
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.modules.GoogleEventTracker
import drt.client.services.JSDateConversions.SDate
import drt.client.services.SPACircuit
import drt.shared.FlightsApi.TerminalName
import drt.shared._
import japgolly.scalajs.react._
import japgolly.scalajs.react.component.Scala.Unmounted
import japgolly.scalajs.react.extra.Reusability
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.TagOf
import japgolly.scalajs.react.vdom.html_<^._
import org.scalajs.dom.html.Select
import org.scalajs.dom.window.confirm

import scala.util.Try


object MonthlyStaffing {

  case class TimeSlotDay(timeSlot: Int, day: Int) {
    def key: (Int, Int) = (timeSlot, day)
  }

  case class State(timeSlots: Seq[Seq[Any]],
                   colHeadings: Seq[String],
                   rowHeadings: Seq[String],
                   changes: Map[(Int, Int), Int]) extends UseValueEq

  val log: Logger = LoggerFactory.getLogger(getClass.getName)

  case class Props(shifts: ShiftAssignments,
                   terminalPageTab: TerminalPageTabLoc,
                   router: RouterCtl[Loc]) {
    def timeSlotMinutes: Int = Try(terminalPageTab.subMode.toInt).toOption.getOrElse(15)
  }

  def slotsInDay(date: SDateLike, slotDuration: Int): Seq[SDateLike] = {

    val startOfDay = SDate.midnightOf(date)
    val slots = minutesInDay(date) / slotDuration
    List.tabulate(slots)(i => {
      val minsToAdd = i * slotDuration
      startOfDay.addMinutes(minsToAdd)
    })
  }

  def timeZoneSafeTimeSlots(slots: Seq[SDateLike], slotMinutes: Int): Seq[Option[SDateLike]] = {
    val slotsInRegularDay = (24 * 60 * 60) / slotMinutes
    val slotsPerHour = 60 / slotMinutes
    if (slots.size < slotsInRegularDay) {

      val timeslotsWithNones: Seq[Option[SDateLike]] = slots.sliding(2).flatMap(dates =>
        if (dates(0).getTimeZoneOffsetMillis < dates(1).getTimeZoneOffsetMillis)
          Option(dates(0)) :: List.fill(slotsPerHour)(None)
        else
          Option(dates(0)) :: Nil
      ).toSeq
      timeslotsWithNones ++ Seq(Option(slots.last))
    }

    else
      slots.map(Option(_))
  }

  def minutesInDay(date: SDateLike): Int = {
    val startOfDay = SDate.midnightOf(date)
    val endOfDay = SDate.midnightOf(date.addDays(1))
    (endOfDay.millisSinceEpoch - startOfDay.millisSinceEpoch).toInt / 1000 / 60
  }

  def drawSelect(values: Seq[String], names: Seq[String], defaultValue: String, callback: ReactEventFromInput => Callback): TagOf[Select] = {
    val valueNames = values.zip(names)
    <.select(^.className := "form-control", ^.defaultValue := defaultValue.toString,
      ^.onChange ==> callback,
      valueNames.map {
        case (value, name) => <.option(^.value := value, s"$name")
      }.toTagMod)
  }

  def consecutiveDaysInMonth(startDay: SDateLike, endDay: SDateLike): Seq[SDateLike] = {
    val days = (endDay.getDate() - startDay.getDate()) + 1
    List.tabulate(days)(i => startDay.addDays(i))
  }

  def sixMonthsFromFirstOfMonth(date: SDateLike): Seq[SDateLike] = (0 to 5).map(i => SDate.firstDayOfMonth(date).addMonths(i))

  def applyRecordedChangesToShiftState(staffTimeSlotDays: Seq[Seq[Any]], changes: Map[(Int, Int), Int]): Seq[Seq[Any]] = changes
    .foldLeft(staffTimeSlotDays) {
      case (staffSoFar, ((slotIdx, dayIdx), newStaff)) =>
        staffSoFar.updated(slotIdx, staffSoFar(slotIdx).updated(dayIdx, newStaff))
    }

  def whatDayChanged(startingSlots: Seq[Seq[Any]], updatedSlots: Seq[Seq[Any]]): Set[Int] =
    toDaysWithIndexSet(updatedSlots)
      .diff(toDaysWithIndexSet(startingSlots)).map { case (_, dayIndex) => dayIndex }

  def toDaysWithIndexSet(updatedSlots: Seq[Seq[Any]]): Set[(Seq[Any], Int)] = {
    updatedSlots
      .transpose
      .zipWithIndex
      .toSet
  }

  def dateListToString(dates: List[String]): String = dates.map(_.toInt).sorted match {
    case Nil => ""
    case head :: Nil => head.toString
    case dateList => dateList.dropRight(1).mkString(", ") + " and " + dateList.last
  }

  val monthOptions: Seq[SDateLike] = sixMonthsFromFirstOfMonth(SDate.now())

  implicit val propsReuse: Reusability[Props] = Reusability.by((_: Props).shifts.hashCode)
  implicit val stateReuse: Reusability[State] = Reusability.always[State]

  def getQuarterHourlySlotChanges(timeSlotMinutes: Int, changes: Map[(Int, Int), Int]): Map[(Int, Int), Int] = if (timeSlotMinutes == 60) hourlyToQuarterHourlySlots(changes) else changes

  val component = ScalaComponent.builder[Props]("StaffingV2")
    .initialStateFromProps(props => {
      stateFromProps(props)
    })
    .renderPS((scope, props, state) => {
      def confirmAndSave(startOfMonthMidnight: SDateLike) = (_: ReactEventFromInput) =>
        Callback {

          val initialTimeSlots: Seq[Seq[Any]] = stateFromProps(props).timeSlots
          val changes = scope.state.changes
          val quarterHourlyChanges = getQuarterHourlySlotChanges(props.timeSlotMinutes, changes)
          val updatedTimeSlots: Seq[Seq[Any]] = applyRecordedChangesToShiftState(state.timeSlots, changes)

          val changedShiftSlots: Seq[StaffAssignment] = updatedShiftAssignments(
            quarterHourlyChanges,
            startOfMonthMidnight,
            props.terminalPageTab.terminal,
            props.timeSlotMinutes
          )

          val updatedMonth = props.terminalPageTab.dateFromUrlOrNow.getMonthString()
          val changedDays = whatDayChanged(initialTimeSlots, updatedTimeSlots)
            .map(d => state.colHeadings(d)).toList

          if (confirm(s"You have updated staff for ${dateListToString(changedDays)} $updatedMonth - do you want to save these changes?")) {
            GoogleEventTracker.sendEvent(s"${props.terminalPageTab.terminal}", "Save Monthly Staffing", s"updated staff for ${dateListToString(changedDays)} $updatedMonth")
            SPACircuit.dispatch(UpdateShifts(changedShiftSlots))
          }
        }

      val viewingDate = SDate.firstDayOfMonth(props.terminalPageTab.dateFromUrlOrNow)
      <.div(
        <.div(^.className := "date-picker",
          <.div(^.className := "row",
            List(
              <.div(<.label("Choose Month", ^.className := "text center")),
              <.div(drawSelect(
                values = monthOptions.map(_.toISOString()),
                names = monthOptions.map(d => s"${d.getMonthString()} ${d.getFullYear()}"),
                defaultValue = viewingDate.toISOString(),
                callback = (e: ReactEventFromInput) => {
                  props.router.set(props.terminalPageTab.withUrlParameters(UrlDateParameter(Option(SDate(e.target.value).toISODateOnly))))
                })),
              <.div(<.label("Time Resolution", ^.className := "text center")),
              <.div(drawSelect(
                values = Seq("15", "60"),
                names = Seq("Quarter Hourly", "Hourly"),
                defaultValue = s"${props.timeSlotMinutes}",
                callback = (e: ReactEventFromInput) =>
                  props.router.set(props.terminalPageTab.copy(subMode = e.target.value))
              )),
              <.div(
                <.input.button(^.value := "Save Changes",
                  ^.className := "btn btn-primary",
                  ^.onClick ==> confirmAndSave(viewingDate)
                ))
            ).toTagMod
          )
        ),
        HotTable.component(HotTable.props(
          state.timeSlots,
          colHeadings = state.colHeadings,
          rowHeadings = state.rowHeadings,
          changeCallback = (row, col, value) => {
            scope.modState(state => state.copy(changes = state.changes.updated(TimeSlotDay(row, col).key, value))).runNow()
          }
        )),
        <.div(^.className := "row",
          <.div(^.className := "col-sm-1 no-gutters",
            <.input.button(^.value := "Save Changes",
              ^.className := "btn btn-primary",
              ^.onClick ==> confirmAndSave(viewingDate)
            )
          )
        ))
    })
    .configure(Reusability.shouldComponentUpdate)
    .componentDidMount(p => Callback {
      GoogleEventTracker.sendPageView(s"${p.props.terminalPageTab.terminal}/planning/${defaultStartDate(p.props.terminalPageTab.dateFromUrlOrNow).toISODateOnly}/${p.props.terminalPageTab.subMode}")
    })
    .build

  def updatedShiftAssignments(changes: Map[(Int, Int), Int], startOfMonthMidnight: SDateLike, terminalName: TerminalName, timeSlotMinutes: Int): Seq[StaffAssignment] = changes.toSeq.map {
    case ((slotIdx, dayIdx), staff) =>

      daysInMonthByTimeSlot(startOfMonthMidnight, timeSlotMinutes)(slotIdx)(dayIdx).map((slotStart: SDateLike) => {
        val startMd = MilliDate(slotStart.millisSinceEpoch)
        val endMd = MilliDate(slotStart.addMinutes(timeSlotMinutes - 1).millisSinceEpoch)
        StaffAssignment(slotStart.toISOString(), terminalName, startMd, endMd, staff, None)
      })
  }.collect {
    case Some(a) => a
  }

  def hourlyToQuarterHourlySlots(hourlySlots: Map[(Int, Int), Int]): Map[(Int, Int), Int] = hourlySlots.flatMap {
    case ((slotIdx, dayIdx), staff) =>
      (0 to 3).map(offset => (((slotIdx * 4) + offset, dayIdx), staff))
  }


  def daysInMonthByTimeSlot(viewingDate: SDateLike, timeSlotMinutes: Int): Seq[Seq[Option[SDateLike]]] =
    consecutiveDaysInMonth(SDate.firstDayOfMonth(viewingDate), SDate.lastDayOfMonth(viewingDate))
      .map(day => timeZoneSafeTimeSlots(
        slotsInDay(day, timeSlotMinutes),
        timeSlotMinutes
      ))
      .transpose

  def stateFromProps(props: Props): State = {
    import drt.client.services.JSDateConversions._

    val viewingDate = props.terminalPageTab.dateFromUrlOrNow
    val terminalName = props.terminalPageTab.terminal
    val terminalShifts = props.shifts.forTerminal(terminalName)
    val shiftAssignments = ShiftAssignments(terminalShifts)

    val daysInMonth: Seq[SDateLike] = consecutiveDaysInMonth(SDate.firstDayOfMonth(viewingDate), SDate.lastDayOfMonth(viewingDate))

    val staffTimeSlots: Seq[Seq[Any]] = daysInMonthByTimeSlot(viewingDate, props.timeSlotMinutes).map(_.map {
      case Some(slotDateTime) => shiftAssignments.terminalStaffAt(terminalName, slotDateTime)
      case None => "-"
    })

    val rowHeadings = slotsInDay(SDate.now(), props.timeSlotMinutes).map(_.prettyTime())

    State(staffTimeSlots, daysInMonth.map(_.getDate().toString), rowHeadings, Map())
  }

  def apply(shifts: ShiftAssignments,
            terminalPageTab: TerminalPageTabLoc,
            router: RouterCtl[Loc]): Unmounted[Props, State, Unit] = component(Props(shifts, terminalPageTab, router))
}
