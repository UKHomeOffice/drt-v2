package drt.client.components

import drt.client.SPAMain.{Loc, TerminalPageTabLoc}
import drt.client.actions.Actions.SaveMonthTimeSlotsToShifts
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.services.JSDateConversions.SDate
import drt.client.services.{SPACircuit, StaffAssignmentParser, StaffAssignmentServiceWithDates}
import drt.shared.{SDateLike, StaffTimeSlot, StaffTimeSlotsForTerminalMonth}
import japgolly.scalajs.react._
import japgolly.scalajs.react.component.Scala.Unmounted
import japgolly.scalajs.react.extra.Reusability
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.TagOf
import japgolly.scalajs.react.vdom.html_<^._
import org.scalajs.dom.html.Select
import org.scalajs.dom.window.confirm

import scala.collection.immutable.Seq
import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import scala.util.{Failure, Success, Try}

object HotTable {

  val log: Logger = LoggerFactory.getLogger("TerminalStaffing")

  @JSImport("react-handsontable", JSImport.Default)
  @js.native
  object RawComponent extends js.Object

  @js.native
  trait Props extends js.Object {
    var settings: js.Dictionary[js.Any] = js.native
  }

  def props(
             data: Seq[Seq[AnyVal]],
             colHeadings: Seq[String],
             rowHeadings: Seq[String],
             changeCallback: (Int, Int, Int) => Unit,
             colWidths: String = "2em"
           ): Props = {
    import js.JSConverters._
    val p = (new js.Object).asInstanceOf[Props]
    val afterChange = (changes: js.Array[js.Array[Any]], _: String) => {
      val maybeArray = Option(changes)
      maybeArray.foreach(
        c => {
          c.toList.foreach(change =>
            (change(0), change(1), change(3)) match {
              case (row: Int, col: Int, value: String) =>
                Try(Integer.parseInt(value)) match {
                  case Success(v) =>
                    changeCallback(row, col, v)
                  case Failure(f) =>
                    log.warn(s"Couldn't parse $value to an Integer $f")
                }
              case (row: Int, col: Int, value: Int) =>
                changeCallback(row, col, value)
              case other =>
                log.error(s"couldn't match $other")
            }
          )
        })
      if (maybeArray.isEmpty) {
        log.info(s"Called change function with no values")
      }
    }

    p.settings = js.Dictionary(
      "data" -> data.map(_.toJSArray).toJSArray,
      "rowHeaders" -> rowHeadings.toJSArray,
      "colHeaders" -> colHeadings.toJSArray,
      "afterChange" -> afterChange,
      "colWidth" -> colWidths
    )
    p
  }

  val component = JsComponent[Props, Children.None, Null](RawComponent)
}

object TerminalStaffingV2 {

  case class TimeSlotDay(timeSlot: Int, day: Int) {
    def key = s"$timeSlot-$day"
  }

  case class State(
                    timeSlots: Seq[Seq[Int]],
                    colHeadings: Seq[String],
                    rowHeadings: Seq[String],
                    changes: Map[String, Int]
                  )

  val log: Logger = LoggerFactory.getLogger(getClass.getName)

  case class Props(
                    rawShiftString: String,
                    terminalPageTab: TerminalPageTabLoc,
                    router: RouterCtl[Loc]
                  ) {
    def timeSlotMinutes: Int = Try(terminalPageTab.subMode.toInt).toOption.getOrElse(15)
  }

  def staffToStaffTimeSlotsForMonth(month: SDateLike, staff: Seq[Seq[Int]], terminal: String, slotMinutes: Int): StaffTimeSlotsForTerminalMonth = {
    val timeSlots = staffTimeSlotSeqToStaffTimeSlots(month, staff, terminal, slotMinutes)
    StaffTimeSlotsForTerminalMonth(month.millisSinceEpoch, terminal, timeSlots)
  }

  def staffTimeSlotSeqToStaffTimeSlots(month: SDateLike, staffDays: Seq[Seq[Int]], terminal: String, slotMinutes: Int): Seq[StaffTimeSlot] =
    staffDays.zipWithIndex
      .flatMap {
        case (days, timeSlotIndex) =>
          days.zipWithIndex.collect {
            case (staffInSlotForDay, dayIndex) if staffInSlotForDay != 0 =>
              val slotStartUtc = month.addDays(dayIndex).addMinutes(timeSlotIndex * slotMinutes).getUtcMillis()
              StaffTimeSlot(terminal, slotStartUtc, staffInSlotForDay, slotMinutes * 60000)
          }

      }
      .sortBy(_.start)


  def updateTimeSlot(timeSlots: Seq[Seq[Int]], slot: Int, day: Int, value: Int): Seq[Seq[Int]] =
    timeSlots.updated(slot, timeSlots(day).updated(day, value))

  def slotsInDay(date: SDateLike, slotDuration: Int): Seq[SDateLike] = {
    val minutesInDay = 24 * 60
    val startOfDay = SDate(y = date.getFullYear(), m = date.getMonth(), d = date.getDate())
    val slots = minutesInDay / slotDuration
    List.tabulate(slots)(i => startOfDay.addMinutes(i * slotDuration))
  }

  def drawSelect(values: Seq[String], names: Seq[String], defaultValue: String, callback: ((ReactEventFromInput) => Callback)): TagOf[Select] = {
    val valueNames = values.zip(names)
    <.select(^.className := "form-control", ^.defaultValue := defaultValue.toString,
      ^.onChange ==> callback,
      valueNames.map {
        case (value, name) => <.option(^.value := value, s"$name")
      }.toTagMod)
  }

