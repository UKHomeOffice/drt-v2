package actors

import actors.persistent.staffing.ShiftsActor.{UpdateShifts, updateMinimumStaff}
import actors.persistent.staffing.{ShiftsActor, ShiftsReadActor}
import akka.actor.{ActorRef, PoisonPill, Props}
import akka.pattern.StatusReply
import akka.testkit.ImplicitSender
import drt.shared._
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.actor.commands.Commands.GetState
import uk.gov.homeoffice.drt.ports.Terminals.{T1, Terminal}
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, SDateLike, TimeZoneHelper}

import scala.concurrent.duration._


object StaffAssignmentGenerator {
  def generateStaffAssignment(name: String, terminal: Terminal, startTime: String, endTime: String, staff: Int): StaffAssignment = {
    val start = SDate(startTime).millisSinceEpoch
    val end = SDate(endTime).millisSinceEpoch
    StaffAssignment(name, terminal, start, end, staff, None)
  }
}

class ShiftsActorSpec extends CrunchTestLike with ImplicitSender {
  sequential
  isolated

  import StaffAssignmentGenerator._

  "Shifts actor" should {
    "remember a shift staff assignment added before a shutdown" in {
      val startTime = SDate(s"2017-01-01T07:00").millisSinceEpoch
      val endTime = SDate(s"2017-01-01T15:00").millisSinceEpoch
      val shifts = ShiftAssignments(Seq(StaffAssignment("Morning", T1, startTime, endTime, 10, None)))

      val now: () => SDateLike = () => SDate("2017-01-01T23:59")
      val expireAfterOneDay: () => SDateLike = () => now().addDays(-1)

      val actor = system.actorOf(Props(new ShiftsActor(now, expireAfterOneDay, 10)), "shiftsActor")

      actor ! UpdateShifts(shifts.assignments)
      expectMsg(StatusReply.Ack)
      actor ! PoisonPill

      val newActor = system.actorOf(Props(new ShiftsActor(now, expireAfterOneDay, 10)), "shiftsActor2")

      newActor ! GetState

      expectMsg(shifts)

      true
    }

    "snapshots are correctly persisted and replayed" in {
      val startTime = SDate(s"2017-01-01T07:00").millisSinceEpoch
      val endTime = SDate(s"2017-01-01T15:00").millisSinceEpoch
      val shifts = ShiftAssignments(Seq(StaffAssignment("Morning", T1, startTime, endTime, 10, None)))

      val now: () => SDateLike = () => SDate("2017-01-01T23:59")
      val expireAfterOneDay: () => SDateLike = () => now().addDays(-1)

      val actor = system.actorOf(Props(new ShiftsActor(now, expireAfterOneDay, 1)), "shiftsActor")

      actor ! UpdateShifts(shifts.assignments)
      expectMsg(StatusReply.Ack)
      actor ! PoisonPill

      val newActor = system.actorOf(Props(new ShiftsActor(now, expireAfterOneDay, 1)), "shiftsActor2")
      newActor ! GetState

      expectMsg(shifts)

      true
    }

    "correctly remember an update to a shift after a restart" in {
      val shift1 = generateStaffAssignment("Morning 1", T1, "2017-01-01T07:00", "2017-01-01T15:00", 10)
      val shift2 = generateStaffAssignment("Morning 2", T1, "2017-01-01T07:30", "2017-01-01T15:30", 10)

      val now: () => SDateLike = () => SDate("2017-01-01T23:59")
      val expireAfterOneDay: () => SDateLike = () => now().addDays(-1)

      val actor = system.actorOf(Props(new ShiftsActor(now, expireAfterOneDay, 10)), "shiftsActor1")

      actor ! UpdateShifts(Seq(shift1, shift2))
      expectMsg(StatusReply.Ack)

      val updatedShifts = Seq(shift1, shift2).map(_.copy(numberOfStaff = 0))
      actor ! UpdateShifts(updatedShifts)
      expectMsg(StatusReply.Ack)
      actor ! PoisonPill

      val newActor = system.actorOf(Props(new ShiftsActor(now, expireAfterOneDay, 10)), "shiftsActor2")

      newActor ! GetState
      val expected = ShiftAssignments(updatedShifts)

      expectMsg(expected)

      true
    }

    "remember multiple added shifts and correctly remember movements after a restart" in {
      val shift1 = generateStaffAssignment("Morning 1", T1, "2017-01-01T07:00", "2017-01-01T15:00", 10)
      val shift2 = generateStaffAssignment("Morning 2", T1, "2017-01-01T07:30", "2017-01-01T15:30", 5)
      val shift3 = generateStaffAssignment("Evening 1", T1, "2017-01-01T17:00", "2017-01-01T23:00", 11)
      val shift4 = generateStaffAssignment("Evening 2", T1, "2017-01-01T17:30", "2017-01-01T23:30", 6)

      val now: () => SDateLike = () => SDate("2017-01-01T23:59")
      val expireAfterOneDay: () => SDateLike = () => now().addDays(-1)

      val actor = system.actorOf(Props(new ShiftsActor(now, expireAfterOneDay, 10)), "shiftsActor1")

      actor ! UpdateShifts(Seq(shift1, shift2, shift3, shift4))
      expectMsg(StatusReply.Ack)

      val updatedShift1 = shift1.copy(numberOfStaff = 0)
      val updatedShift3 = shift3.copy(numberOfStaff = 0)
      actor ! UpdateShifts(Seq(updatedShift1, updatedShift3))
      expectMsg(StatusReply.Ack)
      actor ! PoisonPill

      val newActor = system.actorOf(Props(new ShiftsActor(now, expireAfterOneDay, 10)), "shiftsActor2")

      newActor ! GetState
      val expected = Set(updatedShift1, shift2, updatedShift3, shift4)

      val result = expectMsgPF(1.second) {
        case ShiftAssignments(sa) => sa.toSet
      }

      result === expected
    }

    "restore shifts to a point in time view" in {
      val shift1 = generateStaffAssignment("Morning 1", T1, "2017-01-01T07:00", "2017-01-01T15:00", 10)
      val shift2 = generateStaffAssignment("Morning 2", T1, "2017-01-01T07:30", "2017-01-01T15:30", 5)
      val shift3 = generateStaffAssignment("Evening 1", T1, "2017-01-01T17:00", "2017-01-01T23:00", 11)
      val shift4 = generateStaffAssignment("Evening 2", T1, "2017-01-01T17:30", "2017-01-01T23:30", 6)

      val actor2000 = newStaffActor(nowAs("2017-01-01T20:00"))

      actor2000 ! UpdateShifts(Seq(shift1))
      expectMsg(StatusReply.Ack)
      actor2000 ! PoisonPill

      val actor2005 = newStaffActor(nowAs("2017-01-01T20:05"))

      actor2005 ! UpdateShifts(Seq(shift2))
      expectMsg(StatusReply.Ack)
      actor2005 ! PoisonPill

      val actor2010 = newStaffActor(nowAs("2017-01-01T20:10"))

      actor2010 ! UpdateShifts(Seq(shift3, shift4))
      expectMsg(StatusReply.Ack)
      actor2010 ! PoisonPill

      val actorPit2006 = newStaffPointInTimeActor(nowAs("2017-01-01T20:06"))

      actorPit2006 ! GetState
      val expected = Set(shift1, shift2)

      val result = expectMsgPF(1.second) {
        case ShiftAssignments(sa) => sa.toSet
      }

      result === expected
    }

  }

