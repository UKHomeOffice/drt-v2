package uk.gov.homeoffice.drt.service.staffing

import actors.persistent.staffing.{AddStaffMovements, RemoveStaffMovements}
import akka.Done
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import akka.util.Timeout
import drt.shared.{StaffMovement, StaffMovements}
import org.scalatest.concurrent.ScalaFutures.convertScalaFuture
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import uk.gov.homeoffice.drt.actor.commands.Commands.GetState
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.time.{LocalDate, SDate}

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.DurationInt

class MockActor(probe: ActorRef) extends Actor {
  def receive: PartialFunction[Any, Unit] = {
    case c =>
      probe ! c
      sender() ! MockActor.response
  }
}

object MockActor {
  var response: Any = None
}

class StaffMovementsServiceImplSpec extends TestKit(ActorSystem("test")) with AnyWordSpecLike with Matchers {
  implicit val ec: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global
  implicit val timeout: Timeout = new Timeout(1.second)

  def mockActor(probe: ActorRef): ActorRef = system.actorOf(Props(new MockActor(probe)))
  val liveProbe: TestProbe = TestProbe("live")
  val writeProbe: TestProbe = TestProbe("write")
  val pitProbe: TestProbe = TestProbe("pit")

  "A StaffMovementsServiceImpl" should {
    val service = StaffMovementsServiceImpl(mockActor(liveProbe.ref), mockActor(writeProbe.ref), _ => mockActor(pitProbe.ref))
    "return a list of staff movements for a given date" in {
      val movements = Seq(StaffMovement(T1, "some reason", SDate("2024-07-01T05:00").millisSinceEpoch, 1, "abc", None, None))
      MockActor.response = StaffMovements(movements)
      val result = service.movementsForDate(LocalDate(2024, 7, 1), None)
      result.futureValue should ===(movements)
      liveProbe.expectMsg(GetState)
    }

    "return a list of staff movements for a given point in time" in {
      val movements = Seq(StaffMovement(T1, "some reason", SDate("2024-07-01T05:00").millisSinceEpoch, 1, "abc", None, None))
      MockActor.response = StaffMovements(movements)
      val result = service.movementsForDate(LocalDate(2024, 7, 1), Some(SDate("2024-07-01T05:00").millisSinceEpoch))
      result.futureValue should ===(movements)
      pitProbe.expectMsg(GetState)
    }

    "add a list of staff movements" in {
      MockActor.response = Done
      val movements = List(StaffMovement(T1, "some reason", SDate("2024-07-01T05:00").millisSinceEpoch, 1, "abc", None, None))
      val result = service.addMovements(movements)
      result.futureValue should ===(Done)
      writeProbe.expectMsg(AddStaffMovements(movements))
    }

    "remove a staff movement" in {
      MockActor.response = Done
      service.removeMovements("abc")
      writeProbe.expectMsg(RemoveStaffMovements("abc"))
    }
  }
}
