package actors.persistent.staffing

import actors.daily.RequestAndTerminate
import actors.routing.SequentialWritesActor
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import drt.shared.StaffAssignmentLike
import uk.gov.homeoffice.drt.time.SDateLike

import scala.concurrent.ExecutionContext


object ShiftsStaffActor extends ShiftsActorLike {
  val snapshotInterval = 5000

  val persistenceId = "shifts-staff-store"

  case class ReplaceAllShifts(newShifts: Seq[StaffAssignmentLike]) extends ShiftUpdate

  case class UpdateShifts(shiftsToUpdate: Seq[StaffAssignmentLike]) extends ShiftUpdate

  def sequentialWritesProps(now: () => SDateLike,
                            expireBefore: () => SDateLike,
                            requestAndTerminateActor: ActorRef,
                            system: ActorSystem
                           )
                           (implicit timeout: Timeout, ec: ExecutionContext): Props =
    Props(new SequentialWritesActor[ShiftUpdate](update => {
      val actor = system.actorOf(Props(new ShiftsActor(persistenceId, now, expireBefore, snapshotInterval)), s"shifts-staff-actor-writes")
      requestAndTerminateActor.ask(RequestAndTerminate(actor, update))
    }))

}