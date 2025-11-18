package drt.client.components

import drt.client.components.MonthlyShiftsUtil._
import drt.client.services.JSDateConversions.SDate
import drt.shared._
import uk.gov.homeoffice.drt.Shift
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{LocalDate, SDateLike}
import utest._

object MonthlyShiftsUtilSpec extends TestSuite {
  val tests: Tests = Tests {
    test("numberOfDaysInMonth should return correct number of days") {
      val viewingDate: SDateLike = SDate("2023-10-01T00:00:00Z")
      val result = MonthlyShiftsUtil.numberOfDaysInMonth(viewingDate)
      assert(result == 31)
    }

    test("daysCount should return correct number of days for monthly dayRange") {
      val viewingDate: SDateLike = SDate("2023-10-01T00:00:00Z")
      val result = MonthlyShiftsUtil.daysCountByViewPeriod("monthly", viewingDate)
      assert(result == 31)
    }

    test("daysCount should return correct number of days for weekly dayRange") {
      val viewingDate: SDateLike = SDate("2023-10-01T00:00:00Z")
      val result = MonthlyShiftsUtil.daysCountByViewPeriod("weekly", viewingDate)
      assert(result == 7)
    }

    test("daysCount should return correct number of days for daily dayRange") {
      val viewingDate: SDateLike = SDate("2023-10-01T00:00:00Z")
      val result = MonthlyShiftsUtil.daysCountByViewPeriod("daily", viewingDate)
      assert(result == 1)
    }

    test("firstDay should return correct first day for monthly dayRange") {
      val viewingDate: SDateLike = SDate("2023-10-15T00:00:00Z")
      val result = MonthlyShiftsUtil.firstDayByViewPeriod("monthly", viewingDate)
      assert(result == LocalDate(2023, 10, 1))
    }

    test("firstDay should return correct first day for weekly dayRange") {
      val viewingDate: SDateLike = SDate("2023-10-15T00:00:00Z")
      val result = MonthlyShiftsUtil.firstDayByViewPeriod("weekly", viewingDate)
      assert(result == LocalDate(2023, 10, 9)) // Assuming the week starts on Monday
    }

    test("firstDay should return correct first day for daily dayRange") {
      val viewingDate: SDateLike = SDate("2023-10-15T00:00:00Z")
      val result = MonthlyShiftsUtil.firstDayByViewPeriod("daily", viewingDate)
      assert(result == viewingDate.toLocalDate)
    }

    test("assignmentsForShift should generate correct row and col values for monthly dayRange") {
      val firstDay: SDateLike = SDate("2023-10-01T00:00:00Z")
      val dayRange = "monthly"
      val interval = 60
      val terminal = Terminal("T1")
      val shift = Shift(
        port = "LHR",
        terminal = "T1",
        shiftName = "Morning",
        startDate = LocalDate(2023, 10, 1),
        startTime = "08:00",
        endTime = "09:00",
        endDate = None,
        staffNumber = 5,
        frequency = None,
        createdBy = None,
        createdAt = 0L
      )
      val shiftAssignments = ShiftAssignments(Seq.empty)

      val result: Seq[ShiftSummaryStaffing] = MonthlyShiftsUtil.generateShiftSummaries(firstDay, dayRange, terminal, Seq(shift), shiftAssignments, Map.empty, interval)

      assert(result.nonEmpty)
      assert(result.head.shiftSummary.name == "Morning")
      assert(result.head.shiftSummary.defaultStaffNumber == 5)
      assert(result.head.staffTableEntries.nonEmpty)
      assert(result.head.staffTableEntries.head.column == 1)
      assert(result.head.staffTableEntries.head.row == 0)
      assert(result.head.staffTableEntries.last.column == 31)
      assert(result.head.staffTableEntries.last.row == 0)
    }

    test("assignmentsForShift should generate correct row and col values for weekly dayRange") {
      val firstDay: SDateLike = SDate("2023-10-01T00:00:00Z")
      val dayRange = "weekly"
      val interval = 60
      val terminal = Terminal("T1")
      val shift = Shift(
        port = "LHR",
        terminal = "T1",
        shiftName = "Morning",
        startDate = LocalDate(2023, 10, 1),
        startTime = "08:00",
        endTime = "09:00",
        endDate = None,
        staffNumber = 5,
        frequency = None,
        createdBy = None,
        createdAt = 0L
      )
      val shiftAssignments = ShiftAssignments(Seq.empty)

      val result = MonthlyShiftsUtil.generateShiftSummaries(firstDay, dayRange, terminal, Seq(shift), shiftAssignments, Map.empty, interval)

      assert(result.nonEmpty)
      assert(result.head.shiftSummary.name == "Morning")
      assert(result.head.shiftSummary.defaultStaffNumber == 5)
      assert(result.head.staffTableEntries.nonEmpty)
      assert(result.head.staffTableEntries.head.column == 1)
      assert(result.head.staffTableEntries.head.row == 0)
      assert(result.head.staffTableEntries.last.column == 7)
      assert(result.head.staffTableEntries.last.row == 0)
    }

    test("assignmentsForShift should generate correct row and col values for daily dayRange") {
      val daysCount = 1
      val interval = 60
      val terminal = Terminal("T1")
      val shift = Shift(
        port = "LHR",
        terminal = "T1",
        shiftName = "Morning",
        startDate = LocalDate(2023, 10, 1),
        startTime = "08:00",
        endTime = "09:00",
        endDate = None,
        staffNumber = 5,
        frequency = None,
        createdBy = None,
        createdAt = 0L
      )
      val shiftAssignments = ShiftAssignments(Seq.empty)

      val shiftPeriod = ShiftPeriod(
        start = SDate(2023, 10, 1, 8),
        end = SDate(2023, 10, 1, 9),
        endHour = 9,
        endMinute = 0,
        intervalMinutes = interval,
        day = 1,
        endsAfterMidnight = false,
        firstDayEndsAfterMidnight = false,
        addToIndex = 0
      )
      val shiftDetails = ShiftDetails(shift, terminal, shiftAssignments)
      val result = MonthlyShiftsUtil.staffTableEntriesForShift(shiftPeriod, shiftDetails, Map.empty)

      assert(result.nonEmpty)
      assert(result.head.name == "Morning")
      assert(result.head.staffNumber == 5)
      assert(result.size == daysCount * (1 * 60 / interval))
      assert(result.head.column == 1)
      assert(result.head.row == 0)
      assert(result.last.column == 1)
      assert(result.last.row == 0)
    }

    test("generateShiftData should generate correct shift data for monthly dayRange") {
      val viewingDate: SDateLike = SDate("2023-10-01T00:00:00Z")
      val dayRange = "monthly"
      val terminal = Terminal("T1")
      val staffShifts = Seq(
        Shift(
          port = "LHR",
          terminal = "T1",
          shiftName = "Morning",
          startDate = LocalDate(2023, 10, 1),
          startTime = "08:00",
          endTime = "16:00",
          endDate = None,
          staffNumber = 5,
          frequency = None,
          createdBy = None,
          createdAt = 0L
        ),
        Shift(
          port = "LHR",
          terminal = "T1",
          shiftName = "Evening",
          startDate = LocalDate(2023, 10, 1),
          startTime = "16:00",
          endTime = "00:00",
          endDate = None,
          staffNumber = 3,
          frequency = None,
          createdBy = None,
          createdAt = 0L
        )
      )
      val shifts = ShiftAssignments(Seq.empty)
      val interval = 60

      val result: Seq[ShiftSummaryStaffing] = MonthlyShiftsUtil.generateShiftSummaries(viewingDate, dayRange, terminal, staffShifts, shifts, Map.empty, interval)

      assert(result.size == 2)
      assert(result.head.shiftSummary.name == "Morning")
      assert(result.head.shiftSummary.defaultStaffNumber == 5)
      assert(result.head.staffTableEntries.nonEmpty)
      assert(result.head.staffTableEntries.head.column == 1)
      assert(result.head.staffTableEntries.head.row == 0)
      assert(result.head.staffTableEntries.last.column == 31)
      assert(result.head.staffTableEntries.last.row == 7)
    }

    test("createStaffTableEntries should generate correct assignments for shifts ending after midnight") {
      val interval = 60
      val terminal = Terminal("T1")
      val staffShift = Shift(
        port = "LHR",
        terminal = "T1",
        shiftName = "Night",
        startDate = LocalDate(2023, 10, 1),
        startTime = "22:00",
        endTime = "02:00",
        endDate = None,
        staffNumber = 5,
        frequency = None,
        createdBy = None,
        createdAt = 0L
      )
      val shifts = ShiftAssignments(Seq.empty)

      val shiftDetails = ShiftDetails(staffShift, terminal, shifts)

      val recStaff = Map(
        SDate(2023, 10, 1, 22).millisSinceEpoch -> 4,
        SDate(2023, 10, 1, 23).millisSinceEpoch -> 4,
        SDate(2023, 10, 1).millisSinceEpoch -> 4,
        SDate(2023, 10, 1, 1).millisSinceEpoch -> 4,
      )

      val result = MonthlyShiftsUtil.createStaffTableEntries(LocalDate(2023, 10, 1), 1, interval, shiftDetails, recStaff)

      val expected = Seq(
        StaffTableEntry(1, 0, "Night", 4, 5, SDate(2023, 10, 1, 22).millisSinceEpoch, ShiftDateTime(2023, 10, 1, 22, 0), ShiftDateTime(2023, 10, 1, 23, 0)),
        StaffTableEntry(1, 1, "Night", 4, 5, SDate(2023, 10, 1, 23).millisSinceEpoch, ShiftDateTime(2023, 10, 1, 23, 0), ShiftDateTime(2023, 10, 2, 0, 0)),
        StaffTableEntry(1, 2, "Night", 4, 5, SDate(2023, 10, 1, 0).millisSinceEpoch, ShiftDateTime(2023, 10, 1, 0, 0), ShiftDateTime(2023, 10, 1, 1, 0)),
        StaffTableEntry(1, 3, "Night", 4, 5, SDate(2023, 10, 1, 1).millisSinceEpoch, ShiftDateTime(2023, 10, 1, 1, 0), ShiftDateTime(2023, 10, 1, 2, 0))
      )

      assert(result.size == expected.size)
      result.zip(expected).foreach { case (res, exp) =>
        assert(res.column == exp.column)
        assert(res.row == exp.row)
        assert(res.name == exp.name)
        assert(res.staffRecommendation == exp.staffRecommendation)
        assert(res.staffNumber == exp.staffNumber)
        assert(ShiftDateTime.isEqual(res.startTime, exp.startTime))
        assert(ShiftDateTime.isEqual(res.endTime, exp.endTime))
      }
    }
  }
}
