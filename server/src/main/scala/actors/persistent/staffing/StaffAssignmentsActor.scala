package actors.persistent.staffing

import actors.daily.RequestAndTerminate
import actors.routing.SequentialWritesActor
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import drt.shared.StaffAssignmentLike
import uk.gov.homeoffice.drt.time.SDateLike

import scala.concurrent.ExecutionContext


object StaffAssignmentsActor extends ShiftsActorLike {
  val snapshotInterval = 5000

  val persistenceId = "staff-assignments"

  case class ReplaceAllStaffAssignments(newStaffAssignments: Seq[StaffAssignmentLike]) extends ShiftUpdate

  case class UpdateStaffAssignments(staffAssignmentsToUpdate: Seq[StaffAssignmentLike]) extends ShiftUpdate

  def sequentialWritesProps(now: () => SDateLike,
                            expireBefore: () => SDateLike,
                            requestAndTerminateActor: ActorRef,
                            system: ActorSystem
                           )
                           (implicit timeout: Timeout, ec: ExecutionContext): Props =
    Props(new SequentialWritesActor[ShiftUpdate](update => {
      val actor = system.actorOf(Props(new ShiftsActor(persistenceId, now, expireBefore, snapshotInterval)), s"staff-assignments-actor-writes")
      requestAndTerminateActor.ask(RequestAndTerminate(actor, update))
    }))

}