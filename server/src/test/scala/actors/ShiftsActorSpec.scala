package actors


import java.util.UUID

import actors.pointInTime.ShiftsReadActor
import akka.actor.{ActorRef, ActorSystem, PoisonPill, Props}
import akka.testkit.{ImplicitSender, TestKit}
import com.typesafe.config.ConfigFactory
import drt.shared.FlightsApi.TerminalName
import drt.shared._
import org.specs2.mutable.SpecificationLike
import org.specs2.specification.AfterEach
import services.SDate

import scala.collection.JavaConversions._
import scala.concurrent.duration._
import scala.language.reflectiveCalls


class ShiftsActorSpec extends TestKit(ActorSystem("ShiftsActorSpec", ConfigFactory.parseMap(Map(
  "akka.persistence.journal.plugin" -> "akka.persistence.journal.leveldb",
  "akka.persistence.journal.leveldb.dir" -> PersistenceHelper.dbLocation,
  "akka.persistence.snapshot-store.plugin" -> "akka.persistence.snapshot-store.local",
  "akka.persistence.snapshot-store.local.dir" -> s"${PersistenceHelper.dbLocation}/snapshot"
))))
  with SpecificationLike
  with AfterEach
  with ImplicitSender {
  sequential
  isolated

  override def after: Unit = {
    TestKit.shutdownActorSystem(system)
    PersistenceCleanup.deleteJournal(PersistenceHelper.dbLocation)
  }
  
  "Shifts actor" should {
    "remember a shift staff assignmesnt added before a shutdown" in {
      val startTime = MilliDate(SDate(s"2017-01-01T07:00").millisSinceEpoch)
      val endTime = MilliDate(SDate(s"2017-01-01T15:00").millisSinceEpoch)
      val shifts = ShiftAssignments(Seq(StaffAssignment("Morning", "T1", startTime, endTime, 10, None)))

      val now: () => SDateLike = () => SDate("2017-01-01T23:59")
      val expireAfterOneDay: () => SDateLike = () => now().addDays(-1)

      val actor = system.actorOf(Props(classOf[ShiftsActor], now, expireAfterOneDay), "shiftsActor")

      actor ! UpdateShifts(shifts.assignments)
      expectMsg(UpdateShiftsAck(shifts.assignments))
      actor ! PoisonPill

      Thread.sleep(100)

      val newActor = system.actorOf(Props(classOf[ShiftsActor], now, expireAfterOneDay), "shiftsActor")

      newActor ! GetState

      expectMsg(shifts)

      true
    }

    "correctly remember an update to a shift after a restart" in {
      val shift1 = generateStaffAssignment("Morning 1", "T1", "2017-01-01T07:00", "2017-01-01T15:00", 10)
      val shift2 = generateStaffAssignment("Morning 2", "T1", "2017-01-01T07:30", "2017-01-01T15:30", 10)

      val now: () => SDateLike = () => SDate("2017-01-01T23:59")
      val expireAfterOneDay: () => SDateLike = () => now().addDays(-1)

      val actor = system.actorOf(Props(classOf[ShiftsActor], now, expireAfterOneDay), "shiftsActor1")

      actor ! UpdateShifts(Seq(shift1, shift2))
      expectMsg(UpdateShiftsAck(Seq(shift1, shift2)))

      val updatedShifts = Seq(shift1, shift2).map(_.copy(numberOfStaff = 0))
      actor ! UpdateShifts(updatedShifts)
      expectMsg(UpdateShiftsAck(updatedShifts))
      actor ! PoisonPill

      val newActor = system.actorOf(Props(classOf[ShiftsActor], now, expireAfterOneDay), "shiftsActor2")

      newActor ! GetState
      val expected = ShiftAssignments(updatedShifts)

      expectMsg(expected)

      true
    }

    "remember multiple added shifts and correctly remember movements after a restart" in {
      val shift1 = generateStaffAssignment("Morning 1", "T1", "2017-01-01T07:00", "2017-01-01T15:00", 10)
      val shift2 = generateStaffAssignment("Morning 2", "T1", "2017-01-01T07:30", "2017-01-01T15:30", 5)
      val shift3 = generateStaffAssignment("Evening 1", "T1", "2017-01-01T17:00", "2017-01-01T23:00", 11)
      val shift4 = generateStaffAssignment("Evening 2", "T1", "2017-01-01T17:30", "2017-01-01T23:30", 6)

      val now: () => SDateLike = () => SDate("2017-01-01T23:59")
      val expireAfterOneDay: () => SDateLike = () => now().addDays(-1)

      val actor = system.actorOf(Props(classOf[ShiftsActor], now, expireAfterOneDay), "shiftsActor1")

      actor ! UpdateShifts(Seq(shift1, shift2, shift3, shift4))
      expectMsg(UpdateShiftsAck(Seq(shift1, shift2, shift3, shift4)))

      val updatedShift1 = shift1.copy(numberOfStaff = 0)
      val updatedShift3 = shift3.copy(numberOfStaff = 0)
      actor ! UpdateShifts(Seq(updatedShift1, updatedShift3))
      expectMsg(UpdateShiftsAck(Seq(updatedShift1, updatedShift3)))
      actor ! PoisonPill

      val newActor = system.actorOf(Props(classOf[ShiftsActor], now, expireAfterOneDay), "shiftsActor2")

      newActor ! GetState
      val expected = Set(updatedShift1, shift2, updatedShift3, shift4)

      val result = expectMsgPF(1 second) {
        case ShiftAssignments(sa) => sa.toSet
      }

      result === expected
    }

    "restore shifts to a point in time view" in {
      val shift1 = generateStaffAssignment("Morning 1", "T1", "2017-01-01T07:00", "2017-01-01T15:00", 10)
      val shift2 = generateStaffAssignment("Morning 2", "T1", "2017-01-01T07:30", "2017-01-01T15:30", 5)
      val shift3 = generateStaffAssignment("Evening 1", "T1", "2017-01-01T17:00", "2017-01-01T23:00", 11)
      val shift4 = generateStaffAssignment("Evening 2", "T1", "2017-01-01T17:30", "2017-01-01T23:30", 6)

      val actor2000 = newStaffActor(nowAs("2017-01-01T20:00"))

      actor2000 ! UpdateShifts(Seq(shift1))
      expectMsg(UpdateShiftsAck(Seq(shift1)))
      actor2000 ! PoisonPill

      val actor2005 = newStaffActor(nowAs("2017-01-01T20:05"))

      actor2005 ! UpdateShifts(Seq(shift2))
      expectMsg(UpdateShiftsAck(Seq(shift2)))
      actor2005 ! PoisonPill

      val actor2010 = newStaffActor(nowAs("2017-01-01T20:10"))

      actor2010 ! UpdateShifts(Seq(shift3, shift4))
      expectMsg(UpdateShiftsAck(Seq(shift3, shift4)))
      actor2010 ! PoisonPill

      val actorPit2006 = newStaffPointInTimeActor(nowAs("2017-01-01T20:06"))

      actorPit2006 ! GetState
      val expected = Set(shift1, shift2)

      val result = expectMsgPF(1 second) {
        case ShiftAssignments(sa) => sa.toSet
      }

      result === expected
    }

  }

  def newStaffActor(now: () => SDateLike): ActorRef = system.actorOf(Props(classOf[ShiftsActor], now, expiryDateXDaysFrom(now, 1)))
  def newStaffPointInTimeActor(now: () => SDateLike): ActorRef = system.actorOf(Props(classOf[ShiftsReadActor], now(), expiryDateXDaysFrom(now, 1)))

  def nowAs(date: String): () => SDateLike = () => SDate(date)

  def expiryDateXDaysFrom(now: () => SDateLike, days: Int): () => SDateLike = () => now().addDays(-1 * days)

  def generateStaffAssignment(name: String, terminalName: TerminalName, startTime: String, endTime: String, staff: Int): StaffAssignment = {
    val start = MilliDate(SDate(startTime).millisSinceEpoch)
    val end = MilliDate(SDate(endTime).millisSinceEpoch)
    StaffAssignment(name, terminalName, start, end, staff, None)
  }
}