package drt.client.components

import japgolly.scalajs.react.{Children, JsFnComponent}
import japgolly.scalajs.react.vdom.VdomElement

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import scala.scalajs.js.JSConverters._


@js.native
trait ShiftDate extends js.Object {
  var year: Int = js.native
  var month: Int = js.native
  var day: Int = js.native
  var hour: Int = js.native
  var minute: Int = js.native
}

object ShiftDate {
  def apply(year: Int, month: Int, day: Int, hour: Int, minute: Int): ShiftDate = {
    val p = (new js.Object).asInstanceOf[ShiftDate]
    p.year = year
    p.month = month
    p.day = day
    p.hour = hour
    p.minute = minute
    //    println(s"ShiftDate.apply: $p")
    p
  }
}

@js.native
trait DefaultShift extends js.Object {
  var name: String
  var defaultStaffNumber: Int
  var startTime: String
  var endTime: String
}

object DefaultShift {
  def apply(name: String, defaultStaffNumber: Int, startTime: String, endTime: String): DefaultShift = {
    val p = (new js.Object).asInstanceOf[DefaultShift]
    p.name = name
    p.defaultStaffNumber = defaultStaffNumber
    p.startTime = startTime
    p.endTime = endTime
    //    println(s"DefaultShift.apply: $p")
    p
  }
}

@js.native
trait ShiftAssignment extends js.Object {
  var column: Int
  var row: Int
  var name: String
  var staffNumber: Int
  var startTime: ShiftDate
  var endTime: ShiftDate
}

object ShiftAssignment {
  def apply(column: Int, row: Int, name: String, staffNumber: Int, startTime: ShiftDate, endTime: ShiftDate): ShiftAssignment = {
    val p = (new js.Object).asInstanceOf[ShiftAssignment]
    p.column = column
    p.row = row
    p.name = name
    p.staffNumber = staffNumber
    p.startTime = startTime
    p.endTime = endTime
    //    println(s"ShiftAssignment.apply: $p")
    p
  }
}

@js.native
trait ShiftData extends js.Object {
  var index: Int
  var defaultShift: DefaultShift
  var assignments: js.Array[ShiftAssignment]
}

object ShiftData {
  def apply(index: Int, defaultShift: DefaultShift, assignments: Seq[ShiftAssignment]): ShiftData = {
    val p = (new js.Object).asInstanceOf[ShiftData]
    p.index = index
    p.defaultShift = defaultShift
    p.assignments = assignments.toJSArray
    //    println(s"ShiftData.apply: $p")
    p
  }
}

@js.native
trait ShiftHotTableViewProps extends js.Object {
  var month: Int = js.native
  var year: Int = js.native
  var interval: Int = js.native
  var initialShifts: js.Array[ShiftData] = js.native
  var handleSaveChanges: js.Function2[js.Array[ShiftData], js.Array[ShiftAssignment], Unit] = js.native
}

object ShiftHotTableViewProps {
  def apply(month: Int, year: Int, interval: Int, initialShifts: Seq[ShiftData], handleSaveChanges: (Seq[ShiftData], Seq[ShiftAssignment]) => Unit): ShiftHotTableViewProps = {
    val p = (new js.Object).asInstanceOf[ShiftHotTableViewProps]
    p.month = month
    p.year = year
    p.interval = interval
    p.initialShifts = initialShifts.toJSArray
    p.handleSaveChanges = (shifts: js.Array[ShiftData], changedAssignments: js.Array[ShiftAssignment]) => handleSaveChanges(shifts.toSeq, changedAssignments.toSeq)
    p
  }
}

object ShiftHotTableViewComponent {
  @js.native
  @JSImport("@drt/drt-react", "ShiftHotTableView")
  object RawComponent extends js.Object

  val component = JsFnComponent[ShiftHotTableViewProps, Children.None](RawComponent)

  def apply(props: ShiftHotTableViewProps): VdomElement = component(props)

}