  "updateMinimum" should {
    "update the existing level if the new minimum is above the existing level" in {
      ShiftsActor.applyMinimumStaff(10, Option(15), 9) === 10
      ShiftsActor.applyMinimumStaff(10, Option(15), 14) === 10
      ShiftsActor.applyMinimumStaff(10, Option(15), 15) === 10
      ShiftsActor.applyMinimumStaff(10, Option(15), 16) === 16

      ShiftsActor.applyMinimumStaff(10, Option(5), 4) === 10
      ShiftsActor.applyMinimumStaff(10, Option(5), 5) === 10
      ShiftsActor.applyMinimumStaff(10, Option(5), 6) === 10
      ShiftsActor.applyMinimumStaff(10, Option(5), 11) === 11

      ShiftsActor.applyMinimumStaff(10, None, 9) === 10
      ShiftsActor.applyMinimumStaff(10, None, 10) === 10
      ShiftsActor.applyMinimumStaff(10, None, 11) === 11
    }
  }

  "updateMinimumStaff" should {
    "create shift assignments with the minimum level where there are none existing" in {
      val shifts = updateMinimumStaff(T1, LocalDate(2024, 8, 18), LocalDate(2024, 8, 18), 10, None, ShiftAssignments.empty)

      shifts.assignments.length === 96
      shifts.assignments.forall(_.numberOfStaff === 10) === true
    }
    "update existing shift assignments with the new minimum level where they equal the old minimum" in {
      val existingShifts = ShiftAssignments((0 until 96).map { i =>
        val startTime = SDate("2024-08-18T00:00", TimeZoneHelper.europeLondonTimeZone).addMinutes(i * 15)
        val endTime = startTime.addMinutes(14)
        StaffAssignment(s"Shift $i", T1, startTime.millisSinceEpoch, endTime.millisSinceEpoch, 5, None)
      })
      val shifts = updateMinimumStaff(T1, LocalDate(2024, 8, 18), LocalDate(2024, 8, 18), 10, Option(5), existingShifts)

      shifts.assignments.length === 96
      shifts.assignments.forall(_.numberOfStaff === 10) === true
    }
  }

  def newStaffActor(now: () => SDateLike): ActorRef = system.actorOf(Props(new ShiftsActor(now, expiryDateXDaysFrom(now, 1), 10)))
  def newStaffPointInTimeActor(now: () => SDateLike): ActorRef = system.actorOf(Props(new ShiftsReadActor(now(), expiryDateXDaysFrom(now, 1))))

  def nowAs(date: String): () => SDateLike = () => SDate(date)

  def expiryDateXDaysFrom(now: () => SDateLike, days: Int): () => SDateLike = () => now().addDays(-1 * days)
}
