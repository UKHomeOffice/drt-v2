package services.staffing

import actors.DrtStaticParameters.expire48HoursAgo
import actors.{DrtStaticParameters, GetState, SetShifts, ShiftsActor}
import akka.actor.{ActorRef, PoisonPill, Props}
import akka.pattern.AskableActorRef
import akka.testkit.TestProbe
import akka.util.Timeout
import drt.shared.{MilliDate, SDateLike, ShiftAssignments, StaffAssignment}
import services.SDate
import services.crunch.CrunchTestLike
import services.graphstages.Crunch

import scala.concurrent.Await
import scala.concurrent.duration._

class TestableShiftsActor(testProbe: ActorRef, now: () => SDateLike, expireBefore: () => SDateLike) extends ShiftsActor(now, expireBefore) {
  def sendAck(): Unit = testProbe ! MsgAck

  override def onUpdateState(shiftStaffAssignments: ShiftAssignments): Unit = {
    super.onUpdateState(shiftStaffAssignments)
    sendAck()
  }
}

class ShiftsActorSpec extends CrunchTestLike {
  implicit val timeout: Timeout = new Timeout(1 second)

  val nowProvider: () => SDateLike = () => SDate("2018-01-01T00:00")

  "Given some shifts and a shifts actor " +
    "When I send the shifts as a string to the actor and then query the actor's state " +
    "Then I should get back the same shifts I previously sent it" >> {
    val shiftStaffAssignments = ShiftAssignments(
      Seq(StaffAssignment("Roving officer", "T1", MilliDate(SDate("2018-01-01T00:00").millisSinceEpoch), MilliDate(SDate("2018-01-01T00:14").millisSinceEpoch), 1, None))
    )

    val probe = TestProbe()

    val shiftsActor = system.actorOf(Props(classOf[TestableShiftsActor], probe.ref, nowProvider, expire48HoursAgo(nowProvider)))
    val askableShiftsActor: AskableActorRef = shiftsActor

    shiftsActor ! SetShifts(shiftStaffAssignments.assignments)

    probe.expectMsgAnyClassOf(MsgAck.getClass)

    val storedShifts = Await.result(askableShiftsActor ? GetState, 1 second).asInstanceOf[ShiftAssignments]
    val expected = shiftStaffAssignments

    storedShifts === expected
  }

  "Given some shifts and a shifts actor " +
    "When I send the shifts as a string to the actor, then query after a restart " +
    "Then I should get back the same shifts I previously sent it" >> {
    val shiftStaffAssignments = ShiftAssignments(
      Seq(StaffAssignment("Roving officer", "T1", MilliDate(SDate("2018-01-01T00:00").millisSinceEpoch), MilliDate(SDate("2018-01-01T00:14").millisSinceEpoch), 1, None))
    )

    val probe = TestProbe()

    val shiftsActor = system.actorOf(Props(classOf[TestableShiftsActor], probe.ref, nowProvider, expire48HoursAgo(nowProvider)))

    shiftsActor ! SetShifts(shiftStaffAssignments.assignments)

    probe.expectMsgAnyClassOf(MsgAck.getClass)
    shiftsActor ! PoisonPill

    Thread.sleep(250)

    val shiftsActor2: AskableActorRef = system.actorOf(Props(classOf[TestableShiftsActor], probe.ref, nowProvider, expire48HoursAgo(nowProvider)))

    val storedShifts = Await.result(shiftsActor2 ? GetState, 1 second).asInstanceOf[ShiftAssignments]
    val expected = shiftStaffAssignments

    storedShifts === expected
  }
}

