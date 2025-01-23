package uk.gov.homeoffice.drt.service.staffing

import actors.DrtStaticParameters.time48HoursAgo
import actors.PartitionedPortStateActor.GetStateForDateRange
import actors.persistent.staffing.ShiftsActor.UpdateShifts
import actors.persistent.staffing.{ShiftsReadActor, ShiftsStaffActor}
import akka.actor.{ActorRef, ActorSystem, PoisonPill}
import akka.pattern.ask
import akka.util.Timeout
import drt.shared.{ShiftAssignments, StaffAssignmentLike}
import uk.gov.homeoffice.drt.actor.commands.Commands.GetState
import uk.gov.homeoffice.drt.time.MilliDate.MillisSinceEpoch
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, SDateLike}

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}


object StaffShiftsPlanServiceImpl {
  def pitActor(implicit system: ActorSystem): SDateLike => ActorRef = pointInTime => {
    val actorName = s"staff-store-read-actor-" + UUID.randomUUID().toString
    system.actorOf(ShiftsReadActor.props(ShiftsStaffActor.persistenceId, pointInTime, time48HoursAgo(() => pointInTime)), actorName)
  }
}

case class StaffShiftsPlanServiceImpl(liveStaffShiftsReadActor: ActorRef,
                                      shiftsStaffSequentialWritesActor: ActorRef,
                                      pitActor: SDateLike => ActorRef,
                                     )
                                     (implicit timeout: Timeout, ec: ExecutionContext) extends StaffShiftsPlanService {
  override def shiftsForDate(date: LocalDate, maybePointInTime: Option[MillisSinceEpoch]): Future[ShiftAssignments] = {
    maybePointInTime match {
      case None =>
        liveShiftsForDate(date)

      case Some(millis) =>
        shiftsForPointInTime(SDate(millis))
    }
  }

  override def allShifts: Future[ShiftAssignments] =
    liveStaffShiftsReadActor
      .ask(GetState)
      .mapTo[ShiftAssignments]

  private def liveShiftsForDate(date: LocalDate): Future[ShiftAssignments] = {
    val start = SDate(date).millisSinceEpoch
    val end = SDate(date).addDays(1).addMinutes(-1).millisSinceEpoch
    shiftsStaffSequentialWritesActor.ask(GetStateForDateRange(start, end))
      .map { case sa: ShiftAssignments => sa }
  }

  private def shiftsForPointInTime(pointInTime: SDateLike): Future[ShiftAssignments] = {
    val shiftsReadActor: ActorRef = pitActor(pointInTime)

    val start = pointInTime.getLocalLastMidnight.millisSinceEpoch
    val end = pointInTime.getLocalNextMidnight.addMinutes(-1).millisSinceEpoch

    shiftsReadActor.ask(GetStateForDateRange(start, end))
      .map { case sa: ShiftAssignments =>
        shiftsReadActor ! PoisonPill
        sa
      }
      .recoverWith {
        case t =>
          shiftsReadActor ! PoisonPill
          throw t
      }
  }

  override def updateShifts(shiftAssignments: Seq[StaffAssignmentLike]): Future[ShiftAssignments] =
    shiftsStaffSequentialWritesActor
      .ask(UpdateShifts(shiftAssignments))
      .mapTo[ShiftAssignments]
}

