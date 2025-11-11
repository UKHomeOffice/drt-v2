package drt.client.components

import drt.client.services.JSDateConversions.SDate
import drt.shared.CrunchApi.MillisSinceEpoch
import japgolly.scalajs.react.component.JsFn.Component
import japgolly.scalajs.react.{Children, CtorType, JsFnComponent}
import japgolly.scalajs.react.vdom.VdomElement
import uk.gov.homeoffice.drt.time.MilliTimes.oneMinuteMillis

import scala.concurrent.duration.DurationInt
import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import scala.scalajs.js.JSConverters._

@js.native
trait ShiftDate extends js.Object {
  var day: Int = js.native
  var month: Int = js.native
  var year: Int = js.native
}

object ShiftDate {
  def apply(day: Int, month: Int, year: Int): ShiftDate = {
    val p = (new js.Object).asInstanceOf[ShiftDate]
    p.day = day
    p.month = month
    p.year = year
    p
  }
}

@js.native
trait ShiftDateTime extends js.Object {
  var year: Int = js.native
  var month: Int = js.native
  var day: Int = js.native
  var hour: Int = js.native
  var minute: Int = js.native
}

object ShiftDateTime {
  def toString(shiftDateTime: ShiftDateTime): String = {
    s"${shiftDateTime.year}-${shiftDateTime.month}-${shiftDateTime.day} ${shiftDateTime.hour}:${shiftDateTime.minute}"
  }

  def isEqual(shiftDateTime1: ShiftDateTime, shiftDateTime2: ShiftDateTime): Boolean = {
    shiftDateTime1.year == shiftDateTime2.year &&
      shiftDateTime1.month == shiftDateTime2.month &&
      shiftDateTime1.day == shiftDateTime2.day &&
      shiftDateTime1.hour == shiftDateTime2.hour &&
      shiftDateTime1.minute == shiftDateTime2.minute
  }

  def apply(year: Int, month: Int, day: Int, hour: Int, minute: Int): ShiftDateTime = {
    val p = (new js.Object).asInstanceOf[ShiftDateTime]
    p.year = year
    p.month = month
    p.day = day
    p.hour = hour
    p.minute = minute
    p
  }
}

@js.native
trait ShiftSummary extends js.Object {
  var name: String
  var defaultStaffNumber: Int
  var startTime: String
  var endTime: String
  var startDate: ShiftDate
  var endDate: js.UndefOr[ShiftDate]
}

object ShiftSummary {
  def apply(name: String, defaultStaffNumber: Int, startTime: String, endTime: String, startDate: ShiftDate, endDate: Option[ShiftDate]): ShiftSummary = {
    val p = (new js.Object).asInstanceOf[ShiftSummary]
    p.name = name
    p.defaultStaffNumber = defaultStaffNumber
    p.startTime = startTime
    p.endTime = endTime
    p.startDate = startDate
    p.endDate = endDate.orUndefined
    p
  }
}

@js.native
trait StaffTableEntry extends js.Object {
  var column: Int
  var row: Int
  var name: String
  var staffRecommendation: Int
  var staffNumber: Int
  var startTime: ShiftDateTime
  var endTime: ShiftDateTime
}

object StaffTableEntry {
  private def shiftDateToSDate(shiftDate: ShiftDateTime) = {
    SDate(shiftDate.year, shiftDate.month, shiftDate.day, shiftDate.hour, shiftDate.minute)
  }

  private def sDateToShiftDate(date: MillisSinceEpoch) = {
    val s_date = SDate(date)
    ShiftDateTime(s_date.getFullYear, s_date.getMonth, s_date.getDate, s_date.getHours, s_date.getMinutes)
  }

