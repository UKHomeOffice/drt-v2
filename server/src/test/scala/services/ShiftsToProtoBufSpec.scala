package services

import org.specs2.mutable.Specification
import server.protobuf.messages.ShiftMessage.{ShiftMessage, ShiftsMessage}
import actors.ShiftsMessageParser._
import drt.shared.Terminals.T1
import drt.shared.{MilliDate, ShiftAssignments, StaffAssignment}

class ShiftsToProtoBufSpec extends Specification {

  private val createdAt = SDate.now()

  "shiftStringToShiftMessage" should {
    "take a single shift string and return a case class representing it" in {
      val start = MilliDate(SDate("2017-01-20T10:00").millisSinceEpoch)
      val end = MilliDate(SDate("2017-01-20T20:00").millisSinceEpoch)
      val staffAssignment = StaffAssignment("shift name", T1, start, end, 9, None)
      val shiftMessage = staffAssignmentToMessage(staffAssignment, createdAt)

      val expected = ShiftMessage(
        name = Some("shift name"),
        terminalName = Some(T1.toString),
        startDayOLD = None,
        startTimeOLD = None,
        endTimeOLD = None,
        startTimestamp = Some(1484906400000L),
        endTimestamp = Some(1484942400000L),
        numberOfStaff = Some("9"),
        createdAt = Some(createdAt.millisSinceEpoch)
      )

      shiftMessage === expected
    }
  }

  "shiftsStringToShiftsMessage" should {
    "take some lines of shift strings and return a case class representing all of the shifts" in {
      val start = MilliDate(SDate("2017-01-20T10:00").millisSinceEpoch)
      val end = MilliDate(SDate("2017-01-20T20:00").millisSinceEpoch)
      val staffAssignments = ShiftAssignments(Seq(
        StaffAssignment("shift name", T1, start, end, 5, None),
        StaffAssignment("shift name", T1, start, end, 9, None)))

      val shiftsMessage: Seq[ShiftMessage] = staffAssignmentsToShiftsMessages(staffAssignments, createdAt)

      val expected = Seq(
        ShiftMessage(
          name = Some("shift name"),
          terminalName = Some(T1.toString),
          startTimestamp = Some(1484906400000L),
          endTimestamp = Some(1484942400000L),
          numberOfStaff = Some("5"),
          createdAt = Some(createdAt.millisSinceEpoch)
        ), ShiftMessage(
          name = Some("shift name"),
          terminalName = Some(T1.toString),
          startTimestamp = Some(1484906400000L),
          endTimestamp = Some(1484942400000L),
          numberOfStaff = Some("9"),
          createdAt = Some(createdAt.millisSinceEpoch)
        ))

      shiftsMessage === expected
    }
  }

  "shiftMessagesToShiftsString" should {
    "take a v1 ShiftsMessage and return the multiline string representation" in {
      val shiftsMessage = ShiftsMessage(List(ShiftMessage(
        Some("shift name"), Some(T1.toString), Some("20/01/17"), Some("10:00"), Some("20:00"), Some("5")
      ), ShiftMessage(
        Some("shift name"), Some(T1.toString), Some("20/01/17"), Some("10:00"), Some("20:00"), Some("9")
      )))

      val shiftsString = shiftMessagesToStaffAssignments(shiftsMessage.shifts)

      val start = MilliDate(SDate("2017-01-20T10:00").millisSinceEpoch)
      val end = MilliDate(SDate("2017-01-20T20:00").millisSinceEpoch)
      val expected = ShiftAssignments(Seq(
        StaffAssignment("shift name", T1, start, end, 5, None),
        StaffAssignment("shift name", T1, start, end, 9, None)))

      shiftsString === expected
    }
    "take a v2 ShiftsMessage and return the multiline string representation" in {
      val shiftsMessage = ShiftsMessage(List(ShiftMessage(
        Some("shift name"), Some(T1.toString), None, None, None, Some("5"), Some(1484906400000L), Some(1484942400000L)
      ), ShiftMessage(
        Some("shift name"), Some(T1.toString), None, None, None, Some("9"), Some(1484906400000L), Some(1484942400000L)
      )))

      val shiftsString = shiftMessagesToStaffAssignments(shiftsMessage.shifts)

      val start = MilliDate(SDate("2017-01-20T10:00").millisSinceEpoch)
      val end = MilliDate(SDate("2017-01-20T20:00").millisSinceEpoch)
      val staffAssignments = ShiftAssignments(Seq(
        StaffAssignment("shift name", T1, start, end, 5, None),
        StaffAssignment("shift name", T1, start, end, 9, None)))

      val expected = staffAssignments

      shiftsString === expected
    }
  }
}