  def firstDayOfMonth(today: SDateLike) = SDate(y = today.getFullYear(), m = today.getMonth(), d = 1)

  def lastDayOfMonth(today: SDateLike): SDateLike = {
    val firstOfMonth: SDateLike = firstDayOfMonth(today)

    val lastDayOfMonth = firstOfMonth.addMonths(1).addDays(-1)
    lastDayOfMonth
  }

  def toTimeSlots(startTime: SDateLike, endTime: SDateLike): Seq[SDateLike] = {
    val numberOfSlots = (endTime.getHours() - startTime.getHours()) * 4
    List.tabulate(numberOfSlots)(i => startTime.addMinutes(i * 15))
  }

  def consecutiveDaysInMonth(startDay: SDateLike, endDay: SDateLike): Seq[SDateLike] = {
    val days = (endDay.getDate() - startDay.getDate()) + 1
    List.tabulate(days)(i => startDay.addDays(i))
  }

  def sixMonthsFromFirstOfMonth(date: SDateLike): Seq[SDateLike] = (0 to 5).map(i => firstDayOfMonth(date).addMonths(i))

  def applyRecordedChangesToShiftState(staffTimeSlotDays: Seq[Seq[Int]], changes: Map[String, Int]): Seq[Seq[Int]] =
    staffTimeSlotDays.zipWithIndex.map {
      case (days, timeslotIndex) =>
        days.zipWithIndex.map {
          case (staff, dayIndex) =>
            changes.get(TimeSlotDay(timeslotIndex, dayIndex).key) match {
              case Some(s) => s
              case None => staff
            }
        }
    }


  def whatDayChanged(startingSlots: Seq[Seq[Int]], updatedSlots: Seq[Seq[Int]]): Set[Int] =
    toDaysWithIndexSet(updatedSlots)
      .diff(toDaysWithIndexSet(startingSlots)).map { case (_, dayIndex) => dayIndex }

  def toDaysWithIndexSet(updatedSlots: Seq[Seq[Int]]): Set[(Seq[Int], Int)] = {
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

  implicit val propsReuse: Reusability[Props] = Reusability.by((_: Props).rawShiftString.hashCode)
  implicit val stateReuse: Reusability[State] = Reusability.always[State]

  val component = ScalaComponent.builder[Props]("StaffingV2")
    .initialStateFromProps(props => {
      stateFromProps(props)
    })
    .renderPS((scope, props, state) => {
      def confirmAndSave(viewingDate: SDateLike) = (_: ReactEventFromInput) =>
        Callback {

          val initialTimeSlots = stateFromProps(props).timeSlots
          val updatedTimeSlots: Seq[Seq[Int]] = applyRecordedChangesToShiftState(state.timeSlots, scope.state.changes)

          val updatedMonth = props.terminalPageTab.dateFromUrlOrNow.getMonthString()
          val changedDays = whatDayChanged(initialTimeSlots, updatedTimeSlots)
            .map(d => state.colHeadings(d)).toList

          if (confirm(s"You have updated staff for ${dateListToString(changedDays)} $updatedMonth - do you want to save these changes?"))
            SPACircuit.dispatch(
              SaveMonthTimeSlotsToShifts(
                staffToStaffTimeSlotsForMonth(
                  viewingDate,
                  updatedTimeSlots,
                  props.terminalPageTab.terminal,
                  props.timeSlotMinutes
                )))
        }

      val viewingDate = firstDayOfMonth(props.terminalPageTab.dateFromUrlOrNow)
      <.div(
        <.div(^.className := "date-picker",
          <.div(^.className := "row",
            List(
              <.div(<.label("Choose Month", ^.className := "text center")),
              <.div(drawSelect(
                values = monthOptions.map(_.toISOString()),
                names = monthOptions.map(d => s"${d.getMonthString()} ${d.getFullYear()}"),
                defaultValue = viewingDate.toISOString(),
                callback = (e: ReactEventFromInput) =>
                  props.router.set(props.terminalPageTab.copy(date = Option(SDate(e.target.value).toISODateOnly)))
              )),
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
          (row, col, value) => {
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
    .componentDidUpdate(_ => Callback.log("Staff updated"))
    .componentDidMount(_ => Callback.log(s"Staff Mounted"))
    .build

  def stateFromProps(props: Props): State = {
    import drt.client.services.JSDateConversions._
    val viewingDate = props.terminalPageTab.dateFromUrlOrNow

    val terminalName = props.terminalPageTab.terminal
    val terminalShifts = StaffAssignmentParser(props.rawShiftString).parsedAssignments.toList.collect {
      case Success(s) if s.terminalName == terminalName => s
    }

    val ss: StaffAssignmentServiceWithDates = StaffAssignmentServiceWithDates(terminalShifts)

    def firstDay = firstDayOfMonth(viewingDate)

    def daysInMonth = consecutiveDaysInMonth(firstDay, lastDayOfMonth(firstDay))

    val timeSlots = slotsInDay(viewingDate, props.timeSlotMinutes)
      .map(slot => {
        daysInMonth.map(day => {
          val slotDateTime = SDate(day.getFullYear(), day.getMonth(), day.getDate(), slot.getHours(), slot.getMinutes())
          ss.terminalStaffAt(terminalName, slotDateTime)
        })
      })

    State(timeSlots, daysInMonth.map(_.getDate().toString), slotsInDay(SDate.now(), props.timeSlotMinutes).map(_.prettyTime()), Map())
  }

  def apply(rawShiftString: String, terminalPageTab: TerminalPageTabLoc, router: RouterCtl[Loc]): Unmounted[Props, State, Unit]
  = component(Props(rawShiftString, terminalPageTab, router))
}
