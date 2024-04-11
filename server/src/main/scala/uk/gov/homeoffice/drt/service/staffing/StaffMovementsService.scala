package uk.gov.homeoffice.drt.service.staffing

import akka.Done
import drt.shared.StaffMovement
import uk.gov.homeoffice.drt.time.LocalDate
import uk.gov.homeoffice.drt.time.MilliDate.MillisSinceEpoch

import scala.concurrent.Future

trait StaffMovementsService {
  def movementsForDate(date: LocalDate, maybePointInTime: Option[MillisSinceEpoch]): Future[Seq[StaffMovement]]

  def addMovements(movements: List[StaffMovement]): Future[Done.type]

  def removeMovements(movementUuid: String): Unit
}
