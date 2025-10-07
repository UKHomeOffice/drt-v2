package drt.server.feeds

import actors.persistent.staffing.StaffingUtil
import drt.shared.{ShiftAssignments, StaffAssignmentLike}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.service.staffing.{IShiftStaffRollingService, ShiftAssignmentsService, ShiftsService}
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, SDateLike}
import uk.gov.homeoffice.drt.{Shift, ShiftStaffRolling}

import scala.concurrent.{ExecutionContext, Future}

object AutoRollShiftUtil {
  private val log = org.slf4j.LoggerFactory.getLogger(getClass)


  def numberOfMonthsToFill(previousDate: Option[SDateLike], currentDate: SDateLike): Int = {
    previousDate match {
      case Some(d) => val now = currentDate
        val monthsDiff = (d.getFullYear - now.getFullYear) * 12 + (d.getMonth - now.getMonth)
        if (monthsDiff < 0) 6 else 6 - monthsDiff
      case None => 6
    }
  }

  def startAndEndForMonthsGiven(viewDate: SDateLike, monthsToAdd: Int): (LocalDate, LocalDate) = {
    val firstDayOfSixthMonth = viewDate.startOfTheMonth
    val endOfSixMonthInMillis = firstDayOfSixthMonth.addMonths(monthsToAdd).addMinutes(-1)
    (firstDayOfSixthMonth.toLocalDate, endOfSixMonthInMillis.toLocalDate)
  }


  def existingCheckAndUpdate(port: String,
                             terminal: Terminal,
                             previousRollingEndDate: SDateLike,
                             monthsToAdd: Int,
                             shiftService: ShiftsService,
                             shiftAssignmentsService: ShiftAssignmentsService,
                             shiftStaffRollingService: IShiftStaffRollingService
                            )(implicit ec: ExecutionContext): Future[ShiftAssignments] = {

    shiftStaffRollingService.getShiftStaffRolling(port, terminal.toString).flatMap { _ =>

      val (startRollingDate, endRollingDate) = startAndEndForMonthsGiven(previousRollingEndDate, monthsToAdd)

      val assignmentsF: Future[ShiftAssignments] = shiftAssignmentsService.allShiftAssignments
      val shiftsF: Future[Seq[Shift]] = shiftService.getActiveShifts(port, terminal.toString, None)

      for {
        shifts <- shiftsF
        assignments <- assignmentsF
        updatedAssignments <- if (shifts.nonEmpty) {
          val updatedShifts = updateShiftDateForRolling(shifts, startRollingDate, endRollingDate)
          val withDefaultStaff = StaffingUtil.updateWithShiftDefaultStaff(updatedShifts, assignments)
          shiftAssignmentsService.updateShiftAssignments(withDefaultStaff)
        } else {
          Future.successful(ShiftAssignments(Seq.empty[StaffAssignmentLike]))
        }
      } yield {
        if (shifts.nonEmpty) {
          shiftStaffRollingService.upsertShiftStaffRolling(
            ShiftStaffRolling(
              port = port,
              terminal = terminal.toString,
              rollingStartDate = SDate(startRollingDate).millisSinceEpoch,
              rollingEndDate = SDate(endRollingDate).millisSinceEpoch,
              updatedAt = SDate.now().millisSinceEpoch,
              triggeredBy = "auto-shift-staffing"
            )
          )
          log.info(s"updateShiftsStaffingToAssignments :AutoShiftStaffing updated shifts for $port from ${startRollingDate.toISOString} to ${endRollingDate.toISOString} for terminal $terminal")
        }
        updatedAssignments
      }
    }
  }

  def updateShiftDateForRolling(shifts: Seq[Shift], startDate: LocalDate, endDate: LocalDate): Seq[Shift] = {
    shifts.map { s =>
      s.copy(
        startDate = startDate,
        endDate = Some(endDate))
    }
  }

}
