package actors

import actors.pointInTime.FixedPointsReadActor
import akka.actor.{ActorRef, ActorSystem, PoisonPill, Props}
import akka.testkit.{ImplicitSender, TestKit}
import com.typesafe.config.ConfigFactory
import drt.shared.Terminals.T1
import drt.shared._
import org.specs2.mutable.SpecificationLike
import org.specs2.specification.AfterEach
import services.SDate

import scala.collection.JavaConverters._
import scala.concurrent.duration._


class FixedPointsActorSpec extends TestKit(ActorSystem("FixedPointsActorSpec", ConfigFactory.parseMap(Map(
  "akka.log-dead-letters" -> 0,
  "akka.actor.warn-about-java-serializer-usage" -> false,
  "akka.persistence.journal.plugin" -> "akka.persistence.journal.leveldb",
  "akka.persistence.journal.leveldb.dir" -> PersistenceHelper.dbLocation,
  "akka.persistence.snapshot-store.plugin" -> "akka.persistence.snapshot-store.local",
  "akka.persistence.snapshot-store.local.dir" -> s"${PersistenceHelper.dbLocation}/snapshot"
).asJava)))
  with SpecificationLike
  with AfterEach
  with ImplicitSender {
  sequential
  isolated

  override def after: Unit = {
    TestKit.shutdownActorSystem(system)
    PersistenceCleanup.deleteJournal(PersistenceHelper.dbLocation)
  }

  import StaffAssignmentGenerator._

  "FixedPoints actor" should {
    "remember a fixedPoint staff assignmesnt added before a shutdown" in {
      val startTime = MilliDate(SDate(s"2017-01-01T07:00").millisSinceEpoch)
      val endTime = MilliDate(SDate(s"2017-01-01T15:00").millisSinceEpoch)
      val fixedPoints = FixedPointAssignments(Seq(StaffAssignment("Morning", T1, startTime, endTime, 10, None)))

      val now: () => SDateLike = () => SDate("2017-01-01T23:59")
      val actor = system.actorOf(Props(classOf[FixedPointsActor], now), "fixedPointsActor1")

      actor ! SetFixedPoints(fixedPoints.assignments)
      expectMsg(SetFixedPointsAck(fixedPoints.assignments))
      actor ! PoisonPill

      val newActor = system.actorOf(Props(classOf[FixedPointsActor], now), "fixedPointsActor2")

      newActor ! GetState

      expectMsg(fixedPoints)

      true
    }
  }

  "correctly remember an update to a fixed point after a restart" in {
    val fixedPoint1 = generateStaffAssignment("Morning 1", T1, "2017-01-01T07:00", "2017-01-01T15:00", 10)
    val fixedPoint2 = generateStaffAssignment("Morning 2", T1, "2017-01-01T07:30", "2017-01-01T15:30", 10)

    val now: () => SDateLike = () => SDate("2017-01-01T23:59")

    val actor = system.actorOf(Props(classOf[FixedPointsActor], now), "fixedPointsActor1")

    actor ! SetFixedPoints(Seq(fixedPoint1, fixedPoint2))
    expectMsg(SetFixedPointsAck(Seq(fixedPoint1, fixedPoint2)))

    val updatedFixedPoints = Seq(fixedPoint1, fixedPoint2).map(_.copy(numberOfStaff = 0))
    actor ! SetFixedPoints(updatedFixedPoints)
    expectMsg(SetFixedPointsAck(updatedFixedPoints))
    actor ! PoisonPill

    val newActor = system.actorOf(Props(classOf[FixedPointsActor], now), "fixedPointsActor2")

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

    val actor = system.actorOf(Props(classOf[FixedPointsActor], now), "fixedPointsActor1")

    actor ! SetFixedPoints(Seq(fixedPoint1, fixedPoint2, fixedPoint3, fixedPoint4))
    expectMsg(SetFixedPointsAck(Seq(fixedPoint1, fixedPoint2, fixedPoint3, fixedPoint4)))

    val updatedFixedPoint1 = fixedPoint1.copy(numberOfStaff = 0)
    val updatedFixedPoint3 = fixedPoint3.copy(numberOfStaff = 0)
    actor ! SetFixedPoints(Seq(updatedFixedPoint1, updatedFixedPoint3))
    expectMsg(SetFixedPointsAck(Seq(updatedFixedPoint1, updatedFixedPoint3)))
    actor ! PoisonPill

    val newActor = system.actorOf(Props(classOf[FixedPointsActor], now), "fixedPointsActor2")

    newActor ! GetState
    val expected = Set(updatedFixedPoint1, updatedFixedPoint3)

    val result = expectMsgPF(1 second) {
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

    val result = expectMsgPF(1 second) {
      case FixedPointAssignments(sa) => sa.toSet
    }

    result === expected
  }

  def newStaffActor(now: () => SDateLike): ActorRef = system.actorOf(Props(classOf[FixedPointsActor], now))
  def newStaffPointInTimeActor(now: () => SDateLike): ActorRef = system.actorOf(Props(classOf[FixedPointsReadActor], now()))

  def nowAs(date: String): () => SDateLike = () => SDate(date)
}
