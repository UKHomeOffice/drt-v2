package actors

import actors.Sizes.oneMegaByte
import akka.persistence._
import akka.stream.scaladsl.SourceQueueWithComplete
import com.trueaccord.scalapb.GeneratedMessage
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.{MilliDate, SDateLike, ShiftAssignments, StaffAssignment}
import org.slf4j.{Logger, LoggerFactory}
import server.protobuf.messages.ShiftMessage.{ShiftMessage, ShiftStateSnapshotMessage, ShiftsMessage}
import services.SDate
import services.graphstages.{Crunch, StaffAssignmentHelper}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success, Try}

case class ShiftsState(shifts: ShiftAssignments) {
  def updated(data: ShiftAssignments): ShiftsState = copy(shifts = data)
}

case object GetState

case object GetFeedStatuses

case class GetPortState(from: MillisSinceEpoch, to: MillisSinceEpoch)

case class GetUpdatesSince(millis: MillisSinceEpoch, from: MillisSinceEpoch, to: MillisSinceEpoch)

case object GetShifts

case class AddShiftLikeSubscribers(subscribers: List[SourceQueueWithComplete[String]])

class ShiftsActor(now: () => SDateLike) extends ShiftsActorBase(now) {
  var subscribers: List[SourceQueueWithComplete[String]] = List()

  override def onUpdateState(data: ShiftAssignments): Unit = {
    log.info(s"Telling subscribers ($subscribers) about updated shifts")

    subscribers.foreach(s => {
      s.offer(StaffAssignmentHelper.staffAssignmentsToString(data.assignments)).onComplete {
        case Success(qor) => log.info(s"update queued successfully with subscriber: $qor")
        case Failure(t) => log.info(s"update failed to queue with subscriber: $t")
      }
    })
  }

  val subsReceive: Receive = {
    case AddShiftLikeSubscribers(newSubscribers) =>
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

class ShiftsActorBase(now: () => SDateLike) extends RecoveryActorLike with PersistentDrtActor[ShiftsState] {
  val log: Logger = LoggerFactory.getLogger(getClass)

  override def persistenceId = "shifts-store"

  var state = initialState

  def initialState = ShiftsState(ShiftAssignments.empty)

  val snapshotInterval = 1
  override val snapshotBytesThreshold: Int = oneMegaByte

  import ShiftsMessageParser._

  override def stateToMessage: GeneratedMessage = ShiftStateSnapshotMessage(staffAssignmentsToShiftsMessages(state.shifts, now()))

  def updateState(shifts: ShiftAssignments): Unit = state = state.updated(data = shifts)

  def onUpdateState(data: ShiftAssignments): Unit = {}

  def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case snapshot: ShiftStateSnapshotMessage => state = ShiftsState(shiftMessagesToStaffAssignments(snapshot.shifts))
  }

  def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case shiftsMessage: ShiftsMessage =>
      log.info(s"Recovery: ShiftsMessage received with ${shiftsMessage.shifts.length} shifts")
      val shifts = shiftMessagesToStaffAssignments(shiftsMessage.shifts)
      updateState(shifts)
  }

  def receiveCommand: Receive = {
    case GetState =>
      log.info(s"GetState received")
      sender() ! state.shifts

    case shiftStaffAssignments: ShiftAssignments if shiftStaffAssignments != state.shifts =>
      updateState(shiftStaffAssignments)
      onUpdateState(shiftStaffAssignments)

      log.info(s"Fixed points updated. Saving snapshot")
      val snapshotMessage = ShiftStateSnapshotMessage(staffAssignmentsToShiftsMessages(state.shifts, now()))
      saveSnapshot(snapshotMessage)

    case shiftStaffAssignments: ShiftAssignments if shiftStaffAssignments == state.shifts =>
      log.info(s"No changes to fixed points. Not persisting")

    case SaveSnapshotSuccess(md) =>
      log.info(s"Save snapshot success: $md")

    case SaveSnapshotFailure(md, cause) =>
      log.info(s"Save snapshot failure: $md, $cause")

    case u =>
      log.info(s"unhandled message: $u")
  }
}

object ShiftsMessageParser {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def staffAssignmentToMessage(assignment: StaffAssignment, createdAt: SDateLike): ShiftMessage = ShiftMessage(
    name = Option(assignment.name),
    terminalName = Option(assignment.terminalName),
    numberOfStaff = Option(assignment.numberOfStaff.toString),
    startTimestamp = Option(assignment.startDt.millisSinceEpoch),
    endTimestamp = Option(assignment.endDt.millisSinceEpoch),
    createdAt = Option(createdAt.millisSinceEpoch)
  )

  def shiftMessageToStaffAssignmentv1(shiftMessage: ShiftMessage): Option[StaffAssignment] = {
    val maybeSt: Option[SDateLike] = parseDayAndTimeToSdate(shiftMessage.startDayOLD, shiftMessage.startTimeOLD)
    val maybeEt: Option[SDateLike] = parseDayAndTimeToSdate(shiftMessage.startDayOLD, shiftMessage.endTimeOLD)
    for {
      startDt <- maybeSt
      endDt <- maybeEt
    } yield StaffAssignment(
      name = shiftMessage.name.getOrElse(""),
      terminalName = shiftMessage.terminalName.getOrElse(""),
      startDt = MilliDate(startDt.millisSinceEpoch),
      endDt = MilliDate(endDt.millisSinceEpoch),
      numberOfStaff = shiftMessage.numberOfStaff.getOrElse("0").toInt,
      createdBy = None
    )
  }

  def parseDayAndTimeToSdate(maybeDay: Option[String], maybeTime: Option[String]): Option[SDateLike] = {
    val maybeDayMonthYear = maybeDay.getOrElse("1/1/0").split("/") match {
      case Array(d, m, y) => Try((d.toInt, m.toInt, y.toInt + 2000)).toOption
      case _ => None
    }
    val maybeHourMinute = maybeTime.getOrElse("00:00").split(":") match {
      case Array(a, b) => Try((a.toInt, b.toInt)).toOption
      case _ => None
    }

    for {
      (d, m, y) <- maybeDayMonthYear
      (hr, min) <- maybeHourMinute
    } yield SDate(y, m, d, hr, min, Crunch.europeLondonTimeZone)
  }

  def shiftMessageToStaffAssignmentv2(shiftMessage: ShiftMessage): Option[StaffAssignment] = Option(StaffAssignment(
    name = shiftMessage.name.getOrElse(""),
    terminalName = shiftMessage.terminalName.getOrElse(""),
    startDt = MilliDate(shiftMessage.startTimestamp.getOrElse(0L)),
    endDt = MilliDate(shiftMessage.endTimestamp.getOrElse(0L)),
    numberOfStaff = shiftMessage.numberOfStaff.getOrElse("0").toInt,
    createdBy = None
  ))

  def staffAssignmentsToShiftsMessages(shiftStaffAssignments: ShiftAssignments, createdAt: SDateLike): Seq[ShiftMessage] =
    shiftStaffAssignments.assignments.map(a => staffAssignmentToMessage(a, createdAt))

  def shiftMessagesToStaffAssignments(shiftMessages: Seq[ShiftMessage]): ShiftAssignments =
    ShiftAssignments(shiftMessages.collect {
      case sm@ShiftMessage(Some(_), Some(_), Some(_), Some(_), Some(_), Some(_), None, None, _) => shiftMessageToStaffAssignmentv1(sm)
      case sm@ShiftMessage(Some(_), Some(_), None, None, None, Some(_), Some(_), Some(_), _) => shiftMessageToStaffAssignmentv2(sm)
    }.flatten)
}
