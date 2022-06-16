package services.crunch.staffing

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Flow
import akka.util.Timeout
import drt.shared.CrunchApi.{StaffMinute, StaffMinutes}
import drt.shared.{FixedPointAssignments, PortStateStaffMinutes, ShiftAssignments, StaffMovements}
import org.slf4j.{Logger, LoggerFactory}
import services.SDate
import services.crunch.deskrecs.RunnableOptimisation.TerminalUpdateRequest
import services.graphstages.{Crunch, Staffing}

import scala.concurrent.{ExecutionContext, Future}

object RunnableStaffing {
  val log: Logger = LoggerFactory.getLogger(getClass)

  import SDate.implicits.sdateFromMilliDateLocal

  def updateRequestToStaffMinutes(shiftsProvider: TerminalUpdateRequest => Future[ShiftAssignments],
                                  fixedPointsProvider: TerminalUpdateRequest => Future[FixedPointAssignments],
                                  movementsProvider: TerminalUpdateRequest => Future[StaffMovements],
                                 )
                                 (implicit ec: ExecutionContext, mat: Materializer, timeout: Timeout): Flow[TerminalUpdateRequest, PortStateStaffMinutes, NotUsed] =
    Flow[TerminalUpdateRequest]
      .wireTap(cr => log.info(s"${cr.localDate} staffing crunch request started"))
      .mapAsync(1)(cr => shiftsProvider(cr).map(sa => (cr, sa)))
      .mapAsync(1) { case (cr, sa) => fixedPointsProvider(cr).map(fp => (cr, sa, fp)) }
      .mapAsync(1) { case (cr, sa, fp) => movementsProvider(cr).map(sm => (cr, sa, fp, sm)) }
      .via(toStaffMinutes)

  def toStaffMinutes: Flow[(TerminalUpdateRequest, ShiftAssignments, FixedPointAssignments, StaffMovements), PortStateStaffMinutes, NotUsed] =
    Flow[(TerminalUpdateRequest, ShiftAssignments, FixedPointAssignments, StaffMovements)]
      .map { case (cr, sa, fp, sm) =>
        val staff = Staffing.staffAvailableByTerminalAndQueue(cr.end.millisSinceEpoch, sa, fp, Option(sm.movements))

        StaffMinutes(cr.minutesInMillis.map { minute =>
          val m = SDate(minute)
          val shifts = staff.shifts.terminalStaffAt(cr.terminal, m)
          val fixedPoints = staff.fixedPoints.terminalStaffAt(cr.terminal, SDate(m, Crunch.europeLondonTimeZone))
          val movements = staff.movements.terminalStaffAt(cr.terminal, minute)

          StaffMinute(cr.terminal, minute, shifts, fixedPoints, movements, lastUpdated = Option(SDate.now().millisSinceEpoch))
        })
      }

}
