package uk.gov.homeoffice.drt.service.staffing

import actors.DrtStaticParameters.time48HoursAgo
import actors.PartitionedPortStateActor.GetStateForDateRange
import actors.persistent.staffing.ShiftsActor.UpdateShifts
import actors.persistent.staffing.{ShiftsReadActor, StaffAssignmentsActor}
import akka.actor.{ActorRef, ActorSystem, PoisonPill}
import akka.pattern.ask
import akka.util.Timeout
import drt.shared.{ShiftAssignments, StaffAssignmentLike}
import uk.gov.homeoffice.drt.actor.commands.Commands.GetState
import uk.gov.homeoffice.drt.time.MilliDate.MillisSinceEpoch
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, SDateLike}

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}


object StaffAssignmentsServiceImpl {
  def pitActor(implicit system: ActorSystem): SDateLike => ActorRef = pointInTime => {
    val actorName = s"staff-store-read-actor-" + UUID.randomUUID().toString
    system.actorOf(ShiftsReadActor.props(StaffAssignmentsActor.persistenceId, pointInTime, time48HoursAgo(() => pointInTime)), actorName)
  }
}

case class StaffAssignmentsServiceImpl(liveStaffShiftsReadActor: ActorRef,
                                       shiftsStaffSequentialWritesActor: ActorRef,
                                       pitActor: SDateLike => ActorRef,
                                     )
                                      (implicit timeout: Timeout, ec: ExecutionContext) extends StaffAssignmentsService {
  override def staffAssignmentsForDate(date: LocalDate, maybePointInTime: Option[MillisSinceEpoch]): Future[ShiftAssignments] = {
    maybePointInTime match {
      case None =>
        liveStaffAssignmentsForDate(date)

      case Some(millis) =>
        staffAssignmentsForPointInTime(SDate(millis))
    }
  }

  override def allStaffAssignments: Future[ShiftAssignments] =
    liveStaffShiftsReadActor
      .ask(GetState)
      .mapTo[ShiftAssignments]

  private def liveStaffAssignmentsForDate(date: LocalDate): Future[ShiftAssignments] = {
    val start = SDate(date).millisSinceEpoch
    val end = SDate(date).addDays(1).addMinutes(-1).millisSinceEpoch
    liveStaffShiftsReadActor.ask(GetStateForDateRange(start, end))
      .map { case sa: ShiftAssignments => sa }
  }

  private def staffAssignmentsForPointInTime(pointInTime: SDateLike): Future[ShiftAssignments] = {
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

  override def updateStaffAssignments(shiftAssignments: Seq[StaffAssignmentLike]): Future[ShiftAssignments] =
    shiftsStaffSequentialWritesActor
      .ask(UpdateShifts(shiftAssignments))
      .mapTo[ShiftAssignments]
}

