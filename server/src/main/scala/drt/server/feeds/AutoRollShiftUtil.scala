package drt.server.feeds

import drt.server.feeds.AutoShiftStaffing.{getClass, log}
import drt.shared.{ShiftAssignments, StaffAssignment, TM}
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.ports.Terminals
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.service.staffing.ShiftAssignmentsService
import uk.gov.homeoffice.drt.time.{LocalDate, SDate}

import scala.concurrent.ExecutionContext

object AutoRollShiftUtil {
  private val log = org.slf4j.LoggerFactory.getLogger(getClass)

  def updateShiftsStaffingToAssignments(drtSystemInterface: DrtSystemInterface, shiftAssignmentsService: ShiftAssignmentsService)(implicit ec: ExecutionContext): Unit = {
    val currentDate = SDate.now()
    val sixMonthsFromNow = currentDate.addMonths(6).toLocalDate
    val terminals: Iterable[Terminals.Terminal] = drtSystemInterface.airportConfig.terminalsForDate(currentDate.toLocalDate)
    val firstDayOfSixthMonth = LocalDate(sixMonthsFromNow.year, sixMonthsFromNow.month, 1)
    val firstDayOfSixthMonthInMillis = SDate(firstDayOfSixthMonth).millisSinceEpoch
    val endOF6thMonthInMillis = SDate(firstDayOfSixthMonth).addMonths(1).addMinutes(-1).millisSinceEpoch
    log.info(s"Running AutoShiftStaffing for ${drtSystemInterface.airportConfig.portCode.iata} for terminals ${terminals.mkString(", ")} up to $firstDayOfSixthMonth")
    //        shiftAssignmentsService.allShiftAssignments.map(_.assignments.filter(_.start >= firstDayOfSixthMonth)
    val nonZeroStaffing = shiftAssignmentsService.allShiftAssignments.map(_.assignments
      .flatMap(_.splitIntoSlots(ShiftAssignments.periodLengthMinutes))
      .filter(a => a.start > firstDayOfSixthMonthInMillis && a.numberOfStaff > 0))

    nonZeroStaffing.map {
      case assignments if assignments.isEmpty =>
        drtSystemInterface.shiftsService.autoShiftRolling(drtSystemInterface.airportConfig.portCode.iata, terminals.map(_.toString).toSeq)
        log.warn(s"No non-zero shift assignments found for ${drtSystemInterface.airportConfig.portCode.iata}, cannot auto-roll shifts")
      case assignments =>
        log.info(s"Found ${assignments.size} non-zero shift assignments for ${drtSystemInterface.airportConfig.portCode.iata}, proceeding with auto-roll")
    }
  }


  def getShiftAssignmentsForDateRange(startMillis: Long, endMillis: Long, terminal: Terminal, shiftName:String): ShiftAssignments = {

    val periodLengthMinutes = ShiftAssignments.periodLengthMinutes
    val periodLengthMillis = periodLengthMinutes * 60 * 1000L
    //   val slots: Seq[Long] = (startMillis to endMillis by periodLengthMillis).toList
    val shiftAssignments = Seq(StaffAssignment(
      name = shiftName,
      terminal = terminal,
      start = startMillis,
      end = endMillis,
      numberOfStaff = 0,
      createdBy = None
    ))

    val assignments = shiftAssignments
      .flatMap(_.splitIntoSlots(ShiftAssignments.periodLengthMinutes))
      .groupBy(a => TM(a.terminal, a.start))
      .map { case (tm, assignments) =>
        val combinedAssignment = assignments.reduce { (a1, a2) =>
          a1.copy(numberOfStaff = a1.numberOfStaff + a2.numberOfStaff)
        }
        tm -> combinedAssignment
      }


    ShiftAssignments(assignments)
  }

}