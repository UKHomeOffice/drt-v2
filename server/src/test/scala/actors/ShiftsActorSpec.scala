package actors

import actors.persistent.staffing.ShiftsActor.UpdateShifts
import actors.persistent.staffing.{ShiftsActor, ShiftsReadActor}
import akka.actor.{ActorRef, PoisonPill, Props}
import akka.testkit.ImplicitSender
import drt.shared._
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.actor.commands.Commands.GetState
import uk.gov.homeoffice.drt.ports.Terminals.{T1, Terminal}
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

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

  def assertExpectedResponse(expectedShifts: ShiftAssignments): Unit = {
    val response = expectMsgType[ShiftAssignments]
    val sortedResponse = response.assignments.sortBy(_.start)
    val sortedExpectedShifts = expectedShifts.assignments.sortBy(_.start)
    assert(sortedResponse == sortedExpectedShifts)
  }

  "Shifts actor" should {
    "remember a shift staff assignment added before a shutdown" in {
      val startTime = SDate(s"2017-01-01T07:00").millisSinceEpoch
      val endTime = SDate(s"2017-01-01T12:00").millisSinceEpoch
      val shifts = ShiftAssignments(Seq(StaffAssignment("Morning", T1, startTime, endTime, 10, None)))

      val expectedShifts = ShiftAssignments(Seq(
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T07:00:00Z").millisSinceEpoch, SDate(s"2017-01-01T07:14:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T07:15:00Z").millisSinceEpoch, SDate(s"2017-01-01T07:29:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T07:30:00Z").millisSinceEpoch, SDate(s"2017-01-01T07:44:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T07:45:00Z").millisSinceEpoch, SDate(s"2017-01-01T07:59:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T08:00:00Z").millisSinceEpoch, SDate(s"2017-01-01T08:14:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T08:15:00Z").millisSinceEpoch, SDate(s"2017-01-01T08:29:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T08:30:00Z").millisSinceEpoch, SDate(s"2017-01-01T08:44:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T08:45:00Z").millisSinceEpoch, SDate(s"2017-01-01T08:59:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T09:00:00Z").millisSinceEpoch, SDate(s"2017-01-01T09:14:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T09:15:00Z").millisSinceEpoch, SDate(s"2017-01-01T09:29:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T09:30:00Z").millisSinceEpoch, SDate(s"2017-01-01T09:44:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T09:45:00Z").millisSinceEpoch, SDate(s"2017-01-01T09:59:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T10:00:00Z").millisSinceEpoch, SDate(s"2017-01-01T10:14:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T10:15:00Z").millisSinceEpoch, SDate(s"2017-01-01T10:29:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T10:30:00Z").millisSinceEpoch, SDate(s"2017-01-01T10:44:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T10:45:00Z").millisSinceEpoch, SDate(s"2017-01-01T10:59:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T11:00:00Z").millisSinceEpoch, SDate(s"2017-01-01T11:14:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T11:15:00Z").millisSinceEpoch, SDate(s"2017-01-01T11:29:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T11:30:00Z").millisSinceEpoch, SDate(s"2017-01-01T11:44:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T11:45:00Z").millisSinceEpoch, SDate(s"2017-01-01T11:59:00Z").millisSinceEpoch, 10, None),
      ))
      val now: () => SDateLike = () => SDate("2017-01-01T23:59")
      val expireAfterOneDay: () => SDateLike = () => now().addDays(-1)

      val actor = system.actorOf(Props(new ShiftsActor(now, expireAfterOneDay, 10)), "shiftsActor")
      actor ! UpdateShifts(shifts.assignments)
      val response = expectMsgType[ShiftAssignments]
      val sortedResponse = response.assignments.sortBy(_.start)
      val sortedExpectedShifts = expectedShifts.assignments.sortBy(_.start)
      assert(sortedResponse == sortedExpectedShifts)
      actor ! PoisonPill

      val newActor = system.actorOf(Props(new ShiftsActor(now, expireAfterOneDay, 10)), "shiftsActor2")

      newActor ! GetState
      val newResponse = expectMsgType[ShiftAssignments]
      val newSortedResponse = newResponse.assignments.sortBy(_.start)
      val newSortedExpectedShifts = expectedShifts.assignments.sortBy(_.start)
      assert(newSortedResponse == newSortedExpectedShifts)

      true
    }

    "update the shift and check update shift number is applied" in {
      val startTime = SDate(s"2017-01-01T07:00").millisSinceEpoch
      val endTime = SDate(s"2017-01-01T12:00").millisSinceEpoch

      val newStartTime = SDate(s"2017-01-01T9:00").millisSinceEpoch
      val newEndTime = SDate(s"2017-01-01T11:00").millisSinceEpoch

      val shifts = ShiftAssignments(Seq(StaffAssignment("Morning", T1, startTime, endTime, 10, None)))

      val newShift = ShiftAssignments(Seq(StaffAssignment("Morning-late", T1, newStartTime, newEndTime, 5, None)))

      val now: () => SDateLike = () => SDate("2017-01-01T23:59")
      val expireAfterOneDay: () => SDateLike = () => now().addDays(-1)

      val actor = system.actorOf(Props(new ShiftsActor(now, expireAfterOneDay, 10)), "shiftsActor")

      val expectedShifts = ShiftAssignments(Seq(
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T07:00:00Z").millisSinceEpoch, SDate(s"2017-01-01T07:14:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T07:15:00Z").millisSinceEpoch, SDate(s"2017-01-01T07:29:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T07:30:00Z").millisSinceEpoch, SDate(s"2017-01-01T07:44:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T07:45:00Z").millisSinceEpoch, SDate(s"2017-01-01T07:59:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T08:00:00Z").millisSinceEpoch, SDate(s"2017-01-01T08:14:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T08:15:00Z").millisSinceEpoch, SDate(s"2017-01-01T08:29:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T08:30:00Z").millisSinceEpoch, SDate(s"2017-01-01T08:44:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T08:45:00Z").millisSinceEpoch, SDate(s"2017-01-01T08:59:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T09:00:00Z").millisSinceEpoch, SDate(s"2017-01-01T09:14:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T09:15:00Z").millisSinceEpoch, SDate(s"2017-01-01T09:29:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T09:30:00Z").millisSinceEpoch, SDate(s"2017-01-01T09:44:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T09:45:00Z").millisSinceEpoch, SDate(s"2017-01-01T09:59:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T10:00:00Z").millisSinceEpoch, SDate(s"2017-01-01T10:14:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T10:15:00Z").millisSinceEpoch, SDate(s"2017-01-01T10:29:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T10:30:00Z").millisSinceEpoch, SDate(s"2017-01-01T10:44:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T10:45:00Z").millisSinceEpoch, SDate(s"2017-01-01T10:59:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T11:00:00Z").millisSinceEpoch, SDate(s"2017-01-01T11:14:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T11:15:00Z").millisSinceEpoch, SDate(s"2017-01-01T11:29:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T11:30:00Z").millisSinceEpoch, SDate(s"2017-01-01T11:44:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T11:45:00Z").millisSinceEpoch, SDate(s"2017-01-01T11:59:00Z").millisSinceEpoch, 10, None),
      ))

      val expectedNewShifts = ShiftAssignments(Seq(
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T07:00:00Z").millisSinceEpoch, SDate(s"2017-01-01T07:14:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T07:15:00Z").millisSinceEpoch, SDate(s"2017-01-01T07:29:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T07:30:00Z").millisSinceEpoch, SDate(s"2017-01-01T07:44:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T07:45:00Z").millisSinceEpoch, SDate(s"2017-01-01T07:59:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T08:00:00Z").millisSinceEpoch, SDate(s"2017-01-01T08:14:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T08:15:00Z").millisSinceEpoch, SDate(s"2017-01-01T08:29:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T08:30:00Z").millisSinceEpoch, SDate(s"2017-01-01T08:44:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T08:45:00Z").millisSinceEpoch, SDate(s"2017-01-01T08:59:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T09:00:00Z").millisSinceEpoch, SDate(s"2017-01-01T09:14:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T09:15:00Z").millisSinceEpoch, SDate(s"2017-01-01T09:29:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T09:30:00Z").millisSinceEpoch, SDate(s"2017-01-01T09:44:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T09:45:00Z").millisSinceEpoch, SDate(s"2017-01-01T09:59:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T10:00:00Z").millisSinceEpoch, SDate(s"2017-01-01T10:14:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T10:15:00Z").millisSinceEpoch, SDate(s"2017-01-01T10:29:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T10:30:00Z").millisSinceEpoch, SDate(s"2017-01-01T10:44:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T10:45:00Z").millisSinceEpoch, SDate(s"2017-01-01T10:59:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T11:00:00Z").millisSinceEpoch, SDate(s"2017-01-01T11:14:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T11:15:00Z").millisSinceEpoch, SDate(s"2017-01-01T11:29:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T11:30:00Z").millisSinceEpoch, SDate(s"2017-01-01T11:44:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T11:45:00Z").millisSinceEpoch, SDate(s"2017-01-01T11:59:00Z").millisSinceEpoch, 10, None),
      ))

      actor ! UpdateShifts(shifts.assignments)
      assertExpectedResponse(expectedShifts)

      actor ! PoisonPill
      val newActor = system.actorOf(Props(new ShiftsActor(now, expireAfterOneDay, 10)), "shiftsActor2")

      newActor ! GetState
      assertExpectedResponse(expectedShifts)

      newActor ! UpdateShifts(newShift.assignments)

      assertExpectedResponse(expectedNewShifts)
      true
    }

    "snapshots are correctly persisted and replayed" in {
      val startTime = SDate(s"2017-01-01T07:00").millisSinceEpoch
      val endTime = SDate(s"2017-01-01T12:00").millisSinceEpoch
      val shifts = ShiftAssignments(Seq(StaffAssignment("Morning", T1, startTime, endTime, 10, None)))

      val now: () => SDateLike = () => SDate("2017-01-01T23:59")
      val expireAfterOneDay: () => SDateLike = () => now().addDays(-1)

      val actor = system.actorOf(Props(new ShiftsActor(now, expireAfterOneDay, 1)), "shiftsActor")

      val expectedShifts = ShiftAssignments(Seq(
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T07:00:00Z").millisSinceEpoch, SDate(s"2017-01-01T07:14:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T07:15:00Z").millisSinceEpoch, SDate(s"2017-01-01T07:29:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T07:30:00Z").millisSinceEpoch, SDate(s"2017-01-01T07:44:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T07:45:00Z").millisSinceEpoch, SDate(s"2017-01-01T07:59:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T08:00:00Z").millisSinceEpoch, SDate(s"2017-01-01T08:14:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T08:15:00Z").millisSinceEpoch, SDate(s"2017-01-01T08:29:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T08:30:00Z").millisSinceEpoch, SDate(s"2017-01-01T08:44:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T08:45:00Z").millisSinceEpoch, SDate(s"2017-01-01T08:59:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T09:00:00Z").millisSinceEpoch, SDate(s"2017-01-01T09:14:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T09:15:00Z").millisSinceEpoch, SDate(s"2017-01-01T09:29:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T09:30:00Z").millisSinceEpoch, SDate(s"2017-01-01T09:44:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T09:45:00Z").millisSinceEpoch, SDate(s"2017-01-01T09:59:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T10:00:00Z").millisSinceEpoch, SDate(s"2017-01-01T10:14:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T10:15:00Z").millisSinceEpoch, SDate(s"2017-01-01T10:29:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T10:30:00Z").millisSinceEpoch, SDate(s"2017-01-01T10:44:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T10:45:00Z").millisSinceEpoch, SDate(s"2017-01-01T10:59:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T11:00:00Z").millisSinceEpoch, SDate(s"2017-01-01T11:14:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T11:15:00Z").millisSinceEpoch, SDate(s"2017-01-01T11:29:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T11:30:00Z").millisSinceEpoch, SDate(s"2017-01-01T11:44:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning", T1, SDate(s"2017-01-01T11:45:00Z").millisSinceEpoch, SDate(s"2017-01-01T11:59:00Z").millisSinceEpoch, 10, None),
      ))

      actor ! UpdateShifts(shifts.assignments)
      assertExpectedResponse(expectedShifts)
      actor ! PoisonPill

      val newActor = system.actorOf(Props(new ShiftsActor(now, expireAfterOneDay, 1)), "shiftsActor2")
      newActor ! GetState
      assertExpectedResponse(expectedShifts)
      true
    }

    "correctly remember an update to a shift after a restart" in {
      val shift1 = generateStaffAssignment("Morning 1", T1, "2017-01-01T07:00", "2017-01-01T9:00", 10)
      val shift2 = generateStaffAssignment("Morning 2", T1, "2017-01-01T09:00", "2017-01-01T12:00", 10)

      val now: () => SDateLike = () => SDate("2017-01-01T23:59")
      val expireAfterOneDay: () => SDateLike = () => now().addDays(-1)

      val actor = system.actorOf(Props(new ShiftsActor(now, expireAfterOneDay, 10)), "shiftsActor1")
      actor ! UpdateShifts(Seq(shift1, shift2))

      val expectedShifts = ShiftAssignments(Seq(
        StaffAssignment("Morning 1", T1, SDate(s"2017-01-01T07:00:00Z").millisSinceEpoch, SDate(s"2017-01-01T07:14:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning 1", T1, SDate(s"2017-01-01T07:15:00Z").millisSinceEpoch, SDate(s"2017-01-01T07:29:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning 1", T1, SDate(s"2017-01-01T07:30:00Z").millisSinceEpoch, SDate(s"2017-01-01T07:44:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning 1", T1, SDate(s"2017-01-01T07:45:00Z").millisSinceEpoch, SDate(s"2017-01-01T07:59:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning 1", T1, SDate(s"2017-01-01T08:00:00Z").millisSinceEpoch, SDate(s"2017-01-01T08:14:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning 1", T1, SDate(s"2017-01-01T08:15:00Z").millisSinceEpoch, SDate(s"2017-01-01T08:29:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning 1", T1, SDate(s"2017-01-01T08:30:00Z").millisSinceEpoch, SDate(s"2017-01-01T08:44:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning 1", T1, SDate(s"2017-01-01T08:45:00Z").millisSinceEpoch, SDate(s"2017-01-01T08:59:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning 2", T1, SDate(s"2017-01-01T09:00:00Z").millisSinceEpoch, SDate(s"2017-01-01T09:14:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning 2", T1, SDate(s"2017-01-01T09:15:00Z").millisSinceEpoch, SDate(s"2017-01-01T09:29:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning 2", T1, SDate(s"2017-01-01T09:30:00Z").millisSinceEpoch, SDate(s"2017-01-01T09:44:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning 2", T1, SDate(s"2017-01-01T09:45:00Z").millisSinceEpoch, SDate(s"2017-01-01T09:59:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning 2", T1, SDate(s"2017-01-01T10:00:00Z").millisSinceEpoch, SDate(s"2017-01-01T10:14:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning 2", T1, SDate(s"2017-01-01T10:15:00Z").millisSinceEpoch, SDate(s"2017-01-01T10:29:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning 2", T1, SDate(s"2017-01-01T10:30:00Z").millisSinceEpoch, SDate(s"2017-01-01T10:44:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning 2", T1, SDate(s"2017-01-01T10:45:00Z").millisSinceEpoch, SDate(s"2017-01-01T10:59:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning 2", T1, SDate(s"2017-01-01T11:00:00Z").millisSinceEpoch, SDate(s"2017-01-01T11:14:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning 2", T1, SDate(s"2017-01-01T11:15:00Z").millisSinceEpoch, SDate(s"2017-01-01T11:29:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning 2", T1, SDate(s"2017-01-01T11:30:00Z").millisSinceEpoch, SDate(s"2017-01-01T11:44:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning 2", T1, SDate(s"2017-01-01T11:45:00Z").millisSinceEpoch, SDate(s"2017-01-01T11:59:00Z").millisSinceEpoch, 10, None),
      ))

      assertExpectedResponse(expectedShifts)
      val updatedShifts = Seq(shift1, shift2).map(_.copy(numberOfStaff = 0))
      actor ! UpdateShifts(updatedShifts)

      val updatedExpectedShifts = ShiftAssignments(Seq(
        StaffAssignment("Morning 1", T1, SDate(s"2017-01-01T07:00:00Z").millisSinceEpoch, SDate(s"2017-01-01T07:14:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Morning 1", T1, SDate(s"2017-01-01T07:15:00Z").millisSinceEpoch, SDate(s"2017-01-01T07:29:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Morning 1", T1, SDate(s"2017-01-01T07:30:00Z").millisSinceEpoch, SDate(s"2017-01-01T07:44:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Morning 1", T1, SDate(s"2017-01-01T07:45:00Z").millisSinceEpoch, SDate(s"2017-01-01T07:59:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Morning 1", T1, SDate(s"2017-01-01T08:00:00Z").millisSinceEpoch, SDate(s"2017-01-01T08:14:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Morning 1", T1, SDate(s"2017-01-01T08:15:00Z").millisSinceEpoch, SDate(s"2017-01-01T08:29:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Morning 1", T1, SDate(s"2017-01-01T08:30:00Z").millisSinceEpoch, SDate(s"2017-01-01T08:44:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Morning 1", T1, SDate(s"2017-01-01T08:45:00Z").millisSinceEpoch, SDate(s"2017-01-01T08:59:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Morning 2", T1, SDate(s"2017-01-01T09:00:00Z").millisSinceEpoch, SDate(s"2017-01-01T09:14:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Morning 2", T1, SDate(s"2017-01-01T09:15:00Z").millisSinceEpoch, SDate(s"2017-01-01T09:29:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Morning 2", T1, SDate(s"2017-01-01T09:30:00Z").millisSinceEpoch, SDate(s"2017-01-01T09:44:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Morning 2", T1, SDate(s"2017-01-01T09:45:00Z").millisSinceEpoch, SDate(s"2017-01-01T09:59:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Morning 2", T1, SDate(s"2017-01-01T10:00:00Z").millisSinceEpoch, SDate(s"2017-01-01T10:14:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Morning 2", T1, SDate(s"2017-01-01T10:15:00Z").millisSinceEpoch, SDate(s"2017-01-01T10:29:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Morning 2", T1, SDate(s"2017-01-01T10:30:00Z").millisSinceEpoch, SDate(s"2017-01-01T10:44:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Morning 2", T1, SDate(s"2017-01-01T10:45:00Z").millisSinceEpoch, SDate(s"2017-01-01T10:59:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Morning 2", T1, SDate(s"2017-01-01T11:00:00Z").millisSinceEpoch, SDate(s"2017-01-01T11:14:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Morning 2", T1, SDate(s"2017-01-01T11:15:00Z").millisSinceEpoch, SDate(s"2017-01-01T11:29:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Morning 2", T1, SDate(s"2017-01-01T11:30:00Z").millisSinceEpoch, SDate(s"2017-01-01T11:44:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Morning 2", T1, SDate(s"2017-01-01T11:45:00Z").millisSinceEpoch, SDate(s"2017-01-01T11:59:00Z").millisSinceEpoch, 0, None),
      ))
      assertExpectedResponse(updatedExpectedShifts)
      actor ! PoisonPill

      val newActor = system.actorOf(Props(new ShiftsActor(now, expireAfterOneDay, 10)), "shiftsActor2")

      newActor ! GetState

      assertExpectedResponse(updatedExpectedShifts)

      true
    }

    "remember multiple added shifts and correctly remember movements after a restart" in {
      val shift1 = generateStaffAssignment("Morning 1", T1, "2017-01-01T07:00", "2017-01-01T15:00", 10)
      val shift2 = generateStaffAssignment("Morning 2", T1, "2017-01-01T07:30", "2017-01-01T15:30", 5)
      val shift3 = generateStaffAssignment("Evening 1", T1, "2017-01-01T17:00", "2017-01-01T23:00", 11)
      val shift4 = generateStaffAssignment("Evening 2", T1, "2017-01-01T17:30", "2017-01-01T23:30", 6)

      val expectedShift = Seq(
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T07:00:00Z").millisSinceEpoch, SDate("2017-01-01T07:14:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T07:15:00Z").millisSinceEpoch, SDate("2017-01-01T07:29:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T07:30:00Z").millisSinceEpoch, SDate("2017-01-01T07:44:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T07:45:00Z").millisSinceEpoch, SDate("2017-01-01T07:59:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T08:00:00Z").millisSinceEpoch, SDate("2017-01-01T08:14:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T08:15:00Z").millisSinceEpoch, SDate("2017-01-01T08:29:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T08:30:00Z").millisSinceEpoch, SDate("2017-01-01T08:44:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T08:45:00Z").millisSinceEpoch, SDate("2017-01-01T08:59:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T09:00:00Z").millisSinceEpoch, SDate("2017-01-01T09:14:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T09:15:00Z").millisSinceEpoch, SDate("2017-01-01T09:29:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T09:30:00Z").millisSinceEpoch, SDate("2017-01-01T09:44:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T09:45:00Z").millisSinceEpoch, SDate("2017-01-01T09:59:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T10:00:00Z").millisSinceEpoch, SDate("2017-01-01T10:14:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T10:15:00Z").millisSinceEpoch, SDate("2017-01-01T10:29:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T10:30:00Z").millisSinceEpoch, SDate("2017-01-01T10:44:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T10:45:00Z").millisSinceEpoch, SDate("2017-01-01T10:59:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T11:00:00Z").millisSinceEpoch, SDate("2017-01-01T11:14:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T11:15:00Z").millisSinceEpoch, SDate("2017-01-01T11:29:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T11:30:00Z").millisSinceEpoch, SDate("2017-01-01T11:44:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T11:45:00Z").millisSinceEpoch, SDate("2017-01-01T11:59:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T12:00:00Z").millisSinceEpoch, SDate("2017-01-01T12:14:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T12:15:00Z").millisSinceEpoch, SDate("2017-01-01T12:29:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T12:30:00Z").millisSinceEpoch, SDate("2017-01-01T12:44:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T12:45:00Z").millisSinceEpoch, SDate("2017-01-01T12:59:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T13:00:00Z").millisSinceEpoch, SDate("2017-01-01T13:14:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T13:15:00Z").millisSinceEpoch, SDate("2017-01-01T13:29:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T13:30:00Z").millisSinceEpoch, SDate("2017-01-01T13:44:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T13:45:00Z").millisSinceEpoch, SDate("2017-01-01T13:59:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T14:00:00Z").millisSinceEpoch, SDate("2017-01-01T14:14:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T14:15:00Z").millisSinceEpoch, SDate("2017-01-01T14:29:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T14:30:00Z").millisSinceEpoch, SDate("2017-01-01T14:44:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T14:45:00Z").millisSinceEpoch, SDate("2017-01-01T14:59:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 2", T1, SDate("2017-01-01T15:00:00Z").millisSinceEpoch, SDate("2017-01-01T15:14:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 2", T1, SDate("2017-01-01T15:15:00Z").millisSinceEpoch, SDate("2017-01-01T15:29:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T17:00:00Z").millisSinceEpoch, SDate("2017-01-01T17:14:00Z").millisSinceEpoch, 11, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T17:15:00Z").millisSinceEpoch, SDate("2017-01-01T17:29:00Z").millisSinceEpoch, 11, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T17:30:00Z").millisSinceEpoch, SDate("2017-01-01T17:44:00Z").millisSinceEpoch, 6, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T17:45:00Z").millisSinceEpoch, SDate("2017-01-01T17:59:00Z").millisSinceEpoch, 6, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T18:00:00Z").millisSinceEpoch, SDate("2017-01-01T18:14:00Z").millisSinceEpoch, 6, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T18:15:00Z").millisSinceEpoch, SDate("2017-01-01T18:29:00Z").millisSinceEpoch, 6, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T18:30:00Z").millisSinceEpoch, SDate("2017-01-01T18:44:00Z").millisSinceEpoch, 6, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T18:45:00Z").millisSinceEpoch, SDate("2017-01-01T18:59:00Z").millisSinceEpoch, 6, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T19:00:00Z").millisSinceEpoch, SDate("2017-01-01T19:14:00Z").millisSinceEpoch, 6, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T19:15:00Z").millisSinceEpoch, SDate("2017-01-01T19:29:00Z").millisSinceEpoch, 6, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T19:30:00Z").millisSinceEpoch, SDate("2017-01-01T19:44:00Z").millisSinceEpoch, 6, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T19:45:00Z").millisSinceEpoch, SDate("2017-01-01T19:59:00Z").millisSinceEpoch, 6, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T20:00:00Z").millisSinceEpoch, SDate("2017-01-01T20:14:00Z").millisSinceEpoch, 6, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T20:15:00Z").millisSinceEpoch, SDate("2017-01-01T20:29:00Z").millisSinceEpoch, 6, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T20:30:00Z").millisSinceEpoch, SDate("2017-01-01T20:44:00Z").millisSinceEpoch, 6, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T20:45:00Z").millisSinceEpoch, SDate("2017-01-01T20:59:00Z").millisSinceEpoch, 6, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T21:00:00Z").millisSinceEpoch, SDate("2017-01-01T21:14:00Z").millisSinceEpoch, 6, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T21:15:00Z").millisSinceEpoch, SDate("2017-01-01T21:29:00Z").millisSinceEpoch, 6, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T21:30:00Z").millisSinceEpoch, SDate("2017-01-01T21:44:00Z").millisSinceEpoch, 6, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T21:45:00Z").millisSinceEpoch, SDate("2017-01-01T21:59:00Z").millisSinceEpoch, 6, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T22:00:00Z").millisSinceEpoch, SDate("2017-01-01T22:14:00Z").millisSinceEpoch, 6, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T22:15:00Z").millisSinceEpoch, SDate("2017-01-01T22:29:00Z").millisSinceEpoch, 6, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T22:30:00Z").millisSinceEpoch, SDate("2017-01-01T22:44:00Z").millisSinceEpoch, 6, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T22:45:00Z").millisSinceEpoch, SDate("2017-01-01T22:59:00Z").millisSinceEpoch, 6, None),
        StaffAssignment("Evening 2", T1, SDate("2017-01-01T23:00:00Z").millisSinceEpoch, SDate("2017-01-01T23:14:00Z").millisSinceEpoch, 6, None),
        StaffAssignment("Evening 2", T1, SDate("2017-01-01T23:15:00Z").millisSinceEpoch, SDate("2017-01-01T23:29:00Z").millisSinceEpoch, 6, None))


      val now: () => SDateLike = () => SDate("2017-01-01T23:59")
      val expireAfterOneDay: () => SDateLike = () => now().addDays(-1)

      val actor = system.actorOf(Props(new ShiftsActor(now, expireAfterOneDay, 10)), "shiftsActor1")

      actor ! UpdateShifts(Seq(shift1, shift2, shift3, shift4))
      assertExpectedResponse(ShiftAssignments(expectedShift))

      val updatedShift1 = shift1.copy(numberOfStaff = 0)
      val updatedShift3 = shift3.copy(numberOfStaff = 0)

      val expectedUpdated1And2Shift = Seq(
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T07:00:00Z").millisSinceEpoch, SDate("2017-01-01T07:14:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T07:15:00Z").millisSinceEpoch, SDate("2017-01-01T07:29:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T07:30:00Z").millisSinceEpoch, SDate("2017-01-01T07:44:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T07:45:00Z").millisSinceEpoch, SDate("2017-01-01T07:59:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T08:00:00Z").millisSinceEpoch, SDate("2017-01-01T08:14:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T08:15:00Z").millisSinceEpoch, SDate("2017-01-01T08:29:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T08:30:00Z").millisSinceEpoch, SDate("2017-01-01T08:44:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T08:45:00Z").millisSinceEpoch, SDate("2017-01-01T08:59:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T09:00:00Z").millisSinceEpoch, SDate("2017-01-01T09:14:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T09:15:00Z").millisSinceEpoch, SDate("2017-01-01T09:29:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T09:30:00Z").millisSinceEpoch, SDate("2017-01-01T09:44:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T09:45:00Z").millisSinceEpoch, SDate("2017-01-01T09:59:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T10:00:00Z").millisSinceEpoch, SDate("2017-01-01T10:14:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T10:15:00Z").millisSinceEpoch, SDate("2017-01-01T10:29:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T10:30:00Z").millisSinceEpoch, SDate("2017-01-01T10:44:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T10:45:00Z").millisSinceEpoch, SDate("2017-01-01T10:59:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T11:00:00Z").millisSinceEpoch, SDate("2017-01-01T11:14:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T11:15:00Z").millisSinceEpoch, SDate("2017-01-01T11:29:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T11:30:00Z").millisSinceEpoch, SDate("2017-01-01T11:44:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T11:45:00Z").millisSinceEpoch, SDate("2017-01-01T11:59:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T12:00:00Z").millisSinceEpoch, SDate("2017-01-01T12:14:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T12:15:00Z").millisSinceEpoch, SDate("2017-01-01T12:29:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T12:30:00Z").millisSinceEpoch, SDate("2017-01-01T12:44:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T12:45:00Z").millisSinceEpoch, SDate("2017-01-01T12:59:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T13:00:00Z").millisSinceEpoch, SDate("2017-01-01T13:14:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T13:15:00Z").millisSinceEpoch, SDate("2017-01-01T13:29:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T13:30:00Z").millisSinceEpoch, SDate("2017-01-01T13:44:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T13:45:00Z").millisSinceEpoch, SDate("2017-01-01T13:59:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T14:00:00Z").millisSinceEpoch, SDate("2017-01-01T14:14:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T14:15:00Z").millisSinceEpoch, SDate("2017-01-01T14:29:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T14:30:00Z").millisSinceEpoch, SDate("2017-01-01T14:44:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T14:45:00Z").millisSinceEpoch, SDate("2017-01-01T14:59:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Morning 2", T1, SDate("2017-01-01T15:00:00Z").millisSinceEpoch, SDate("2017-01-01T15:14:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 2", T1, SDate("2017-01-01T15:15:00Z").millisSinceEpoch, SDate("2017-01-01T15:29:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T17:00:00Z").millisSinceEpoch, SDate("2017-01-01T17:14:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T17:15:00Z").millisSinceEpoch, SDate("2017-01-01T17:29:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T17:30:00Z").millisSinceEpoch, SDate("2017-01-01T17:44:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T17:45:00Z").millisSinceEpoch, SDate("2017-01-01T17:59:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T18:00:00Z").millisSinceEpoch, SDate("2017-01-01T18:14:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T18:15:00Z").millisSinceEpoch, SDate("2017-01-01T18:29:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T18:30:00Z").millisSinceEpoch, SDate("2017-01-01T18:44:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T18:45:00Z").millisSinceEpoch, SDate("2017-01-01T18:59:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T19:00:00Z").millisSinceEpoch, SDate("2017-01-01T19:14:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T19:15:00Z").millisSinceEpoch, SDate("2017-01-01T19:29:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T19:30:00Z").millisSinceEpoch, SDate("2017-01-01T19:44:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T19:45:00Z").millisSinceEpoch, SDate("2017-01-01T19:59:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T20:00:00Z").millisSinceEpoch, SDate("2017-01-01T20:14:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T20:15:00Z").millisSinceEpoch, SDate("2017-01-01T20:29:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T20:30:00Z").millisSinceEpoch, SDate("2017-01-01T20:44:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T20:45:00Z").millisSinceEpoch, SDate("2017-01-01T20:59:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T21:00:00Z").millisSinceEpoch, SDate("2017-01-01T21:14:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T21:15:00Z").millisSinceEpoch, SDate("2017-01-01T21:29:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T21:30:00Z").millisSinceEpoch, SDate("2017-01-01T21:44:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T21:45:00Z").millisSinceEpoch, SDate("2017-01-01T21:59:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T22:00:00Z").millisSinceEpoch, SDate("2017-01-01T22:14:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T22:15:00Z").millisSinceEpoch, SDate("2017-01-01T22:29:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T22:30:00Z").millisSinceEpoch, SDate("2017-01-01T22:44:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T22:45:00Z").millisSinceEpoch, SDate("2017-01-01T22:59:00Z").millisSinceEpoch, 0, None),
        StaffAssignment("Evening 2", T1, SDate("2017-01-01T23:00:00Z").millisSinceEpoch, SDate("2017-01-01T23:14:00Z").millisSinceEpoch, 6, None),
        StaffAssignment("Evening 2", T1, SDate("2017-01-01T23:15:00Z").millisSinceEpoch, SDate("2017-01-01T23:29:00Z").millisSinceEpoch, 6, None))

      actor ! UpdateShifts(Seq(updatedShift1, updatedShift3))
      assertExpectedResponse(ShiftAssignments(expectedUpdated1And2Shift))
      actor ! PoisonPill


      val newActor = system.actorOf(Props(new ShiftsActor(now, expireAfterOneDay, 10)), "shiftsActor2")

      newActor ! GetState
      val expected = expectedUpdated1And2Shift.toSet

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

      val expectedShift1 = Seq(
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T07:00:00Z").millisSinceEpoch, SDate("2017-01-01T07:14:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T07:15:00Z").millisSinceEpoch, SDate("2017-01-01T07:29:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T07:30:00Z").millisSinceEpoch, SDate("2017-01-01T07:44:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T07:45:00Z").millisSinceEpoch, SDate("2017-01-01T07:59:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T08:00:00Z").millisSinceEpoch, SDate("2017-01-01T08:14:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T08:15:00Z").millisSinceEpoch, SDate("2017-01-01T08:29:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T08:30:00Z").millisSinceEpoch, SDate("2017-01-01T08:44:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T08:45:00Z").millisSinceEpoch, SDate("2017-01-01T08:59:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T09:00:00Z").millisSinceEpoch, SDate("2017-01-01T09:14:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T09:15:00Z").millisSinceEpoch, SDate("2017-01-01T09:29:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T09:30:00Z").millisSinceEpoch, SDate("2017-01-01T09:44:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T09:45:00Z").millisSinceEpoch, SDate("2017-01-01T09:59:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T10:00:00Z").millisSinceEpoch, SDate("2017-01-01T10:14:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T10:15:00Z").millisSinceEpoch, SDate("2017-01-01T10:29:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T10:30:00Z").millisSinceEpoch, SDate("2017-01-01T10:44:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T10:45:00Z").millisSinceEpoch, SDate("2017-01-01T10:59:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T11:00:00Z").millisSinceEpoch, SDate("2017-01-01T11:14:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T11:15:00Z").millisSinceEpoch, SDate("2017-01-01T11:29:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T11:30:00Z").millisSinceEpoch, SDate("2017-01-01T11:44:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T11:45:00Z").millisSinceEpoch, SDate("2017-01-01T11:59:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T12:00:00Z").millisSinceEpoch, SDate("2017-01-01T12:14:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T12:15:00Z").millisSinceEpoch, SDate("2017-01-01T12:29:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T12:30:00Z").millisSinceEpoch, SDate("2017-01-01T12:44:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T12:45:00Z").millisSinceEpoch, SDate("2017-01-01T12:59:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T13:00:00Z").millisSinceEpoch, SDate("2017-01-01T13:14:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T13:15:00Z").millisSinceEpoch, SDate("2017-01-01T13:29:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T13:30:00Z").millisSinceEpoch, SDate("2017-01-01T13:44:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T13:45:00Z").millisSinceEpoch, SDate("2017-01-01T13:59:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T14:00:00Z").millisSinceEpoch, SDate("2017-01-01T14:14:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T14:15:00Z").millisSinceEpoch, SDate("2017-01-01T14:29:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T14:30:00Z").millisSinceEpoch, SDate("2017-01-01T14:44:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T14:45:00Z").millisSinceEpoch, SDate("2017-01-01T14:59:00Z").millisSinceEpoch, 10, None))

      assertExpectedResponse(ShiftAssignments(expectedShift1))
      actor2000 ! PoisonPill

      val actor2005 = newStaffActor(nowAs("2017-01-01T20:05"))

      actor2005 ! UpdateShifts(Seq(shift2))

      val expectedShift1And2 = Seq(
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T07:00:00Z").millisSinceEpoch, SDate("2017-01-01T07:14:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T07:15:00Z").millisSinceEpoch, SDate("2017-01-01T07:29:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T07:30:00Z").millisSinceEpoch, SDate("2017-01-01T07:44:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T07:45:00Z").millisSinceEpoch, SDate("2017-01-01T07:59:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T08:00:00Z").millisSinceEpoch, SDate("2017-01-01T08:14:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T08:15:00Z").millisSinceEpoch, SDate("2017-01-01T08:29:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T08:30:00Z").millisSinceEpoch, SDate("2017-01-01T08:44:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T08:45:00Z").millisSinceEpoch, SDate("2017-01-01T08:59:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T09:00:00Z").millisSinceEpoch, SDate("2017-01-01T09:14:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T09:15:00Z").millisSinceEpoch, SDate("2017-01-01T09:29:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T09:30:00Z").millisSinceEpoch, SDate("2017-01-01T09:44:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T09:45:00Z").millisSinceEpoch, SDate("2017-01-01T09:59:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T10:00:00Z").millisSinceEpoch, SDate("2017-01-01T10:14:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T10:15:00Z").millisSinceEpoch, SDate("2017-01-01T10:29:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T10:30:00Z").millisSinceEpoch, SDate("2017-01-01T10:44:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T10:45:00Z").millisSinceEpoch, SDate("2017-01-01T10:59:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T11:00:00Z").millisSinceEpoch, SDate("2017-01-01T11:14:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T11:15:00Z").millisSinceEpoch, SDate("2017-01-01T11:29:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T11:30:00Z").millisSinceEpoch, SDate("2017-01-01T11:44:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T11:45:00Z").millisSinceEpoch, SDate("2017-01-01T11:59:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T12:00:00Z").millisSinceEpoch, SDate("2017-01-01T12:14:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T12:15:00Z").millisSinceEpoch, SDate("2017-01-01T12:29:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T12:30:00Z").millisSinceEpoch, SDate("2017-01-01T12:44:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T12:45:00Z").millisSinceEpoch, SDate("2017-01-01T12:59:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T13:00:00Z").millisSinceEpoch, SDate("2017-01-01T13:14:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T13:15:00Z").millisSinceEpoch, SDate("2017-01-01T13:29:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T13:30:00Z").millisSinceEpoch, SDate("2017-01-01T13:44:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T13:45:00Z").millisSinceEpoch, SDate("2017-01-01T13:59:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T14:00:00Z").millisSinceEpoch, SDate("2017-01-01T14:14:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T14:15:00Z").millisSinceEpoch, SDate("2017-01-01T14:29:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T14:30:00Z").millisSinceEpoch, SDate("2017-01-01T14:44:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T14:45:00Z").millisSinceEpoch, SDate("2017-01-01T14:59:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 2", T1, SDate("2017-01-01T15:00:00Z").millisSinceEpoch, SDate("2017-01-01T15:14:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 2", T1, SDate("2017-01-01T15:15:00Z").millisSinceEpoch, SDate("2017-01-01T15:29:00Z").millisSinceEpoch, 5, None))

      assertExpectedResponse(ShiftAssignments(expectedShift1And2))
      actor2005 ! PoisonPill

      val actor2010 = newStaffActor(nowAs("2017-01-01T20:10"))

      actor2010 ! UpdateShifts(Seq(shift3, shift4))

      val expectedShiftWith3And4 = Seq(
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T07:00:00Z").millisSinceEpoch, SDate("2017-01-01T07:14:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T07:15:00Z").millisSinceEpoch, SDate("2017-01-01T07:29:00Z").millisSinceEpoch, 10, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T07:30:00Z").millisSinceEpoch, SDate("2017-01-01T07:44:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T07:45:00Z").millisSinceEpoch, SDate("2017-01-01T07:59:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T08:00:00Z").millisSinceEpoch, SDate("2017-01-01T08:14:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T08:15:00Z").millisSinceEpoch, SDate("2017-01-01T08:29:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T08:30:00Z").millisSinceEpoch, SDate("2017-01-01T08:44:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T08:45:00Z").millisSinceEpoch, SDate("2017-01-01T08:59:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T09:00:00Z").millisSinceEpoch, SDate("2017-01-01T09:14:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T09:15:00Z").millisSinceEpoch, SDate("2017-01-01T09:29:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T09:30:00Z").millisSinceEpoch, SDate("2017-01-01T09:44:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T09:45:00Z").millisSinceEpoch, SDate("2017-01-01T09:59:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T10:00:00Z").millisSinceEpoch, SDate("2017-01-01T10:14:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T10:15:00Z").millisSinceEpoch, SDate("2017-01-01T10:29:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T10:30:00Z").millisSinceEpoch, SDate("2017-01-01T10:44:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T10:45:00Z").millisSinceEpoch, SDate("2017-01-01T10:59:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T11:00:00Z").millisSinceEpoch, SDate("2017-01-01T11:14:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T11:15:00Z").millisSinceEpoch, SDate("2017-01-01T11:29:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T11:30:00Z").millisSinceEpoch, SDate("2017-01-01T11:44:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T11:45:00Z").millisSinceEpoch, SDate("2017-01-01T11:59:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T12:00:00Z").millisSinceEpoch, SDate("2017-01-01T12:14:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T12:15:00Z").millisSinceEpoch, SDate("2017-01-01T12:29:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T12:30:00Z").millisSinceEpoch, SDate("2017-01-01T12:44:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T12:45:00Z").millisSinceEpoch, SDate("2017-01-01T12:59:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T13:00:00Z").millisSinceEpoch, SDate("2017-01-01T13:14:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T13:15:00Z").millisSinceEpoch, SDate("2017-01-01T13:29:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T13:30:00Z").millisSinceEpoch, SDate("2017-01-01T13:44:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T13:45:00Z").millisSinceEpoch, SDate("2017-01-01T13:59:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T14:00:00Z").millisSinceEpoch, SDate("2017-01-01T14:14:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T14:15:00Z").millisSinceEpoch, SDate("2017-01-01T14:29:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T14:30:00Z").millisSinceEpoch, SDate("2017-01-01T14:44:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 1", T1, SDate("2017-01-01T14:45:00Z").millisSinceEpoch, SDate("2017-01-01T14:59:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 2", T1, SDate("2017-01-01T15:00:00Z").millisSinceEpoch, SDate("2017-01-01T15:14:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Morning 2", T1, SDate("2017-01-01T15:15:00Z").millisSinceEpoch, SDate("2017-01-01T15:29:00Z").millisSinceEpoch, 5, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T17:00:00Z").millisSinceEpoch, SDate("2017-01-01T17:14:00Z").millisSinceEpoch, 11, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T17:15:00Z").millisSinceEpoch, SDate("2017-01-01T17:29:00Z").millisSinceEpoch, 11, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T17:30:00Z").millisSinceEpoch, SDate("2017-01-01T17:44:00Z").millisSinceEpoch, 6, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T17:45:00Z").millisSinceEpoch, SDate("2017-01-01T17:59:00Z").millisSinceEpoch, 6, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T18:00:00Z").millisSinceEpoch, SDate("2017-01-01T18:14:00Z").millisSinceEpoch, 6, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T18:15:00Z").millisSinceEpoch, SDate("2017-01-01T18:29:00Z").millisSinceEpoch, 6, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T18:30:00Z").millisSinceEpoch, SDate("2017-01-01T18:44:00Z").millisSinceEpoch, 6, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T18:45:00Z").millisSinceEpoch, SDate("2017-01-01T18:59:00Z").millisSinceEpoch, 6, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T19:00:00Z").millisSinceEpoch, SDate("2017-01-01T19:14:00Z").millisSinceEpoch, 6, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T19:15:00Z").millisSinceEpoch, SDate("2017-01-01T19:29:00Z").millisSinceEpoch, 6, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T19:30:00Z").millisSinceEpoch, SDate("2017-01-01T19:44:00Z").millisSinceEpoch, 6, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T19:45:00Z").millisSinceEpoch, SDate("2017-01-01T19:59:00Z").millisSinceEpoch, 6, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T20:00:00Z").millisSinceEpoch, SDate("2017-01-01T20:14:00Z").millisSinceEpoch, 6, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T20:15:00Z").millisSinceEpoch, SDate("2017-01-01T20:29:00Z").millisSinceEpoch, 6, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T20:30:00Z").millisSinceEpoch, SDate("2017-01-01T20:44:00Z").millisSinceEpoch, 6, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T20:45:00Z").millisSinceEpoch, SDate("2017-01-01T20:59:00Z").millisSinceEpoch, 6, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T21:00:00Z").millisSinceEpoch, SDate("2017-01-01T21:14:00Z").millisSinceEpoch, 6, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T21:15:00Z").millisSinceEpoch, SDate("2017-01-01T21:29:00Z").millisSinceEpoch, 6, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T21:30:00Z").millisSinceEpoch, SDate("2017-01-01T21:44:00Z").millisSinceEpoch, 6, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T21:45:00Z").millisSinceEpoch, SDate("2017-01-01T21:59:00Z").millisSinceEpoch, 6, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T22:00:00Z").millisSinceEpoch, SDate("2017-01-01T22:14:00Z").millisSinceEpoch, 6, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T22:15:00Z").millisSinceEpoch, SDate("2017-01-01T22:29:00Z").millisSinceEpoch, 6, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T22:30:00Z").millisSinceEpoch, SDate("2017-01-01T22:44:00Z").millisSinceEpoch, 6, None),
        StaffAssignment("Evening 1", T1, SDate("2017-01-01T22:45:00Z").millisSinceEpoch, SDate("2017-01-01T22:59:00Z").millisSinceEpoch, 6, None),
        StaffAssignment("Evening 2", T1, SDate("2017-01-01T23:00:00Z").millisSinceEpoch, SDate("2017-01-01T23:14:00Z").millisSinceEpoch, 6, None),
        StaffAssignment("Evening 2", T1, SDate("2017-01-01T23:15:00Z").millisSinceEpoch, SDate("2017-01-01T23:29:00Z").millisSinceEpoch, 6, None))

      assertExpectedResponse(ShiftAssignments(expectedShiftWith3And4))
      actor2010 ! PoisonPill

      val actorPit2006 = newStaffPointInTimeActor(nowAs("2017-01-01T20:06"))

      actorPit2006 ! GetState

      val result = expectMsgPF(1.second) {
        case ShiftAssignments(sa) => sa.toSet
      }

      result === expectedShift1And2.toSet
    }

  }


  def newStaffActor(now: () => SDateLike): ActorRef = system.actorOf(Props(new ShiftsActor(now, expiryDateXDaysFrom(now, 1), 10)))

  def newStaffPointInTimeActor(now: () => SDateLike): ActorRef = system.actorOf(Props(new ShiftsReadActor(now(), expiryDateXDaysFrom(now, 1))))

  def nowAs(date: String): () => SDateLike = () => SDate(date)

  def expiryDateXDaysFrom(now: () => SDateLike, days: Int): () => SDateLike = () => now().addDays(-1 * days)
}
