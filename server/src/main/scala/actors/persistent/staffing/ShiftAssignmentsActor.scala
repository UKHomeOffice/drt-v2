package actors.persistent.staffing

import actors.daily.RequestAndTerminate
import actors.routing.SequentialWritesActor
import org.apache.pekko.actor.{ActorRef, ActorSystem, Props}
import org.apache.pekko.pattern.ask
import org.apache.pekko.util.Timeout
import drt.shared.StaffAssignmentLike
import uk.gov.homeoffice.drt.time.SDateLike

import scala.concurrent.ExecutionContext


object ShiftAssignmentsActor extends ShiftsActorLike {
  val snapshotInterval = 5000

  val persistenceId = "staff-assignments"

  def sequentialWritesProps(now: () => SDateLike,
                            expireBefore: () => SDateLike,
                            requestAndTerminateActor: ActorRef,
                            system: ActorSystem
                           )
                           (implicit timeout: Timeout): Props =
    Props(new SequentialWritesActor[ShiftUpdate](update => {
      val actor = system.actorOf(Props(new LegacyStaffAssignmentsActor(persistenceId, now, expireBefore, snapshotInterval)), s"staff-assignments-actor-writes")
      requestAndTerminateActor.ask(RequestAndTerminate(actor, update))
    }))
}
