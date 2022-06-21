package services.crunch.staffing

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Flow
import akka.util.Timeout
import drt.shared.CrunchApi.{StaffMinute, StaffMinutes}
import drt.shared.{FixedPointAssignments, PortStateStaffMinutes, ShiftAssignments, StaffMovements}
import org.slf4j.{Logger, LoggerFactory}
import services.SDate
import services.crunch.deskrecs.RunnableOptimisation.{ProcessingRequest, TerminalUpdateRequest}
import services.graphstages.{Crunch, Staffing}
import uk.gov.homeoffice.drt.time.SDateLike

import scala.concurrent.{ExecutionContext, Future}

object RunnableStaffing {
  val log: Logger = LoggerFactory.getLogger(getClass)

  import SDate.implicits.sdateFromMilliDateLocal

  def staffMinutesFlow(shiftsProvider: ProcessingRequest => Future[ShiftAssignments],
                       fixedPointsProvider: ProcessingRequest => Future[FixedPointAssignments],
                       movementsProvider: ProcessingRequest => Future[StaffMovements],
                       now: () => SDateLike,
                      )
                      (implicit ec: ExecutionContext, mat: Materializer, timeout: Timeout): Flow[ProcessingRequest, PortStateStaffMinutes, NotUsed] =
    Flow[ProcessingRequest]
      .wireTap(cr => log.info(s"${cr.localDate} staffing crunch request started"))
      .mapAsync(1)(cr => shiftsProvider(cr).map(sa => (cr, sa)))
      .mapAsync(1) { case (cr, sa) => fixedPointsProvider(cr).map(fp => (cr, sa, fp)) }
      .mapAsync(1) { case (cr, sa, fp) => movementsProvider(cr).map(sm => (cr, sa, fp, sm)) }
      .via(toStaffMinutes(now))

  def toStaffMinutes(now: () => SDateLike): Flow[(ProcessingRequest, ShiftAssignments, FixedPointAssignments, StaffMovements), PortStateStaffMinutes, NotUsed] =
    Flow[(ProcessingRequest, ShiftAssignments, FixedPointAssignments, StaffMovements)]
      .collect { case (cr: TerminalUpdateRequest, sa, fp, sm) =>
        val staff = Staffing.staffAvailableByTerminalAndQueue(cr.start.millisSinceEpoch, sa, fp, Option(sm.movements))

        StaffMinutes(cr.minutesInMillis.map { minute =>
          val m = SDate(minute)
          val shifts = staff.shifts.terminalStaffAt(cr.terminal, m)
          val fixedPoints = staff.fixedPoints.terminalStaffAt(cr.terminal, SDate(m, Crunch.europeLondonTimeZone))
          val movements = staff.movements.terminalStaffAt(cr.terminal, minute)

          StaffMinute(cr.terminal, minute, shifts, fixedPoints, movements, lastUpdated = Option(now().millisSinceEpoch))
        })
      }

}
