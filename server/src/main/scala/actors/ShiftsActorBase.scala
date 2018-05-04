package actors

import akka.actor.ActorLogging
import akka.persistence._
import akka.stream.scaladsl.SourceQueueWithComplete
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.{MilliDate, SDateLike}
import org.joda.time.format.DateTimeFormat
import org.slf4j.LoggerFactory
import server.protobuf.messages.ShiftMessage.{ShiftMessage, ShiftStateSnapshotMessage, ShiftsMessage}
import services.SDate

import scala.util.Try

case class ShiftsState(shifts: String) {
  def updated(data: String): ShiftsState = copy(shifts = data)
}

case object GetState

case class GetPortState(from: MillisSinceEpoch, to: MillisSinceEpoch)

case class GetUpdatesSince(millis: MillisSinceEpoch, from: MillisSinceEpoch, to: MillisSinceEpoch)

case object GetShifts

case class AddShiftLikeSubscribers(subscribers: List[SourceQueueWithComplete[String]])

class ShiftsActor extends ShiftsActorBase {
  var subscribers: List[SourceQueueWithComplete[String]] = List()

  override def onUpdateState(data: String): Unit = {
    log.info(s"Telling subscribers about updated shifts")
    subscribers.map(_.offer(data))
  }

  val subsReceive: Receive = {
    case AddShiftLikeSubscribers(newSubscribers) =>
      log.info(s"AddShiftLikeSubscribers matched")
      subscribers = newSubscribers.foldLeft(subscribers) {
        case (soFar, newSub: SourceQueueWithComplete[String]) =>
          log.info(s"Adding shifts subscriber $newSub")
          newSub :: soFar
      }
  }

  override def receiveCommand: Receive = {
    subsReceive orElse super.receiveCommand
  }
}

class ShiftsActorBase extends PersistentActor with ActorLogging {

  override def persistenceId = "shifts-store"

  var state = ShiftsState("")

  val snapshotInterval = 1

  import ShiftsMessageParser._

  def updateState(shifts: String): Unit = {
    state = state.updated(data = shifts)
  }

  def onUpdateState(data: String) = {}

  val receiveRecover: Receive = {
    case shiftsMessage: ShiftsMessage =>
      log.info(s"Recovery: ShiftsMessage received with ${shiftsMessage.shifts.length} shifts")
      val shifts = shiftMessagesToShiftsString(shiftsMessage.shifts.toList)
      updateState(shifts)

    case SnapshotOffer(md, snapshot: ShiftStateSnapshotMessage) =>
      log.info(s"Recovery: received SnapshotOffer from ${SDate(md.timestamp).toLocalDateTimeString()} with ${snapshot.shifts.length} shifts")
      state = ShiftsState(shiftMessagesToShiftsString(snapshot.shifts.toList))

    case RecoveryCompleted =>
      log.info("RecoveryCompleted")

    case u =>
      log.info(s"Recovery: received unexpected ${u.getClass}")
  }

  def receiveCommand: Receive = {
    case GetState =>
      log.info(s"GetState received")
      sender() ! state.shifts

    case data: String =>
      if (data != state.shifts) {
        updateState(data)
        onUpdateState(data)
        val createdAt = SDate.now()
        log.info(s"Shifts updated. Saving snapshot")
        saveSnapshot(ShiftStateSnapshotMessage(shiftsStringToShiftMessages(state.shifts, createdAt)))
      } else {
        log.info(s"No changes to shifts. Not persisting")
      }

    case SaveSnapshotSuccess(md) =>
      log.info(s"Save snapshot success: $md")

    case SaveSnapshotFailure(md, cause) =>
      log.info(s"Save snapshot failure: $md, $cause")

    case u =>
      log.info(s"unhandled message: $u")
  }
}


object ShiftsMessageParser {

  def dateAndTimeToMillis(date: String, time: String): Option[Long] = {

    val formatter = DateTimeFormat.forPattern("dd/MM/yy HH:mm")
    Try {
      formatter.parseMillis(date + " " + time)
    }.toOption
  }

  def dateString(timestamp: Long) = {
    import services.SDate.implicits._

    MilliDate(timestamp).ddMMyyString
  }

  def timeString(timestamp: Long) = {
    import services.SDate.implicits._

    val date = MilliDate(timestamp)

    f"${date.getHours()}%02d:${date.getMinutes()}%02d"
  }

  def startAndEndTimestamps(startDate: String, startTime: String, endTime: String): (Option[Long], Option[Long]) = {
    val startMillis = dateAndTimeToMillis(startDate, startTime)
    val endMillis = dateAndTimeToMillis(startDate, endTime)

    val oneDay = 60 * 60 * 24 * 1000L

    (startMillis, endMillis) match {
      case (Some(start), Some(end)) =>
        if (start <= end)
          (Some(start), Some(end))
        else
          (Some(start), Some(end + oneDay))
      case _ => (None, None)
    }
  }

  val log = LoggerFactory.getLogger(getClass)

  def shiftStringToShiftMessage(shift: String, createdAt: SDateLike): Option[ShiftMessage] = {
    shift.replaceAll("([^\\\\]),", "$1\",\"").split("\",\"").toList.map(_.trim) match {
      case List(description, terminalName, startDay, startTime, endTime, staffNumberDelta) =>
        val (startTimestamp, endTimestamp) = startAndEndTimestamps(startDay, startTime, endTime)
        Some(ShiftMessage(
          name = Some(description),
          terminalName = Some(terminalName),
          startTimestamp = startTimestamp,
          endTimestamp = endTimestamp,
          numberOfStaff = Some(staffNumberDelta),
          createdAt = Option(createdAt.millisSinceEpoch)
        ))
      case _ =>
        log.warn(s"Couldn't parse shifts line: '$shift'")
        None
    }
  }

  def shiftsStringToShiftsMessage(shifts: String, createdAt: SDateLike): ShiftsMessage = {
    ShiftsMessage(shiftsStringToShiftMessages(shifts, createdAt))
  }

  def shiftsStringToShiftMessages(shifts: String, createdAt: SDateLike): List[ShiftMessage] = {
    shifts.split("\n").map((shift: String) => shiftStringToShiftMessage(shift, createdAt)).collect { case Some(x) => x }.toList
  }

  def shiftMessagesToShiftsString(shiftMessages: List[ShiftMessage]): String = {
    shiftMessages.collect {
      case ShiftMessage(Some(name), Some(terminalName), Some(startDay), Some(startTime), Some(endTime), Some(numberOfStaff), None, None, _) =>
        s"$name, $terminalName, $startDay, $startTime, $endTime, $numberOfStaff"
      case ShiftMessage(Some(name), Some(terminalName), None, None, None, Some(numberOfStaff), Some(startTimestamp), Some(endTimestamp), _) =>
        s"$name, $terminalName, ${ShiftsMessageParser.dateString(startTimestamp)}, ${ShiftsMessageParser.timeString(startTimestamp)}, ${ShiftsMessageParser.timeString(endTimestamp)}, $numberOfStaff"
    }.mkString("\n")
  }
}
