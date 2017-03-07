package actors

import akka.persistence._
import server.protobuf.messages.ShiftMessage.{ShiftMessage, ShiftsMessage}
import org.slf4j.LoggerFactory

case class ShiftsState(events: List[String] = Nil) {
  def updated(data: String): ShiftsState = copy(data :: events)

  def size: Int = events.length

  override def toString: String = events.reverse.toString
}

case object GetState

object ShiftsMessageParser {
  val log = LoggerFactory.getLogger(getClass)

  def shiftStringToShiftMessage(shift: String): Option[ShiftMessage] = {
    shift.replaceAll("([^\\\\]),", "$1\",\"").split("\",\"").toList.map(_.trim) match {
      case List(description, terminalName, startDay, startTime, endTime, staffNumberDelta) =>
        Some(ShiftMessage(
          Some(description),
          Some(terminalName),
          Some(startDay),
          Some(startTime),
          Some(endTime),
          Some(staffNumberDelta)
        ))
      case _ =>
        log.warn(s"Couldn't parse shifts line: '$shift'")
        None
    }
  }

  def shiftsStringToShiftsMessage(shifts: String): ShiftsMessage = {
    val shiftMessages = shifts.split("\n").map(shiftStringToShiftMessage).collect{ case Some(x) => x }
    ShiftsMessage(shiftMessages)
  }

  def shiftsMessageToShiftsString(shiftsMessage: ShiftsMessage) = {
    shiftsMessage.shifts.map((sm: ShiftMessage) => {
      val shiftString = for {
        name <- sm.name
        terminalName <- sm.terminalName
        startDay <- sm.startDay
        startTime <- sm.startTime
        endTime <- sm.endTime
        numberOfStaff <- sm.numberOfStaff
      } yield s"$name, $terminalName, $startDay, $startTime, $endTime, $numberOfStaff"
      shiftString.getOrElse(List())
    }).mkString("\n")
  }
}

class ShiftsActor extends PersistentActor {

  override def persistenceId = "shifts-store"

  var state = ShiftsState()

  import ShiftsMessageParser._

  def updateState(data: String): Unit = {
    state = state.updated(data)
  }

  val receiveRecover: Receive = {
    case shiftsMessage: ShiftsMessage => updateState(shiftsMessageToShiftsString(shiftsMessage))
    case SnapshotOffer(_, snapshot: ShiftsState) => state = snapshot
  }

  val receiveCommand: Receive = {
    case GetState =>
      sender() ! state.events.headOption.getOrElse("")
    case data: String =>
      persist(shiftsStringToShiftsMessage(data)) { shiftsMessage =>
        updateState(data)
        context.system.eventStream.publish(shiftsMessage)
      }
  }
}
