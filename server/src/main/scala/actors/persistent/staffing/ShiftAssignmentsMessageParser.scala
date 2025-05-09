package actors.persistent.staffing

import drt.shared.{ShiftAssignments, StaffAssignment, StaffAssignmentLike}
import org.slf4j.{Logger, LoggerFactory}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.protobuf.messages.ShiftMessage.ShiftMessage
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}
import uk.gov.homeoffice.drt.time.TimeZoneHelper.europeLondonTimeZone

import scala.util.Try

object ShiftAssignmentsMessageParser {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def staffAssignmentToMessage(assignment: StaffAssignmentLike, createdAt: SDateLike): ShiftMessage = ShiftMessage(
    name = Option(assignment.name),
    terminalName = Option(assignment.terminal.toString),
    numberOfStaff = Option(assignment.numberOfStaff.toString),
    startTimestamp = Option(assignment.start),
    endTimestamp = Option(assignment.end),
    createdAt = Option(createdAt.millisSinceEpoch)
  )

  private def shiftMessageToStaffAssignmentv1(shiftMessage: ShiftMessage): Option[StaffAssignment] = {
    val maybeSt: Option[SDateLike] = parseDayAndTimeToSdate(shiftMessage.startDayOLD, shiftMessage.startTimeOLD)
    val maybeEt: Option[SDateLike] = parseDayAndTimeToSdate(shiftMessage.startDayOLD, shiftMessage.endTimeOLD)
    for {
      startDt <- maybeSt
      endDt <- maybeEt
    } yield {
      StaffAssignment(
        name = shiftMessage.name.getOrElse(""),
        terminal = Terminal(shiftMessage.terminalName.getOrElse("")),
        start = startDt.roundToMinute().millisSinceEpoch,
        end = endDt.roundToMinute().millisSinceEpoch,
        numberOfStaff = shiftMessage.numberOfStaff.getOrElse("0").toInt,
        createdBy = None
      )
    }
  }

  private def parseDayAndTimeToSdate(maybeDay: Option[String], maybeTime: Option[String]): Option[SDateLike] = {
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
    } yield SDate(y, m, d, hr, min, europeLondonTimeZone)
  }

  private def shiftMessageToStaffAssignmentv2(shiftMessage: ShiftMessage): Option[StaffAssignment] = Option(StaffAssignment(
    name = shiftMessage.name.getOrElse(""),
    terminal = Terminal(shiftMessage.terminalName.getOrElse("")),
    start = shiftMessage.startTimestamp.getOrElse(0L),
    end = shiftMessage.endTimestamp.getOrElse(0L),
    numberOfStaff = shiftMessage.numberOfStaff.getOrElse("0").toInt,
    createdBy = None
  ))

  def shiftAssignmentsToShiftsMessages(shiftAssignments: ShiftAssignments,
                                       createdAt: SDateLike): Seq[ShiftMessage] =
    shiftAssignments.assignments.map(a => staffAssignmentToMessage(a, createdAt))

  def shiftMessagesToShiftAssignments(shiftMessages: Seq[ShiftMessage]): ShiftAssignments =
    ShiftAssignments(shiftMessages.collect {
      case sm@ShiftMessage(Some(_), Some(_), Some(_), Some(_), Some(_), Some(_), None, None, _) => shiftMessageToStaffAssignmentv1(sm)
      case sm@ShiftMessage(Some(_), Some(_), None, None, None, Some(_), Some(_), Some(_), _) => shiftMessageToStaffAssignmentv2(sm)
    }.flatten)
}
