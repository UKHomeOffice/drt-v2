error id: file://<WORKSPACE>/server/src/main/scala/uk/gov/homeoffice/drt/service/staffing/ShiftAssignmentsService.scala:
file://<WORKSPACE>/server/src/main/scala/uk/gov/homeoffice/drt/service/staffing/ShiftAssignmentsService.scala
empty definition using pc, found symbol in pc: 
empty definition using semanticdb
empty definition using fallback
non-local guesses:

offset: 396
uri: file://<WORKSPACE>/server/src/main/scala/uk/gov/homeoffice/drt/service/staffing/ShiftAssignmentsService.scala
text:
```scala
package uk.gov.homeoffice.drt.service.staffing

import drt.shared.{ShiftAssignments, StaffAssignmentLike}
import uk.gov.homeoffice.drt.time.LocalDate
import uk.gov.homeoffice.drt.time.MilliDate.MillisSinceEpoch

import scala.concurrent.Future

trait ShiftAssignmentsService {
  def shiftAssignmentsForDate(date: LocalDate, maybePointInTime: Option[MillisSinceEpoch]): Future[ShiftAssignments]

  @@def allShiftAssignments: Future[ShiftAssignments]

  def updateShiftAssignments(shiftAssignments: Seq[StaffAssignmentLike]): Future[ShiftAssignments]
}

```


#### Short summary: 

empty definition using pc, found symbol in pc: 