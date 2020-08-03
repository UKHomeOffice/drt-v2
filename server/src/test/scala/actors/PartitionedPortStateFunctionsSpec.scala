package actors

import actors.PartitionedPortStateActor.{FlightsRequester, GetStateForDateRange, QueueMinutesRequester, StaffMinutesRequester}
import akka.actor.{Actor, ActorRef, Props}
import akka.testkit.{ImplicitSender, TestProbe}
import drt.shared.CrunchApi.{CrunchMinute, MinutesContainer, StaffMinute}
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.{TM, TQM}
import services.crunch.CrunchTestLike

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

class MockRequestHandler(response: Any) extends Actor {
  override def receive: Receive = {
    case _ => sender() ! response
  }
}

class PartitionedPortStateFunctionsSpec extends CrunchTestLike with ImplicitSender {
  val probe: TestProbe = TestProbe("PartitionedPortStateFunctions")
  val emptyQueueMinutes: MinutesContainer[CrunchMinute, TQM] = MinutesContainer.empty[CrunchMinute, TQM]
  val emptyStaffMinutes: MinutesContainer[StaffMinute, TM] = MinutesContainer.empty[StaffMinute, TM]
  val emptyFlights: FlightsWithSplits = FlightsWithSplits.empty

  "Given a queue minutes requester with a mock queues actor" >> {
    "When the mock is set to return an empty CrunchMinute MinutesContainer" >> {
      val requestQueueMinutes: QueueMinutesRequester = PartitionedPortStateActor.requestQueueMinutesFn(mockResponseActor(emptyQueueMinutes))
      "Then I should see the empty container in the future" >> {
        val result = Await.result(requestQueueMinutes(GetStateForDateRange(0L, 0L)), 1 second)
        result === emptyQueueMinutes
      }
    }
    "When the mock is set to return an a non-MinutesContainer" >> {
      val requestQueueMinutes: QueueMinutesRequester = PartitionedPortStateActor.requestQueueMinutesFn(mockResponseActor(List()))
      "Then I should see an exception in the future" >> {
        val result = Try(Await.result(requestQueueMinutes(GetStateForDateRange(0L, 0L)), 1 second))
        result.isFailure === true
      }
    }
  }

  "Given a staff minutes requester with a mock staff actor" >> {
    "When the mock is set to return an empty StaffMinute MinutesContainer" >> {
      val requestQueueMinutes: StaffMinutesRequester = PartitionedPortStateActor.requestStaffMinutesFn(mockResponseActor(emptyStaffMinutes))
      "Then I should see the empty container in the future" >> {
        val result = Await.result(requestQueueMinutes(GetStateForDateRange(0L, 0L)), 1 second)
        result === emptyStaffMinutes
      }
    }
    "When the mock is set to return an a non-MinutesContainer" >> {
      val requestQueueMinutes: StaffMinutesRequester = PartitionedPortStateActor.requestStaffMinutesFn(mockResponseActor(List()))
      "Then I should see an exception in the future" >> {
        val result = Try(Await.result(requestQueueMinutes(GetStateForDateRange(0L, 0L)), 1 second))
        result.isFailure === true
      }
    }
  }

  "Given a flights requester with a mock flights actor" >> {
    "When the mock is set to return an empty FlightsWithSplits" >> {
      val requestQueueMinutes: FlightsRequester = PartitionedPortStateActor.requestFlightsFn(mockResponseActor(emptyFlights))
      "Then I should see the empty container in the future" >> {
        val result = Await.result(requestQueueMinutes(GetStateForDateRange(0L, 0L)), 1 second)
        result === emptyFlights
      }
    }
    "When the mock is set to return an a non-FlightsWithSplits" >> {
      val requestQueueMinutes: FlightsRequester = PartitionedPortStateActor.requestFlightsFn(mockResponseActor(List()))
      "Then I should see an exception in the future" >> {
        val result = Try(Await.result(requestQueueMinutes(GetStateForDateRange(0L, 0L)), 1 second))
        result.isFailure === true
      }
    }
  }

  private def mockResponseActor(response: Any): ActorRef = {
    system.actorOf(Props(new MockRequestHandler(response)))
  }
}
