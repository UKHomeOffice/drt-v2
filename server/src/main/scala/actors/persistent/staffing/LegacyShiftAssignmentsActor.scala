package actors.persistent.staffing

import actors.daily.RequestAndTerminate
import actors.routing.SequentialWritesActor
import drt.shared.StaffAssignmentLike
import org.apache.pekko.actor.{ActorRef, ActorSystem, Props}
import org.apache.pekko.pattern.ask
import org.apache.pekko.util.Timeout
import uk.gov.homeoffice.drt.time.SDateLike

object LegacyShiftAssignmentsActor extends ShiftsActorLike {
  val snapshotInterval = 5000

  val persistenceId = "shifts-store"

  case class ReplaceAllShifts(newShifts: Seq[StaffAssignmentLike]) extends ShiftUpdate

  case class UpdateShifts(shiftsToUpdate: Seq[StaffAssignmentLike]) extends ShiftUpdate

  def sequentialWritesProps(now: () => SDateLike,
                            expireBefore: () => SDateLike,
                            requestAndTerminateActor: ActorRef,
                            system: ActorSystem
                           )
                           (implicit timeout: Timeout): Props =
    Props(new SequentialWritesActor[ShiftUpdate](update => {
      val actor = system.actorOf(Props(new ShiftAssignmentsActorLike(persistenceId, now, expireBefore, snapshotInterval)), s"shifts-actor-writes")
      requestAndTerminateActor.ask(RequestAndTerminate(actor, update))
    }))
}
