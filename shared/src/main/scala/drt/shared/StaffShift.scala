package drt.shared

import uk.gov.homeoffice.drt.time.LocalDate

case class StaffShift(port: String,
                      terminal: String,
                      shiftName: String,
                      startDate: LocalDate,
                      startTime: String,
                      endTime: String,
                      endDate: Option[LocalDate],
                      staffNumber: Int,
                      frequency: Option[String],
                      createdBy: Option[String],
                      createdAt: Long)
