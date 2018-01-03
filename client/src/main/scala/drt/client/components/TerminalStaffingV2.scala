package drt.client.components

import drt.client.SPAMain.{Loc, TerminalPageTabLoc}
import drt.client.actions.Actions.SaveMonthTimeSlotsToShifts
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.services.JSDateConversions.SDate
import drt.client.services.{SPACircuit, StaffAssignmentParser, StaffAssignmentServiceWithDates}
import drt.shared.{SDateLike, StaffTimeSlot, StaffTimeSlotsForMonth}
import japgolly.scalajs.react._
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.html_<^._

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
    val afterChange = (changes: js.Array[js.Array[Any]], source: String) => {
      val maybeArray = Option(changes)
      maybeArray.foreach(
        c => {
          c.toList.foreach(change =>
            (change(0), change(1), change(3)) match {
              case (row: Int, col: Int, value: String) =>
                val tryValue = Try(Integer.parseInt(value)) match {
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

  case class State(
                    timeSlots: Seq[Seq[Int]],
                    colHeadings: Seq[String],
                    rowHeadings: Seq[String]
                  )

  val log: Logger = LoggerFactory.getLogger(getClass.getName)

  case class Props(
                    rawShiftString: String,
                    terminalPageTab: TerminalPageTabLoc,
                    router: RouterCtl[Loc]
                  )

  def staffToStaffTimeSlotsForMonth(month: SDateLike, staff: Seq[Seq[Int]], start: SDateLike, terminal: String): StaffTimeSlotsForMonth = {

    StaffTimeSlotsForMonth(month, staff.zipWithIndex.flatMap {
      case (days, timeslotIndex) =>
        days.zipWithIndex.map {
          case (staff, dayIndex) =>
            StaffTimeSlot(terminal, start.addDays(dayIndex).addMinutes(timeslotIndex * 15).millisSinceEpoch, staff)
        }
    }.sortBy(_.start))
  }

  def updateTimeSlot(timeSlots: Seq[Seq[Int]], slot: Int, day: Int, value: Int): Seq[Seq[Int]] = {
    timeSlots.updated(slot, timeSlots(day).updated(day, value))
  }

  def slotsInDay(date: SDateLike): Seq[SDateLike] = {
    val startOfDay = SDate(date.getFullYear(), date.getMonth(), date.getDate())
    val slots = 24 * 4
    List.tabulate(slots)(i => startOfDay.addMinutes(i * 15))
  }

  def drawSelect(values: Seq[String], names: Seq[String], defaultValue: String, callback: ((ReactEventFromInput) => Callback)) = {
    val valueNames = values.zip(names)
    <.select(^.className := "form-control", ^.defaultValue := defaultValue.toString,
      ^.onChange ==> callback,
      valueNames.map {
        case (value, name) => <.option(^.value := value, s"$name")
      }.toTagMod)
  }

  def firstDayOfMonth(today: SDateLike) = SDate(today.getFullYear(), today.getMonth(), 1, 0, 0)

  def lastDayOfMonth(today: SDateLike) = {
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

  def sixMonthsFromFirstOfMonth(date: SDateLike): Seq[SDateLike] = {
    (0 to 5).map(i => firstDayOfMonth(date).addMonths(i))
  }

  def dateFromDateStringOption(dateStringOption: Option[String]) = {
    dateStringOption.map(d => SDate(d)).getOrElse(SDate.now())
  }

  val monthOptions: Seq[SDateLike] = sixMonthsFromFirstOfMonth(SDate.now())

  val component = ScalaComponent.builder[Props]("StaffingV2")
    .initialStateFromProps(props => {
      import drt.client.services.JSDateConversions._
      val viewingDate = dateFromDateStringOption(props.terminalPageTab.date)

      val shifts = StaffAssignmentParser(props.rawShiftString).parsedAssignments.toList.collect {
        case Success(s) => s
      }
      val ss: StaffAssignmentServiceWithDates = StaffAssignmentServiceWithDates(shifts)

      def firstDay = firstDayOfMonth(viewingDate)

      def daysInMonth = consecutiveDaysInMonth(firstDay, lastDayOfMonth(firstDay))

      val timeSlots = slotsInDay(viewingDate)
        .map(slot => {
          daysInMonth.map(day => ss.terminalStaffAt("T2", SDate(day.getFullYear(), day.getMonth(), day.getDate(), slot.getHours(), slot.getMinutes())))
        })

      State(timeSlots, daysInMonth.map(_.getDate().toString), slotsInDay(SDate.now()).map(_.prettyTime()))
    })
    .renderPS((scope, props, state) => {

      val viewingDate = dateFromDateStringOption(props.terminalPageTab.date)
      <.div(
        <.div(^.className := "date-picker",
          <.div(^.className := "row",
            List(
              <.div(^.className := "col-sm-1 no-gutters spacer", <.label("Choose Month", ^.className := "text center")),
              <.div(^.className := "col-sm-1 no-gutters narrower", drawSelect(
                values = monthOptions.map(_.toISOString),
                names = monthOptions.map(d => f"${d.getMonth()}%02d/${d.getFullYear()}"),
                defaultValue = viewingDate.toISOString,
                callback = (e: ReactEventFromInput) =>
                  props.router.set(props.terminalPageTab.copy(date = Option(SDate(e.target.value).toISODateOnly)))
              ))).toTagMod
          )
        ),
        HotTable.component(HotTable.props(
          state.timeSlots,
          colHeadings = state.colHeadings,
          rowHeadings = state.rowHeadings,
          (row, col, value) => {
            scope.modState(state => state.copy(timeSlots = updateTimeSlot(state.timeSlots, row, col, value))).runNow()
          }
        )),
        <.div(^.className := "row",
          <.div(^.className := "col-sm-1 no-gutters",
            <.input.button(^.value := "Save Changes",
              ^.className := "btn btn-primary",
              ^.onClick ==> ((e: ReactEventFromInput) =>
                Callback(
                  SPACircuit.dispatch(
                    SaveMonthTimeSlotsToShifts(
                      staffToStaffTimeSlotsForMonth(
                        viewingDate,
                        state.timeSlots,
                        dateFromDateStringOption(props.terminalPageTab.date),
                        props.terminalPageTab.terminal
                      ))))
                ))
          )
        )
      )
    })
    .componentDidMount(_ => Callback.log(s"Staff Mounted"))
    .build

  def apply(rawShiftString: String, terminalPageTab: TerminalPageTabLoc, router: RouterCtl[Loc])
  = component(Props(rawShiftString, terminalPageTab, router))
}
