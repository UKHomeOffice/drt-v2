package uk.gov.homeoffice.drt.service.staffing

import actors.DrtStaticParameters.time48HoursAgo
import actors.PartitionedPortStateActor.GetStateForDateRange
import actors.persistent.staffing.{ShiftsReadActor, UpdateShifts}
import akka.actor.{ActorRef, ActorSystem, PoisonPill}
import akka.pattern.ask
import akka.util.Timeout
import drt.shared.{MonthOfShifts, ShiftAssignments, StaffAssignmentLike}
import org.slf4j.LoggerFactory
import services.staffing.StaffTimeSlots
import uk.gov.homeoffice.drt.actor.commands.Commands.GetState
import uk.gov.homeoffice.drt.time.MilliDate.MillisSinceEpoch
import uk.gov.homeoffice.drt.time.TimeZoneHelper.europeLondonTimeZone
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, SDateLike}

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

case class ShiftsServiceImpl(liveShiftsActor: ActorRef,
                             shiftsWriteActor: ActorRef,
                            )
                            (implicit timeout: Timeout, ec: ExecutionContext, system: ActorSystem) extends ShiftsService {
  private val log = LoggerFactory.getLogger(getClass)

  override def shiftsForDate(date: LocalDate, maybePointInTime: Option[MillisSinceEpoch]): Future[ShiftAssignments] = {
    maybePointInTime match {
      case None =>
        liveShiftsForDate(date)

      case Some(millis) =>
        shiftsForPointInTime(SDate(millis))
    }
  }

  override def shiftsForMonth(month: MillisSinceEpoch): Future[MonthOfShifts] =
    liveShiftsActor
      .ask(GetState)
      .collect {
        case shifts: ShiftAssignments =>
          log.info(s"Shifts: Retrieved shifts from actor for month starting: ${SDate(month).toISOString}")
          val monthInLocalTime = SDate(month, europeLondonTimeZone)
          MonthOfShifts(month, StaffTimeSlots.getShiftsForMonth(shifts, monthInLocalTime))
      }

  private def liveShiftsForDate(date: LocalDate): Future[ShiftAssignments] = {
    val start = SDate(date).millisSinceEpoch
    val end = SDate(date).addDays(1).addMinutes(-1).millisSinceEpoch
    liveShiftsActor.ask(GetStateForDateRange(start, end))
      .map { case sa: ShiftAssignments => sa }
      .recoverWith {
        case t =>
          log.error(s"Error getting shifts for $date: ${t.getMessage}")
          Future(ShiftAssignments.empty)
      }
  }

  private def shiftsForPointInTime(pointInTime: SDateLike): Future[ShiftAssignments] = {
    val actorName = "shifts-read-actor-" + UUID.randomUUID().toString
    val shiftsReadActor: ActorRef = system.actorOf(ShiftsReadActor.props(pointInTime, time48HoursAgo(() => pointInTime)), actorName)

    val start = pointInTime.getLocalLastMidnight.millisSinceEpoch
    val end = pointInTime.getLocalNextMidnight.addMinutes(-1).millisSinceEpoch

    shiftsReadActor.ask(GetStateForDateRange(start, end))
      .map { case sa: ShiftAssignments =>
        shiftsReadActor ! PoisonPill
        sa
      }
      .recoverWith {
        case _ =>
          shiftsReadActor ! PoisonPill
          Future(ShiftAssignments.empty)
      }
  }

  override def updateShifts(shiftAssignments: Seq[StaffAssignmentLike]): Unit = {
    shiftsWriteActor ! UpdateShifts(shiftAssignments)
  }
}
