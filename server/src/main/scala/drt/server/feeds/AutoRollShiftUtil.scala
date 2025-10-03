package drt.server.feeds

import actors.persistent.staffing.StaffingUtil
import drt.server.feeds.AutoShiftStaffing.{getClass, log}
import drt.shared.{ShiftAssignments, StaffAssignment, StaffAssignmentLike, TM}
import uk.gov.homeoffice.drt.Shift
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.ports.Terminals
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.service.staffing.{ShiftAssignmentsService, ShiftsService}
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, SDateLike}

import scala.concurrent.{ExecutionContext, Future}

object AutoRollShiftUtil {
  private val log = org.slf4j.LoggerFactory.getLogger(getClass)

  def updateShiftsStaffingToAssignments(port: String,
                                        terminals: Seq[Terminal],
                                        rollingDate : SDateLike,
                                        monthsToAdd : Int,
                                        shiftService: ShiftsService,
                                        shiftAssignmentsService: ShiftAssignmentsService)
                                       (implicit ec: ExecutionContext): Future[Seq[ShiftAssignments]] = {
    val assignmentsF: Future[ShiftAssignments] = shiftAssignmentsService.allShiftAssignments

    Future.sequence(terminals.map { terminal =>
      rollingAssignmentForTerminal(port, rollingDate, terminal, shiftService, assignmentsF,monthsToAdd).flatMap(as =>
        shiftAssignmentsService.updateShiftAssignments(as))
    }).recover {
      case e: Throwable =>
        log.error(s"AutoRollShiftUtil failed to update shifts: ${e.getMessage}")
        Seq()
    }
  }

  def rollingAssignmentForTerminal(port: String,
                                   rollingDate: SDateLike,
                                   terminal: Terminal,
                                   shiftService: ShiftsService,
                                   ShiftAssignments: Future[ShiftAssignments],
                                   monthsToAdd : Int
                                  )
                                  (implicit ec: ExecutionContext): Future[Seq[StaffAssignmentLike]] = {

    val (startMillis, endMillis) = StartAndEndForMonthsGiven(rollingDate, monthsToAdd)

    val shiftsF: Future[Seq[Shift]] = shiftService.getActiveShifts(port, terminal.toString, None)

    for {
      shifts <- shiftsF
      assignments <- ShiftAssignments
      updatedShifts = updateShiftDateForRolling(shifts, startMillis, endMillis)
      withDefaultStaff = StaffingUtil.updateWithShiftDefaultStaff(updatedShifts, assignments)
    } yield withDefaultStaff
  }

  def updateShiftDateForRolling(shifts: Seq[Shift], startDate: SDateLike, endDate: SDateLike): Seq[Shift] = {
    shifts.map { s =>
      s.copy(
        startDate = LocalDate(startDate.getFullYear, startDate.getMonth, startDate.getDate),
        endDate = Some(LocalDate(endDate.getFullYear, endDate.getMonth, endDate.getDate)))
    }
  }

  def StartAndEndForMonthsGiven(viewDate: SDateLike, monthToAdd:Int): (SDateLike, SDateLike) = {
    val firstDayOfSixthMonth = viewDate.addMonths(0).startOfTheMonth
    val endOfSixMonthInMillis = firstDayOfSixthMonth.addMonths(monthToAdd).addMinutes(-1)
    (firstDayOfSixthMonth, endOfSixMonthInMillis)
  }

}