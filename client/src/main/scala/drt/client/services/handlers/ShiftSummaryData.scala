package drt.client.services.handlers

import drt.client.components

object ShiftSummaryData {
  case class ShiftDate(
                        year: Int,
                        month: Int,
                        day: Int,
                        hour: Int,
                        minute: Int
                      ) {
    def toClientShiftDate: components.ShiftDate = components.ShiftDate(year, month, day, hour, minute)
  }

  case class StaffTableEntry(
                              column: Int,
                              row: Int,
                              name: String,
                              staffNumber: Int,
                              startTime: ShiftDate,
                              endTime: ShiftDate) {
    def toClientStaffTableEntry: components.StaffTableEntry = {
      components.StaffTableEntry(column, row, name, staffNumber, startTime.toClientShiftDate, endTime.toClientShiftDate)
    }
  }

  case class ShiftSummary(
                           name: String,
                           defaultStaffNumber: Int,
                           startTime: String,
                           endTime: String
                         ) {
    def toClientShiftSummary: components.ShiftSummary =
      components.ShiftSummary(name, defaultStaffNumber, startTime, endTime)

  }

  case class ShiftSummaryStaffing(
                                   index: Int,
                                   shiftSummary: ShiftSummary,
                                   staffTableEntries: Seq[StaffTableEntry]
                                 ) {
    def toClientShiftSummaryStaffing: components.ShiftSummaryStaffing =
      components.ShiftSummaryStaffing(index, shiftSummary.toClientShiftSummary, staffTableEntries.map(_.toClientStaffTableEntry))
  }

}