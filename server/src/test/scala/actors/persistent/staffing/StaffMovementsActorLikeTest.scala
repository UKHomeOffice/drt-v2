package actors.persistent.staffing

import actors.persistent.staffing.StaffMovementsActor.staffMovementMessageToStaffMovement
import drt.shared.{StaffMovement, StaffMovements}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.protobuf.messages.StaffMovementMessages.{StaffMovementMessage, StaffMovementsMessage}

class StaffMovementsActorLikeTest extends AnyWordSpec with Matchers {
  "eventToState" should {
    "add incoming staff movements to the existing state" in {
      val existingMovement = StaffMovement(T1, "reason", 1234567890L, 1, "uuid1", None, None)
      val newMovement = StaffMovementMessage(Option("T1"), Option("reason"), Option(1234567890L), Option(1), Option("uuid2"))

      val actorLike = new StaffMovementsActorLike {}

      val existingState = StaffMovementsState(StaffMovements(Seq(existingMovement)))

      val (updatedState, _) = actorLike.eventToState(existingState, StaffMovementsMessage(Seq(newMovement)))

      updatedState shouldEqual StaffMovementsState(StaffMovements(Seq(existingMovement, staffMovementMessageToStaffMovement(newMovement))))
    }

    "ignore any staff movement events with a UUID that already exists (because these are duplicates)" in {
      val existingMovement = StaffMovement(T1, "reason", 1234567890L, 1, "uuid1", None, None)
      val newMovement = StaffMovementMessage(Option("T1"), Option("reason"), Option(1234567890L), Option(1), Option("uuid1"))

      val actorLike = new StaffMovementsActorLike {}

      val existingState = StaffMovementsState(StaffMovements(Seq(existingMovement)))

      val (updatedState, _) = actorLike.eventToState(existingState, StaffMovementsMessage(Seq(newMovement)))

      updatedState shouldEqual existingState
    }
  }
}
