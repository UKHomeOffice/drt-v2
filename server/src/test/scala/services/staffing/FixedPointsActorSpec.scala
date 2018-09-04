package services.staffing

import actors.{FixedPointsActor, GetState}
import akka.actor.{ActorRef, Props}
import akka.pattern.AskableActorRef
import akka.testkit.TestProbe
import akka.util.Timeout
import drt.shared.MilliDate
import services.SDate
import services.crunch.CrunchTestLike
import services.graphstages.StaffAssignment

import scala.concurrent.Await
import scala.concurrent.duration._

class TestableFixedPointsActor(testProbe: ActorRef) extends FixedPointsActor {
  def sendAck(thing: Any): Unit = testProbe ! MsgAck(thing)

  override def onUpdateState(data: Seq[StaffAssignment]): Unit = {
    super.onUpdateState(data)
    sendAck(data)
  }
}
case class MsgAck(thing: Any)

class FixedPointsActorSpec extends CrunchTestLike {
  implicit val timeout = new Timeout(1 second)

  "Given some fixed points and a fixed points actor " +
    "When I send the fixed points as a string to the actor and then query the actor's state " +
    "Then I should get back the same fixed points I previously sent it" >> {
    val assignment = StaffAssignment("Roving officer", "T1", MilliDate(SDate("2018-01-01T00:00").millisSinceEpoch), MilliDate(SDate("2018-01-01T00:14").millisSinceEpoch), 1)
    val fixedPoints = assignment.toCsv

    val probe = TestProbe()

    val fixedPointsActor = system.actorOf(Props(classOf[TestableFixedPointsActor], probe.ref))
    val askableFixedPointsActor: AskableActorRef = fixedPointsActor

    fixedPointsActor ! fixedPoints

    probe.expectMsgAnyClassOf(classOf[MsgAck])

    val storedFixedPointsWithSpacing = Await.result(askableFixedPointsActor ? GetState, 1 second).asInstanceOf[String]
    val storedFixedPointsWithoutSpacing = storedFixedPointsWithSpacing.replace(", ", ",")

    storedFixedPointsWithoutSpacing === fixedPoints
  }
}

