package uk.gov.homeoffice.drt.service.staffing

import actors.persistent.staffing.FixedPointsActor.SetFixedPoints
import akka.Done
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import akka.util.Timeout
import drt.shared.{FixedPointAssignments, StaffAssignment}
import org.scalatest.concurrent.ScalaFutures.convertScalaFuture
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import uk.gov.homeoffice.drt.actor.commands.Commands.GetState
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.time.SDate

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.DurationInt


class FixedPointsServiceImplSpec extends TestKit(ActorSystem("test")) with AnyWordSpecLike with Matchers {
  implicit val ec: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global
  implicit val timeout: Timeout = new Timeout(1.second)

  def mockActor(probe: ActorRef): ActorRef = system.actorOf(Props(new MockActor(probe)))
  val liveProbe: TestProbe = TestProbe("live")
  val writeProbe: TestProbe = TestProbe("write")
  val pitProbe: TestProbe = TestProbe("pit")

  "A FixedPointsServiceImpl" should {
    val service = FixedPointsServiceImpl(mockActor(liveProbe.ref), mockActor(writeProbe.ref), _ => mockActor(pitProbe.ref))
    val assignments = FixedPointAssignments(Seq(StaffAssignment("assignment", T1, SDate("2024-07-01T05:00").millisSinceEpoch, SDate("2024-07-01T12:00").millisSinceEpoch, 1, None)))
    "return a list of fixed points assignments for a given date" in {
      MockActor.response = assignments
      val result = service.fixedPoints(None)
      result.futureValue should ===(assignments)
      liveProbe.expectMsg(GetState)
    }

    "return a list of fixed points assignments for a given point in time" in {
      MockActor.response = assignments
      val result = service.fixedPoints(Some(SDate("2024-07-01T05:00").millisSinceEpoch))
      result.futureValue should ===(assignments)
      pitProbe.expectMsg(GetState)
    }

    "update shifts" in {
      MockActor.response = Done
      service.updateFixedPoints(assignments.assignments)
      writeProbe.expectMsg(SetFixedPoints(assignments.assignments))
    }
  }
}
