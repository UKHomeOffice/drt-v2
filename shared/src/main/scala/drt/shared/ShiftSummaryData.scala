package drt.shared

object ShiftSummaryData {

  case class ShiftDate(
                        year: Int,
                        month: Int,
                        day: Int,
                        hour: Int,
                        minute: Int
                      )

  case class StaffTableEntry(
                              column: Int,
                              row: Int,
                              name: String,
                              staffNumber: Int,
                              startTime: ShiftDate,
                              endTime: ShiftDate)

  case class ShiftSummary(
                           name: String,
                           defaultStaffNumber: Int,
                           startTime: String,
                           endTime: String
                         )

  case class ShiftSummaryStaffing(
                                   index: Int,
                                   shiftSummary: ShiftSummary,
                                   staffTableEntries: Seq[StaffTableEntry]
                                 )

}