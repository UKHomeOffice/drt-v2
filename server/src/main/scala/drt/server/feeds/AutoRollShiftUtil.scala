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


  def monthToBased(previousDate: Option[SDateLike], currentDate: SDateLike): Int = {
    previousDate match {
      case Some(d) => val now = currentDate
        val monthsDiff = (d.getFullYear - now.getFullYear) * 12 + (d.getMonth - now.getMonth)
        if (monthsDiff < 0) 6 else 6 - monthsDiff
      case None => 6
    }
  }

  def StartAndEndForMonthsGiven(viewDate: SDateLike, monthToAdd: Int): (SDateLike, SDateLike) = {
    val firstDayOfSixthMonth = viewDate.addDays(0).startOfTheMonth
    val endOfSixMonthInMillis = firstDayOfSixthMonth.addMonths(monthToAdd).addMinutes(-1)
    (firstDayOfSixthMonth, endOfSixMonthInMillis)
  }

  def existingCheckAndUpdate(port: String,
                             terminal: Terminal,
                             previousRollingEndDate: SDateLike,
                             monthsToAdd: Int,
                             shiftService: ShiftsService,
                             shiftAssignmentsService: ShiftAssignmentsService,
                             shiftStaffRollingService: IShiftStaffRollingService
                            )(implicit ec: ExecutionContext): Future[ShiftAssignments] = {

    shiftStaffRollingService.getShiftStaffRolling(port, terminal.toString).flatMap { ssrs =>

      val (startMillis, endMillis) = StartAndEndForMonthsGiven(previousRollingEndDate, monthsToAdd)
      updateShiftsStaffingToAssignments(port, terminal, startMillis, endMillis, shiftService, shiftAssignmentsService, shiftStaffRollingService)
    }

  }

  def updateShiftsStaffingToAssignments(port: String,
                                        terminal: Terminal,
                                        startMillis: SDateLike,
                                        endMillis: SDateLike,
                                        shiftService: ShiftsService,
                                        shiftAssignmentsService: ShiftAssignmentsService,
                                        shiftStaffRollingService: IShiftStaffRollingService)
                                       (implicit ec: ExecutionContext): Future[ShiftAssignments] = {
    val assignmentsF: Future[ShiftAssignments] = shiftAssignmentsService.allShiftAssignments
    val shiftsF: Future[Seq[Shift]] = shiftService.getActiveShifts(port, terminal.toString, None)

    for {
      shifts <- shiftsF
      assignments <- assignmentsF
      updatedAssignments <- if (shifts.nonEmpty) {
        val updatedShifts = updateShiftDateForRolling(shifts, startMillis, endMillis)
        val withDefaultStaff = StaffingUtil.updateWithShiftDefaultStaff(updatedShifts, assignments)
        shiftAssignmentsService.updateShiftAssignments(withDefaultStaff)
      } else {
        Future.successful(ShiftAssignments(Seq.empty[StaffAssignmentLike]))
      }
      _ = if (shifts.nonEmpty) shiftStaffRollingService.upsertShiftStaffRolling(
        ShiftStaffRolling(
          port = port,
          terminal = terminal.toString,
          rollingStartedDate = startMillis.millisSinceEpoch,
          rollingEndedDate = endMillis.millisSinceEpoch,
          updatedAt = SDate.now().millisSinceEpoch,
          appliedBy = "auto-shift-staffing"
        )
      )
      _ = log.info(s"updateShiftsStaffingToAssignments :AutoShiftStaffing updated shifts for $port from ${startMillis.toISOString} to ${endMillis.toISOString} for terminal $terminal")
    } yield updatedAssignments
  }

  def updateShiftDateForRolling(shifts: Seq[Shift], startDate: SDateLike, endDate: SDateLike): Seq[Shift] = {
    shifts.map { s =>
      s.copy(
        startDate = LocalDate(startDate.getFullYear, startDate.getMonth, startDate.getDate),
        endDate = Some(LocalDate(endDate.getFullYear, endDate.getMonth, endDate.getDate)))
    }
  }

}