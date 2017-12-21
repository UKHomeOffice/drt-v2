package drt.client.components

import diode.data.Pot
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.services.JSDateConversions.SDate
import drt.client.services.{StaffAssignmentParser, StaffAssignmentServiceWithDates}
import drt.shared.SDateLike
import japgolly.scalajs.react._
import japgolly.scalajs.react.vdom.html_<^._

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import scala.util.{Failure, Success, Try}

object HotTable {

  val log = LoggerFactory.getLogger("TerminalStaffing")

  @JSImport("react-handsontable", JSImport.Default)
  @js.native
  object RawComponent extends js.Object

  @js.native
  trait Props extends js.Object {
    var settings: js.Dictionary[js.Any] = js.native
  }

  def props(data: Seq[Seq[AnyVal]], colHeadings: Seq[String], rowHeadings: Seq[String], changeCallback: (Int, Int, Int) => Unit): Props = {
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
                    log.warn(s"Couldn't parse $value to an Integer")
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
      "afterChange" -> afterChange
    )
    p
  }

  val component = JsComponent[Props, Children.None, Null](RawComponent)
}

object TerminalStaffingV2 {


  case class State(
                    month: Int,
                    timeSlots: Seq[Seq[Int]],
                    colHeadings: Seq[String],
                    rowHeadings: Seq[String]
                  )
  
  val log: Logger = LoggerFactory.getLogger(getClass.getName)

  case class Props(
                    rawShiftString: String
                  )

  def updateTimeSlot(timeSlots: Seq[Seq[Int]], slot: Int, day: Int, value: Int) = {
    timeSlots.updated(slot, timeSlots(day).updated(day, value))
  }


  def slotsInDay(date: SDateLike) = {
    val startOfDay = SDate(date.getFullYear(), date.getMonth(), date.getDate())
    val slots = 24 * 4
    List.tabulate(slots)(i => startOfDay.addMinutes(i * 15))
  }

  val component = ScalaComponent.builder[Props]("StaffingV2")
    .initialStateFromProps(props => {
      import drt.client.services.JSDateConversions._
      def firstDayOfMonth(today: SDateLike) = SDate(today.getFullYear(), today.getMonth(), 1, 0, 0)

      def lastDayOfMonth(today: SDateLike) = {
        val firstOfMonth: SDateLike = firstDayOfMonth(today)

        val lastDayOfMonth = firstOfMonth.addMonths(1).addDays(-1)
        lastDayOfMonth
      }

      def toTimeSlots(startTime: SDateLike, endTime: SDateLike) = {
        val numberOfSlots = (endTime.getHours() - startTime.getHours()) * 4
        List.tabulate(numberOfSlots)(i => startTime.addMinutes(i * 15))
      }

      def consecutiveDaysInMonth(startDay: SDateLike, endDay: SDateLike) = {
        val days = (endDay.getDate() - startDay.getDate()) + 1
        List.tabulate(days)(i => startDay.addDays(i))
      }

      val shifts = StaffAssignmentParser(props.rawShiftString).parsedAssignments.toList.collect {
        case Success(s) => s
      }
      val ss: StaffAssignmentServiceWithDates = StaffAssignmentServiceWithDates(shifts)

      def firstDay = firstDayOfMonth(SDate.now())

      def daysInMonth = consecutiveDaysInMonth(firstDay, lastDayOfMonth(SDate.now()))

      val timeSlots = slotsInDay(SDate.now())
        .map(slot => {
          daysInMonth.map(day => ss.terminalStaffAt("T2", SDate(day.getFullYear(), day.getMonth(), day.getDate(), slot.getHours(), slot.getMinutes())))
        })
      State(12, timeSlots, daysInMonth.map(_.getDate().toString), slotsInDay(SDate.now()).map(_.prettyTime()))
    })
    .renderPS((scope, props, state) => {

      HotTable.component(HotTable.props(
        state.timeSlots,
        colHeadings = state.colHeadings,
        rowHeadings = state.rowHeadings,
        (row, col, value) => {
          scope.modState(state => state.copy(timeSlots = updateTimeSlot(state.timeSlots, row, col, value))).runNow()
        }
      ))
    })
    .componentDidMount(_ => Callback.log(s"Staff Mounted"))
    .build

  def apply(rawShiftString: String) = component(Props(rawShiftString))
}
