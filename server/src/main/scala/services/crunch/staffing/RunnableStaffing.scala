package services.crunch.staffing

import akka.NotUsed
import akka.stream.scaladsl.Flow
import drt.shared.CrunchApi.{MinutesContainer, StaffMinute, StaffMinutes}
import drt.shared.{FixedPointAssignments, ShiftAssignments, StaffMovements, TM}
import org.slf4j.{Logger, LoggerFactory}
import services.graphstages.Staffing
import uk.gov.homeoffice.drt.actor.commands.{ProcessingRequest, TerminalUpdateRequest}
import uk.gov.homeoffice.drt.time.TimeZoneHelper.europeLondonTimeZone
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

import scala.concurrent.{ExecutionContext, Future}

object RunnableStaffing {
  val log: Logger = LoggerFactory.getLogger(getClass)

  import SDate.implicits.sdateFromMillisLocal

  def staffMinutesFlow(shiftsProvider: ProcessingRequest => Future[ShiftAssignments],
                       fixedPointsProvider: ProcessingRequest => Future[FixedPointAssignments],
                       movementsProvider: ProcessingRequest => Future[StaffMovements],
                       now: () => SDateLike,
                      )
                      (implicit ec: ExecutionContext): Flow[ProcessingRequest, MinutesContainer[StaffMinute, TM], NotUsed] =
    Flow[ProcessingRequest]
      .wireTap(processingRequest => log.info(s"${processingRequest.date} staffing crunch request started"))
      .mapAsync(1)(cr => shiftsProvider(cr).map(sa => (cr, sa)))
      .mapAsync(1) { case (cr, sa) => fixedPointsProvider(cr).map(fp => (cr, sa, fp)) }
      .mapAsync(1) { case (cr, sa, fp) => movementsProvider(cr).map(sm => (cr, sa, fp, sm)) }
      .via(toStaffMinutes(now))

  private def toStaffMinutes(now: () => SDateLike): Flow[(ProcessingRequest, ShiftAssignments, FixedPointAssignments, StaffMovements), MinutesContainer[StaffMinute, TM], NotUsed] =
    Flow[(ProcessingRequest, ShiftAssignments, FixedPointAssignments, StaffMovements)]
      .collect { case (processingRequest: TerminalUpdateRequest, sa, fp, sm) =>
        val staff = Staffing.staffAvailableByTerminalAndQueue(processingRequest.start.millisSinceEpoch, sa, fp, Option(sm.movements))

        StaffMinutes(processingRequest.minutesInMillis.map { minute =>
          val m = SDate(minute, europeLondonTimeZone)
          val shifts = staff.shifts.terminalStaffAt(processingRequest.terminal, m, sdateFromMillisLocal)
          val fixedPoints = staff.fixedPoints.terminalStaffAt(processingRequest.terminal, m, sdateFromMillisLocal)
          val movements = staff.movements.terminalStaffAt(processingRequest.terminal, minute)

          StaffMinute(processingRequest.terminal, minute, shifts, fixedPoints, movements, lastUpdated = Option(now().millisSinceEpoch))
        }).asContainer
      }
}
