package controllers.application

import controllers.application.ShiftsHelpers.daysCountByDayRange
import drt.shared.{Shift, ShiftAssignments}
import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, SDateLike}

class ShiftsHelpersSpec extends Specification {

  "ShiftsHelpers" should {
    "return correct number of days for monthly dayRange" in {
      val viewingDate = SDate("2025-02-04")
      val result = daysCountByDayRange("monthly", viewingDate)
      result === 28
    }


    "numberOfDaysInMonth should return correct number of days" in {
      val viewingDate: SDateLike = SDate("2023-10-01T00:00:00Z")
      val result = ShiftsHelpers.numberOfDaysInMonth(viewingDate)
      result === 31
    }

    "daysCount should return correct number of days for monthly dayRange" in {
      val viewingDate: SDateLike = SDate("2023-10-01T00:00:00Z")
      val result = ShiftsHelpers.daysCountByDayRange("monthly", viewingDate)
      result === 31
    }

    "daysCount should return correct number of days for weekly dayRange" in {
      val viewingDate: SDateLike = SDate("2023-10-01T00:00:00Z")
      val result = ShiftsHelpers.daysCountByDayRange("weekly", viewingDate)
      result === 7
    }

    "daysCount should return correct number of days for daily dayRange" in {
      val viewingDate: SDateLike = SDate("2023-10-01T00:00:00Z")
      val result = ShiftsHelpers.daysCountByDayRange("daily", viewingDate)
      result === 1
    }

    "firstDay should return correct first day for monthly dayRange" in {
      val viewingDate: SDateLike = SDate("2023-10-15T00:00:00Z")
      val result = ShiftsHelpers.firstDayByDayRange("monthly", viewingDate)
      result === SDate(2023, 10, 1)
    }

    "firstDay should return correct first day for daily dayRange" in {
      val viewingDate: SDateLike = SDate("2023-10-15T00:00:00Z")
      val result = ShiftsHelpers.firstDayByDayRange("daily", viewingDate)
      result === viewingDate
    }

    "assignmentsForShift should generate correct row and col values for monthly dayRange" in {
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

      val result: Seq[ShiftSummaryStaffing] = ShiftsHelpers.generateShiftSummaries(firstDay, dayRange, terminal, Seq(shift), shiftAssignments, interval)

      result.nonEmpty &&
        result.head.shiftSummary.name === "Morning" &&
        result.head.shiftSummary.defaultStaffNumber === 5 &&
        result.head.staffTableEntries.nonEmpty &&
        result.head.staffTableEntries.head.column === 1 &&
        result.head.staffTableEntries.head.row === 0 &&
        result.head.staffTableEntries.last.column === 31 &&
        result.head.staffTableEntries.last.row === 0
    }

    "assignmentsForShift should generate correct row and col values for weekly dayRange" in {
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

      val result = ShiftsHelpers.generateShiftSummaries(firstDay, dayRange, terminal, Seq(shift), shiftAssignments, interval)

      result.nonEmpty &&
        result.head.shiftSummary.name === "Morning" &&
        result.head.shiftSummary.defaultStaffNumber === 5 &&
        result.head.staffTableEntries.nonEmpty &&
        result.head.staffTableEntries.head.column === 1 &&
        result.head.staffTableEntries.head.row === 0 &&
        result.head.staffTableEntries.last.column === 7 &&
        result.head.staffTableEntries.last.row === 0
    }

    "assignmentsForShift should generate correct row and col values for daily dayRange" in {
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
        start = SDate(2023, 10, 1, 8, 0),
        end = SDate(2023, 10, 1, 9, 0),
        endHour = 9,
        endMinute = 0,
        interval = interval,
        day = 1,
        isShiftEndAfterMidnight = false,
        isFirstDayForShiftEndAfterMidnight = false,
        addToIndex = 0
      )
      val shiftDetails = ShiftDetails(shift, terminal, shiftAssignments)
      val result = ShiftsHelpers.staffTableEntriesForShift(shiftPeriod, shiftDetails)

      result.nonEmpty &&
        result.head.name === "Morning" &&
        result.head.staffNumber === 5 &&
        result.size === daysCount * (1 * 60 / interval) &&
        result.head.column === 1 &&
        result.head.row === 0 &&
        result.last.column === 1 &&
        result.last.row === 0
    }