  def splitIntoSlots(tableEntry: StaffTableEntry, slotMinutes: Int): Seq[StaffTableEntry] =
    (shiftDateToSDate(tableEntry.startTime).millisSinceEpoch until shiftDateToSDate(tableEntry.endTime).millisSinceEpoch
      by slotMinutes.minutes.toMillis).map(start =>
      StaffTableEntry(
        column = tableEntry.column,
        row = tableEntry.row,
        name = tableEntry.name,
        staffRecommendation = tableEntry.staffRecommendation,
        staffNumber = tableEntry.staffNumber,
        startTime = sDateToShiftDate(start),
        endTime = sDateToShiftDate(start + (slotMinutes.minutes.toMillis - oneMinuteMillis)
        )
      )
    )

  def apply(column: Int, row: Int, name: String, staffRecommendation: Int, staffNumber: Int, startTime: ShiftDateTime, endTime: ShiftDateTime): StaffTableEntry = {
    val p = (new js.Object).asInstanceOf[StaffTableEntry]
    p.column = column
    p.row = row
    p.name = name
    p.staffRecommendation = staffRecommendation
    p.staffNumber = staffNumber
    p.startTime = startTime
    p.endTime = endTime
    p
  }
}

@js.native
trait ShiftSummaryStaffing extends js.Object {
  var index: Int
  var shiftSummary: ShiftSummary
  var staffTableEntries: js.Array[StaffTableEntry]
}

object ShiftSummaryStaffing {
  def apply(index: Int, shiftSummary: ShiftSummary, staffTableEntries: Seq[StaffTableEntry]): ShiftSummaryStaffing = {
    val p = (new js.Object).asInstanceOf[ShiftSummaryStaffing]
    p.index = index
    p.shiftSummary = shiftSummary
    p.staffTableEntries = staffTableEntries.toJSArray
    p
  }
}

@js.native
trait ShiftHotTableViewProps extends js.Object {
  var shiftDate: ShiftDate = js.native
  var viewPeriod: String = js.native
  var intervalMinutes: Int = js.native
  var shiftSummaries: js.Array[ShiftSummaryStaffing] = js.native
  var handleSaveChanges: js.Function2[js.Array[ShiftSummaryStaffing], js.Array[StaffTableEntry], Unit] = js.native
  var handleEditShift: js.Function2[Int, ShiftSummary, Unit] = js.native
  var sendAnalyticsEvent: js.Function1[IAnalyticsEvent, Unit] = js.native
}

object ShiftHotTableViewProps {
  def apply(shiftDate: ShiftDate,
            viewPeriod: String,
            interval: Int,
            shiftSummaries: Seq[ShiftSummaryStaffing],
            handleSaveChanges: (Seq[ShiftSummaryStaffing], Seq[StaffTableEntry]) => Unit,
            handleEditShift: (Int, ShiftSummary) => Unit,
            sendAnalyticsEvent: js.Function1[IAnalyticsEvent, Unit]
           ): ShiftHotTableViewProps = {
    val p = (new js.Object).asInstanceOf[ShiftHotTableViewProps]
    p.shiftDate = shiftDate
    p.viewPeriod = viewPeriod
    p.intervalMinutes = interval
    p.shiftSummaries = shiftSummaries.toJSArray
    p.handleSaveChanges = (shifts: js.Array[ShiftSummaryStaffing],
                           changedAssignments: js.Array[StaffTableEntry]) => handleSaveChanges(shifts.toSeq, changedAssignments.toSeq)
    p.handleEditShift = (index: Int, shiftSummary: ShiftSummary) => handleEditShift(index, shiftSummary)
    p.sendAnalyticsEvent = sendAnalyticsEvent
    p
  }
}

object ShiftHotTableViewComponent {
  @js.native
  @JSImport("@drt/drt-react", "ShiftHotTableView")
  object RawComponent extends js.Object

  val component: Component[ShiftHotTableViewProps, CtorType.Props] = JsFnComponent[ShiftHotTableViewProps, Children.None](RawComponent)

  def apply(props: ShiftHotTableViewProps): VdomElement = component(props)

}

