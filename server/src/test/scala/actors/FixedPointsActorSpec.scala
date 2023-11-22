package actors

import actors.persistent.staffing._
import akka.actor.{ActorRef, PoisonPill, Props}
import akka.testkit.ImplicitSender
import drt.shared._
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.actor.commands.Commands.GetState
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

import scala.concurrent.duration._


class FixedPointsActorSpec extends CrunchTestLike with ImplicitSender {
  sequential
  isolated

  import StaffAssignmentGenerator._

  val forecastLengthDays = 2

  "FixedPoints actor" should {
    "remember a fixedPoint staff assignments added before a shutdown" in {
      val startTime = SDate(s"2017-01-01T07:00").millisSinceEpoch
      val endTime = SDate(s"2017-01-01T15:00").millisSinceEpoch
      val fixedPoints = FixedPointAssignments(Seq(StaffAssignment("Morning", T1, startTime, endTime, 10, None)))

      val now: () => SDateLike = () => SDate("2017-01-01T23:59")
      val actor = system.actorOf(Props(new FixedPointsActor(now, 1440, forecastLengthDays)), "fixedPointsActor1")

      actor ! SetFixedPoints(fixedPoints.assignments)
      expectMsg(SetFixedPointsAck(fixedPoints.assignments))
      actor ! PoisonPill

      val newActor = system.actorOf(Props(new FixedPointsActor(now, 1440, forecastLengthDays)), "fixedPointsActor2")

      newActor ! GetState

      expectMsg(fixedPoints)

      true
    }
  }

  "correctly remember an update to a fixed point after a restart" in {
    val fixedPoint1 = generateStaffAssignment("Morning 1", T1, "2017-01-01T07:00", "2017-01-01T15:00", 10)
    val fixedPoint2 = generateStaffAssignment("Morning 2", T1, "2017-01-01T07:30", "2017-01-01T15:30", 10)

    val now: () => SDateLike = () => SDate("2017-01-01T23:59")

    val actor = system.actorOf(Props(new FixedPointsActor(now, 1440, forecastLengthDays)), "fixedPointsActor1")

    actor ! SetFixedPoints(Seq(fixedPoint1, fixedPoint2))
    expectMsg(SetFixedPointsAck(Seq(fixedPoint1, fixedPoint2)))

    val updatedFixedPoints = Seq(fixedPoint1, fixedPoint2).map(_.copy(numberOfStaff = 0))
    actor ! SetFixedPoints(updatedFixedPoints)
    expectMsg(SetFixedPointsAck(updatedFixedPoints))
    actor ! PoisonPill

    val newActor = system.actorOf(Props(new FixedPointsActor(now, 1440, forecastLengthDays)), "fixedPointsActor2")

    newActor ! GetState
    val expected = FixedPointAssignments(updatedFixedPoints)

    expectMsg(expected)

    true
  }

  "remember multiple added fixed points and correctly remember updates after a restart" in {
    val fixedPoint1 = generateStaffAssignment("Morning 1", T1, "2017-01-01T07:00", "2017-01-01T15:00", 10)
    val fixedPoint2 = generateStaffAssignment("Morning 2", T1, "2017-01-01T07:30", "2017-01-01T15:30", 5)
    val fixedPoint3 = generateStaffAssignment("Evening 1", T1, "2017-01-01T17:00", "2017-01-01T23:00", 11)
    val fixedPoint4 = generateStaffAssignment("Evening 2", T1, "2017-01-01T17:30", "2017-01-01T23:30", 6)

    val now: () => SDateLike = () => SDate("2017-01-01T23:59")

    val actor = system.actorOf(Props(new FixedPointsActor(now, 1440, forecastLengthDays)), "fixedPointsActor1")

    actor ! SetFixedPoints(Seq(fixedPoint1, fixedPoint2, fixedPoint3, fixedPoint4))
    expectMsg(SetFixedPointsAck(Seq(fixedPoint1, fixedPoint2, fixedPoint3, fixedPoint4)))

    val updatedFixedPoint1 = fixedPoint1.copy(numberOfStaff = 0)
    val updatedFixedPoint3 = fixedPoint3.copy(numberOfStaff = 0)
    actor ! SetFixedPoints(Seq(updatedFixedPoint1, updatedFixedPoint3))
    expectMsg(SetFixedPointsAck(Seq(updatedFixedPoint1, updatedFixedPoint3)))
    actor ! PoisonPill

    val newActor = system.actorOf(Props(new FixedPointsActor(now, 1440, forecastLengthDays)), "fixedPointsActor2")

    newActor ! GetState
    val expected = Set(updatedFixedPoint1, updatedFixedPoint3)

    val result = expectMsgPF(1.second) {
      case FixedPointAssignments(sa) => sa.toSet
    }

    result === expected
  }

  "restore fixed points to a point in time view" in {
    val fixedPoint1 = generateStaffAssignment("Morning 1", T1, "2017-01-01T07:00", "2017-01-01T15:00", 10)
    val fixedPoint2 = generateStaffAssignment("Morning 2", T1, "2017-01-01T07:30", "2017-01-01T15:30", 5)
    val fixedPoint3 = generateStaffAssignment("Evening 1", T1, "2017-01-01T17:00", "2017-01-01T23:00", 11)
    val fixedPoint4 = generateStaffAssignment("Evening 2", T1, "2017-01-01T17:30", "2017-01-01T23:30", 6)

    val actor2000 = newStaffActor(nowAs("2017-01-01T20:00"))

    actor2000 ! SetFixedPoints(Seq(fixedPoint1))
    expectMsg(SetFixedPointsAck(Seq(fixedPoint1)))
    actor2000 ! PoisonPill

    val actor2005 = newStaffActor(nowAs("2017-01-01T20:05"))

    actor2005 ! SetFixedPoints(Seq(fixedPoint2))
    expectMsg(SetFixedPointsAck(Seq(fixedPoint2)))
    actor2005 ! PoisonPill

    val actor2010 = newStaffActor(nowAs("2017-01-01T20:10"))

    actor2010 ! SetFixedPoints(Seq(fixedPoint3, fixedPoint4))
    expectMsg(SetFixedPointsAck(Seq(fixedPoint3, fixedPoint4)))
    actor2010 ! PoisonPill

    val actorPit2006 = newStaffPointInTimeActor(nowAs("2017-01-01T20:06"))

    actorPit2006 ! GetState
    val expected = Set(fixedPoint2)

    val result = expectMsgPF(1.second) {
      case FixedPointAssignments(sa) => sa.toSet
    }

    result === expected
  }

  def newStaffActor(now: () => SDateLike): ActorRef = system.actorOf(Props(new FixedPointsActor(now, 1440, forecastLengthDays)))
  def newStaffPointInTimeActor(now: () => SDateLike): ActorRef = system.actorOf(Props(new FixedPointsReadActor(
    now(),
    now,
    defaultAirportConfig.minutesToCrunch,
    forecastLengthDays)))

  def nowAs(date: String): () => SDateLike = () => SDate(date)
}