    "generateShiftData should generate correct shift data for monthly dayRange" in {
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

      val result: Seq[ShiftSummaryStaffing] = ShiftsHelpers.generateShiftSummaries(viewingDate, dayRange, terminal, staffShifts, shifts, interval)

      result.size === 2 &&
        result.head.shiftSummary.name === "Morning" &&
        result.head.shiftSummary.defaultStaffNumber === 5 &&
        result.head.staffTableEntries.nonEmpty &&
        result.head.staffTableEntries.head.column === 1 &&
        result.head.staffTableEntries.head.row === 0 &&
        result.head.staffTableEntries.last.column === 31 &&
        result.head.staffTableEntries.last.row === 7
    }

    "createStaffTableEntries for first day of month should generate correct assignments for shifts ending after midnight" in {
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

      val result = ShiftsHelpers.createStaffTableEntries(SDate(2023, 10, 1, 22, 0), 1, interval, shiftDetails)

      val expected = Seq(
        StaffTableEntry(1, 0, "Night", 5, ShiftDate(2023, 10, 1, 22, 0), ShiftDate(2023, 10, 1, 23, 0)),
        StaffTableEntry(1, 1, "Night", 5, ShiftDate(2023, 10, 1, 23, 0), ShiftDate(2023, 10, 2, 0, 0)),
        StaffTableEntry(1, 2, "Night", 5, ShiftDate(2023, 10, 2, 0, 0), ShiftDate(2023, 10, 2, 1, 0)),
        StaffTableEntry(1, 3, "Night", 5, ShiftDate(2023, 10, 2, 1, 0), ShiftDate(2023, 10, 2, 2, 0))
      )

      result.zip(expected).foreach { case (res, exp) =>
        res.column === exp.column &&
          res.row === exp.row &&
          res.name === exp.name &&
          res.staffNumber === exp.staffNumber &&
          res.startTime.isEqual(exp.startTime) &&
          res.endTime.isEqual(exp.endTime)
      }

      result.size ==== expected.size

    }

    "createStaffTableEntries for other day of month should generate correct assignments for shifts ending after midnight" in {
      //      val viewingDate: SDateLike = SDate("2023-10-01T00:00:00Z")
      //      val dayRange = "monthly"
      val interval = 60
      val terminal = Terminal("T1")
      val staffShift = Shift(
        port = "LHR",
        terminal = "T1",
        shiftName = "Night",
        startDate = LocalDate(2023, 10, 2),
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

      val result = ShiftsHelpers.createStaffTableEntries(SDate(2023, 10, 2, 22, 0), 1, interval, shiftDetails)

      val expected = Seq(
        StaffTableEntry(1, 0, "Night", 5, ShiftDate(2023, 10, 2, 22, 0), ShiftDate(2023, 10, 2, 23, 0)),
        StaffTableEntry(1, 1, "Night", 5, ShiftDate(2023, 10, 2, 23, 0), ShiftDate(2023, 10, 3, 0, 0)),
        StaffTableEntry(1, 2, "Night", 5, ShiftDate(2023, 10, 3, 0, 0), ShiftDate(2023, 10, 3, 1, 0)),
        StaffTableEntry(1, 3, "Night", 5, ShiftDate(2023, 10, 3, 1, 0), ShiftDate(2023, 10, 3, 2, 0))
      )

      result.zip(expected).foreach { case (res, exp) =>
        res.column === exp.column &&
          res.row === exp.row &&
          res.name === exp.name &&
          res.staffNumber === exp.staffNumber &&
          res.startTime.isEqual(exp.startTime) &&
          res.endTime.isEqual(exp.endTime)
      }

      result.size ==== expected.size

    }
  }
}