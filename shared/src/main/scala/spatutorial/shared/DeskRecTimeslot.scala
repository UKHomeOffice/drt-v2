package spatutorial.shared

import boopickle.Default._

//sealed trait TodoPriority
//
//case object TodoLow extends TodoPriority
//
//case object TodoNormal extends TodoPriority
//
//case object TodoHigh extends TodoPriority

case class DeskRecTimeslot(id: String, timeLabel: String, timeStamp: Long, deskRec: Int)

//object TodoPriority {
//  implicit val todoPriorityPickler: Pickler[TodoPriority] = generatePickler[TodoPriority]
//}
