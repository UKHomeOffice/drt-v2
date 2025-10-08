package drt.server.feeds

import drt.shared.{ShiftAssignments, StaffAssignment, StaffAssignmentLike}
import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.Shift
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.service.staffing.ShiftAssignmentsService
import uk.gov.homeoffice.drt.testsystem.{MockShiftAssignmentsService, MockShiftStaffRollingService, MockStaffShiftsService}
import uk.gov.homeoffice.drt.time.{LocalDate, SDate}
import uk.gov.homeoffice.drt.time.TimeZoneHelper.europeLondonTimeZone

import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt

class AutoRollShiftUtilSpec extends Specification {

  "sixthMonthStartAndEnd should return correct millis for the 6th month from previousEndDate" >> {
    val previousEndDate = SDate("2025-09-15T12:00")
    val (startDate, endDate) = AutoRollShiftUtil.startAndEndForMonthsGiven(previousEndDate, 6)

    val expectedStart = SDate(2025, 10, 1, 0, 0).toLocalDate
    val expectedEnd = SDate(2026, 3, 31, 0, 0).toLocalDate

    startDate must beEqualTo(expectedStart)
    endDate must beEqualTo(expectedEnd)
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
    val newStart = LocalDate(2024, 8, 1)
    val newEnd = LocalDate(2024, 8, 31)

    val rollingShifts = AutoRollShiftUtil.updateShiftDateForRolling(originalShifts, newStart, newEnd)

    rollingShifts must haveSize(2)
    rollingShifts.forall(_.startDate == newStart) must beTrue
    rollingShifts.forall(_.endDate.contains(newEnd)) must beTrue
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

  "return empty when there are no shift exists when if assignments exists" in {
    val existingShiftAssignments = ShiftAssignments(Map(
      drt.shared.TM(T1, SDate("2024-07-01T08:00").millisSinceEpoch) ->
        StaffAssignment("shift", T1, SDate("2024-07-01T08:00").millisSinceEpoch, SDate("2024-07-01T08:15").millisSinceEpoch, 2, None)
    ))
    val shiftService = MockStaffShiftsService()
    val shiftStaffRollingService = MockShiftStaffRollingService()
    val shiftAssignmentsService: ShiftAssignmentsService = MockShiftAssignmentsService(existingShiftAssignments.assignments)
    val previousViewDate = SDate("2024-07-15T08:00")
    val currentDate = SDate("2024-07-26T12:00")
    val monthsToAdd = AutoRollShiftUtil.numberOfMonthsToFill(Some(previousViewDate), currentDate)
    shiftService.saveShift(Seq())

    val resultF = AutoRollShiftUtil.existingCheckAndUpdate(
      "LHR",
      T1,
      previousViewDate,
      monthsToAdd,
      shiftService,
      shiftAssignmentsService,
      shiftStaffRollingService
    )

    Await.result(resultF, 5.seconds) === ShiftAssignments(Seq.empty[StaffAssignmentLike])
  }

  "return assignment to rolls when there are shifts for given previous end date present and not assignments present in" in {
    val existingShiftAssignments = ShiftAssignments(Map(
      drt.shared.TM(T1, SDate("2024-07-01T08:00").millisSinceEpoch) ->
        StaffAssignment("shift",
          T1,
          SDate("2024-07-01T08:00").millisSinceEpoch,
          SDate("2024-07-01T08:15").millisSinceEpoch,
          1,
          None)
    ))
    val shiftService = MockStaffShiftsService()
    val shiftStaffRollingService = MockShiftStaffRollingService()
    val shiftWithNoEndDate = getShift("shift", 1, SDate("2024-07-01T00:00").millisSinceEpoch, None)
    val shiftAssignmentsService: ShiftAssignmentsService = MockShiftAssignmentsService(existingShiftAssignments.assignments)
    val previousEndDate = SDate("2024-07-15T08:00")
    val currentDate = SDate("2024-06-26T12:00")
    val monthsToAdd = AutoRollShiftUtil.numberOfMonthsToFill(Some(previousEndDate), currentDate)

    shiftService.saveShift(Seq(shiftWithNoEndDate))

    val resultF = AutoRollShiftUtil.existingCheckAndUpdate(
      "LHR",
      T1,
      previousEndDate,
      monthsToAdd,
      shiftService,
      shiftAssignmentsService,
      shiftStaffRollingService
    )

    val result: Seq[StaffAssignmentLike] = Await.result(resultF, 5.seconds).assignments

    val sortedResult = result.sortBy(_.start)
    sortedResult.head mustEqual
      StaffAssignment("shift",
        T1,
        SDate("2024-07-01T08:00").millisSinceEpoch,
        SDate("2024-07-01T08:15").millisSinceEpoch,
        1,
        None)

    sortedResult.reverse.head mustEqual
      StaffAssignment("shift",
        T1,
        SDate("2024-12-31T08:45").millisSinceEpoch,
        SDate("2024-12-31T08:59").millisSinceEpoch,
        1,
        None)

  }

  "monthToBased" should {
    val currentDate = SDate.now()
    "return 6 when previousDate is None" in {
      AutoRollShiftUtil.numberOfMonthsToFill(None, currentDate) mustEqual 6
    }
    "return correct months left when previousDate is 3 months ago" in {
      val threeMonthsAgo = currentDate.addMonths(-3)
      AutoRollShiftUtil.numberOfMonthsToFill(Some(threeMonthsAgo), currentDate) mustEqual 6
    }
    "return 0 when previousDate is 6 months ago" in {
      val sixMonthsAgo = currentDate.addMonths(-6)
      AutoRollShiftUtil.numberOfMonthsToFill(Some(sixMonthsAgo), currentDate) mustEqual 6
    }
    "return negative when previousDate is more than 6 months ago" in {
      val sevenMonthsAgo = currentDate.addMonths(-7)
      AutoRollShiftUtil.numberOfMonthsToFill(Some(sevenMonthsAgo), currentDate) mustEqual 6
    }

    "return 6 when previousDate is current months" in {
      val currentMonth = SDate.now()
      AutoRollShiftUtil.numberOfMonthsToFill(Some(currentMonth), currentDate) mustEqual 6
    }

    "return 1 when previousDate is one months ahead" in {
      val aMonthAhead = currentDate.addMonths(1)
      AutoRollShiftUtil.numberOfMonthsToFill(Some(aMonthAhead), currentDate) mustEqual 5
    }

    "return 4 when previousDate is 2 months ahead" in {
      val twoMonthsAhead = SDate.now().addMonths(2)
      AutoRollShiftUtil.numberOfMonthsToFill(Some(twoMonthsAhead), currentDate) mustEqual 4
    }

    "return 3 when previousDate is 3 months ahead" in {
      val threeMonthAhead = SDate.now().addMonths(3)
      AutoRollShiftUtil.numberOfMonthsToFill(Some(threeMonthAhead), currentDate) mustEqual 3
    }

    "return 2 when previousDate is 5 months ago" in {
      val fourMonthAhead = SDate.now().addMonths(4)
      AutoRollShiftUtil.numberOfMonthsToFill(Some(fourMonthAhead), currentDate) mustEqual 2
    }

    "return 1 when previousDate is 5 months ago" in {
      val fiveMonthAhead = SDate.now().addMonths(5)
      AutoRollShiftUtil.numberOfMonthsToFill(Some(fiveMonthAhead), currentDate) mustEqual 1
    }

    "return 0 when previousDate is 5 months ago" in {
      val sixMonthAhead = SDate.now().addMonths(6)
      AutoRollShiftUtil.numberOfMonthsToFill(Some(sixMonthAhead), currentDate) mustEqual 0
    }
  }

}
