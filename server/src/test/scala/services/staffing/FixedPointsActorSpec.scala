package services.staffing

import actors.{FixedPointsActor, GetState}
import akka.actor.{ActorRef, Props}
import akka.pattern.AskableActorRef
import akka.testkit.TestProbe
import akka.util.Timeout
import drt.shared.{MilliDate, StaffAssignment, StaffAssignments}
import services.SDate
import services.crunch.CrunchTestLike

import scala.concurrent.Await
import scala.concurrent.duration._

class TestableFixedPointsActor(testProbe: ActorRef) extends FixedPointsActor {
  def sendAck(): Unit = testProbe ! MsgAck

  override def onUpdateState(fixedPointStaffAssignments: StaffAssignments): Unit = {
    super.onUpdateState(fixedPointStaffAssignments)
    sendAck()
  }
}
case object MsgAck

class FixedPointsActorSpec extends CrunchTestLike {
  implicit val timeout: Timeout = new Timeout(1 second)

  "Given some fixed points and a fixed points actor " +
    "When I send the fixed points as a string to the actor and then query the actor's state " +
    "Then I should get back the same fixed points I previously sent it" >> {
    val fixedPointStaffAssignments = StaffAssignments(
      Seq(StaffAssignment("Roving officer", "T1", MilliDate(SDate("2018-01-01T00:00").millisSinceEpoch), MilliDate(SDate("2018-01-01T00:14").millisSinceEpoch), 1, None))
    )

    val probe = TestProbe()

    val fixedPointsActor = system.actorOf(Props(classOf[TestableFixedPointsActor], probe.ref))
    val askableFixedPointsActor: AskableActorRef = fixedPointsActor

    fixedPointsActor ! fixedPointStaffAssignments

    probe.expectMsgAnyClassOf(MsgAck.getClass)

    val storedFixedPoints = Await.result(askableFixedPointsActor ? GetState, 1 second).asInstanceOf[StaffAssignments]
    val expected = fixedPointStaffAssignments

    storedFixedPoints === expected
  }
}

