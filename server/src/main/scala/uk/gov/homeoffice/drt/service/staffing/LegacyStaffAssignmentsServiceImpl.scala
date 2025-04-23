package uk.gov.homeoffice.drt.service.staffing

import actors.DrtStaticParameters.time48HoursAgo
import actors.PartitionedPortStateActor.GetStateForDateRange
import actors.persistent.staffing.ShiftsActor.UpdateShifts
import actors.persistent.staffing.{ShiftsActor, ShiftsReadActor}
import org.apache.pekko.actor.{ActorRef, ActorSystem, PoisonPill}
import org.apache.pekko.pattern.ask
import org.apache.pekko.util.Timeout
import drt.shared.{ShiftAssignments, StaffAssignmentLike}
import uk.gov.homeoffice.drt.actor.commands.Commands.GetState
import uk.gov.homeoffice.drt.time.MilliDate.MillisSinceEpoch
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, SDateLike}

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}


object LegacyStaffAssignmentsServiceImpl {
  def pitActor(implicit system: ActorSystem): SDateLike => ActorRef = pointInTime => {
    val actorName = s"shifts-read-actor-" + UUID.randomUUID().toString
    system.actorOf(ShiftsReadActor.props(ShiftsActor.persistenceId, pointInTime, time48HoursAgo(() => pointInTime)), actorName)
  }
}

case class LegacyStaffAssignmentsServiceImpl(liveShiftsActor: ActorRef,
                                             shiftsWriteActor: ActorRef,
                                             pitActor: SDateLike => ActorRef,
                            )
                                            (implicit timeout: Timeout, ec: ExecutionContext) extends LegacyStaffAssignmentsService {
  override def shiftsForDate(date: LocalDate, maybePointInTime: Option[MillisSinceEpoch]): Future[ShiftAssignments] = {
    maybePointInTime match {
      case None =>
        liveShiftsForDate(date)

      case Some(millis) =>
        staffAssignmentsForPointInTime(SDate(millis))
    }
  }

  override def allShifts: Future[ShiftAssignments] =
    liveShiftsActor
      .ask(GetState)
      .mapTo[ShiftAssignments]

  private def liveShiftsForDate(date: LocalDate): Future[ShiftAssignments] = {
    val start = SDate(date).millisSinceEpoch
    val end = SDate(date).addDays(1).addMinutes(-1).millisSinceEpoch
    liveShiftsActor.ask(GetStateForDateRange(start, end))
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

  override def updateShifts(shiftAssignments: Seq[StaffAssignmentLike]): Future[ShiftAssignments] =
    shiftsWriteActor
      .ask(UpdateShifts(shiftAssignments))
      .mapTo[ShiftAssignments]
}
