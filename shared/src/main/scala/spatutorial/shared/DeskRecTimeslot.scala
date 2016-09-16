package spatutorial.shared

import boopickle.Default._

//sealed trait TodoPriority
//
//case object TodoLow extends TodoPriority
//
//case object TodoNormal extends TodoPriority
//
//case object TodoHigh extends TodoPriority

case class DeskRecTimeslot(id: String, timeStamp: Int, deskRec: Int)

//object TodoPriority {
//  implicit val todoPriorityPickler: Pickler[TodoPriority] = generatePickler[TodoPriority]
//}
