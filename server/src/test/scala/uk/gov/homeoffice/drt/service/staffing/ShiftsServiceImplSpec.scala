package uk.gov.homeoffice.drt.service.staffing

import actors.PartitionedPortStateActor.GetStateForDateRange
import actors.persistent.staffing.ShiftsActor.UpdateShifts
import akka.Done
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import akka.util.Timeout
import drt.shared.{MonthOfShifts, ShiftAssignments, StaffAssignment}
import org.scalatest.concurrent.ScalaFutures.convertScalaFuture
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import uk.gov.homeoffice.drt.actor.commands.Commands.GetState
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.time.{LocalDate, SDate}

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.DurationInt


class ShiftsServiceImplSpec extends TestKit(ActorSystem("test")) with AnyWordSpecLike with Matchers {
  implicit val ec: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global
  implicit val timeout: Timeout = new Timeout(1.second)

  def mockActor(probe: ActorRef): ActorRef = system.actorOf(Props(new MockActor(probe)))
  val liveProbe: TestProbe = TestProbe("live")
  val writeProbe: TestProbe = TestProbe("write")
  val pitProbe: TestProbe = TestProbe("pit")

  "A ShiftsServiceImpl" should {
    val service = ShiftsServiceImpl(mockActor(liveProbe.ref), mockActor(writeProbe.ref), _ => mockActor(pitProbe.ref))
    val assignments = ShiftAssignments(Seq(StaffAssignment("assignment", T1, SDate("2024-07-01T05:00").millisSinceEpoch, SDate("2024-07-01T12:00").millisSinceEpoch, 1, None)))
    "return a list of staff assignments for a given date" in {
      MockActor.response = assignments
      val result = service.shiftsForDate(LocalDate(2024, 7, 1), None)
      result.futureValue should ===(assignments)
      liveProbe.expectMsgClass(classOf[GetStateForDateRange])
    }

    "return a list of staff assignments for a given point in time" in {
      MockActor.response = assignments
      val result = service.shiftsForDate(LocalDate(2024, 7, 1), Some(SDate("2024-07-01T05:00").millisSinceEpoch))
      result.futureValue should ===(assignments)
      pitProbe.expectMsgClass(classOf[GetStateForDateRange])
    }

    "return a list of staff assignments for a month" in {
      MockActor.response = assignments
      val result = service.shiftsForMonth(SDate("2024-07-01T01:00").millisSinceEpoch)
      result.futureValue.getClass should ===(classOf[MonthOfShifts])
      liveProbe.expectMsg(GetState)
    }

    "update shifts" in {
      MockActor.response = Done
      service.updateShifts(assignments.assignments)
      writeProbe.expectMsg(UpdateShifts(assignments.assignments))
    }
  }
}
