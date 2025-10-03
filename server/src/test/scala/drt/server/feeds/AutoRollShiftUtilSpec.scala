package drt.server.feeds

import drt.shared.{ShiftAssignments, StaffAssignment, StaffAssignmentLike}
import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.Shift
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.testsystem.MockStaffShiftsService
import uk.gov.homeoffice.drt.time.SDate
import uk.gov.homeoffice.drt.time.TimeZoneHelper.europeLondonTimeZone

import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt

class AutoRollShiftUtilSpec extends Specification {

  "sixthMonthStartAndEnd should return correct millis for the 6th month from viewDate" >> {
    val viewDate = SDate("2025-09-15T12:00")
    val (startMillis, endMillis) = AutoRollShiftUtil.StartAndEndForMonthsGiven(viewDate, 6)

    val expectedStart = SDate("2025-09-01T00:00", europeLondonTimeZone)
    val expectedEnd = SDate("2026-02-28T23:59", europeLondonTimeZone)

    startMillis must beEqualTo(expectedStart)
    endMillis must beEqualTo(expectedEnd)
  }


  "updateShiftDateForRolling should update all shifts to the given start and end dates" >> {
    val originalShifts = Seq(
      Shift(shiftName = "shift1",
        terminal = "T1",
        startDate = SDate("2024-07-01T08:00").toLocalDate,
        startTime = "08:00",
        endTime = "12:00",
        endDate = None,
        staffNumber = 1,
        createdBy = None,
        createdAt = 0,
        port = "XYZ",
        frequency = None),
      Shift(shiftName = "shift2",
        terminal = "T1",
        startDate = SDate("2024-07-02T09:00").toLocalDate,
        startTime = "11:00",
        endTime = "04:00",
        endDate = None,
        staffNumber = 2,
        createdBy = None,
        createdAt = 0,
        port = "XYZ",
        frequency = None)
    )
    val newStart = SDate("2024-08-01T00:00", europeLondonTimeZone)
    val newEnd = SDate("2024-08-31T23:59", europeLondonTimeZone)

    val rollingShifts = AutoRollShiftUtil.updateShiftDateForRolling(originalShifts, newStart, newEnd)

    rollingShifts must haveSize(2)
    rollingShifts.forall(_.startDate == newStart.toLocalDate) must beTrue
    rollingShifts.forall(_.endDate.contains(newEnd.toLocalDate)) must beTrue
  }

  def getShift(shiftName: String, staffNumber: Int, startDate: Long, endDate: Option[Long]) = Shift(
    shiftName = shiftName,
    terminal = "T1",
    startDate = SDate(startDate).toLocalDate,
    startTime = "08:00",
    endTime = "09:00",
    endDate = endDate.map(SDate(_).toLocalDate),
    staffNumber = staffNumber,
    createdBy = None,
    createdAt = 0,
    port = "LHR",
    frequency = None
  )

  "return empty when there are non-zero assignments for given view date" in {
    val existingShiftAssignments = ShiftAssignments(Map(
      drt.shared.TM(T1, SDate("2024-07-01T08:00").millisSinceEpoch) ->
        StaffAssignment("shift", T1, SDate("2024-07-01T08:00").millisSinceEpoch, SDate("2024-07-01T08:15").millisSinceEpoch, 2, None)
    ))
    val shiftAssignmentsF = Future.successful(existingShiftAssignments)
    val shiftService = MockStaffShiftsService()
    val shiftWithEndDate = getShift("shift", 1, SDate("2024-07-01T00:00").millisSinceEpoch, Some(SDate("2024-07-14T00:00").millisSinceEpoch))
    val viewDate = SDate("2024-07-15T08:00")

    shiftService.saveShift(Seq(shiftWithEndDate))

    val resultF: Future[Seq[StaffAssignmentLike]] = AutoRollShiftUtil.rollingAssignmentForTerminal(
      "LHR",
      viewDate,
      T1,
      shiftService,
      shiftAssignmentsF,
      1
    )

    Await.result(resultF, 5.seconds) === Seq.empty[StaffAssignmentLike]
  }

  "return assignment to rolls when there are shifts for given view date present and not assignments present in" in {
    val existingShiftAssignments = ShiftAssignments(Map(
      drt.shared.TM(T1, SDate("2024-07-01T08:00", europeLondonTimeZone).millisSinceEpoch) ->
        StaffAssignment("shift",
          T1,
          SDate("2024-07-01T08:00", europeLondonTimeZone).millisSinceEpoch,
          SDate("2024-07-01T08:15", europeLondonTimeZone).millisSinceEpoch,
          1,
          None)
    ))
    val shiftAssignmentsF = Future.successful(existingShiftAssignments)
    val shiftService = MockStaffShiftsService()
    val shiftWithNoEndDate = getShift("shift", 1, SDate("2024-07-01T00:00", europeLondonTimeZone).millisSinceEpoch, None)
    val viewDate = SDate("2024-07-15T08:00", europeLondonTimeZone)
    val monthsToAdd = 1

    shiftService.saveShift(Seq(shiftWithNoEndDate))

    val resultF: Future[Seq[StaffAssignmentLike]] = AutoRollShiftUtil.rollingAssignmentForTerminal(
      "LHR",
      viewDate,
      T1,
      shiftService,
      shiftAssignmentsF,
      monthsToAdd
    )

    val result: Seq[StaffAssignmentLike] = Await.result(resultF, 5.seconds)

    result.foreach(s => println(s" ${s.numberOfStaff} ${SDate(s.start, europeLondonTimeZone).toISOString} - ${SDate(s.end, europeLondonTimeZone).toISOString}")) // scalastyle:ignore

    val sortedResult = result.sortBy(_.start)
    sortedResult.head mustEqual
      StaffAssignment("shift",
        T1,
        SDate("2024-07-01T08:00", europeLondonTimeZone).millisSinceEpoch,
        SDate("2024-07-01T08:15", europeLondonTimeZone).millisSinceEpoch,
        1,
        None)

    sortedResult.reverse.head mustEqual
      StaffAssignment("shift",
        T1,
        SDate("2024-07-31T08:45", europeLondonTimeZone).millisSinceEpoch,
        SDate("2024-07-31T08:59", europeLondonTimeZone).millisSinceEpoch,
        1,
        None)

  }

}
