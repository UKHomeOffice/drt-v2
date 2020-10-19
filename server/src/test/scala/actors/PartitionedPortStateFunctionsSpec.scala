package actors

import actors.PartitionedPortStateActor._
import actors.queues.FlightsRouterActor
import akka.actor.{Actor, ActorRef, Props}
import akka.stream.scaladsl.Source
import akka.testkit.{ImplicitSender, TestProbe}
import drt.shared.CrunchApi.{CrunchMinute, MinutesContainer, PortStateUpdates, StaffMinute}
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.Queues.EeaDesk
import drt.shared.Terminals.T1
import drt.shared.{ApiFlightWithSplits, PortState, TM, TQM}
import services.crunch.CrunchTestLike

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.Try

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
      val requestQueueMinutes: FlightsRequester = PartitionedPortStateActor.requestFlightsFn(mockResponseActor(Source(List(emptyFlights))))
      "Then I should see the empty container in the future" >> {
        val eventualResult: Future[FlightsWithSplits] = FlightsRouterActor.runAndCombine(requestQueueMinutes(GetStateForDateRange(0L, 0L)))
        val result = Await.result(eventualResult, 1 second)
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

  "Given a replyWithUpdates function with mock requesters returning no updates" >> {
    "When I ask to reply with updates" >> {
      val emptyFlights = FlightsWithSplits.empty
      val emptyQueues = MinutesContainer.empty[CrunchMinute, TQM]
      val emptyStaff = MinutesContainer.empty[StaffMinute, TM]
      val replyWithUpdates = makeReplyWithUpdates(emptyFlights, emptyQueues, emptyStaff)

      "Then I should see a None being sent" >> {
        replyWithUpdates(0L, 0L, 0L, self)
        expectMsg(None)
        success
      }
    }
  }

  "Given a replyWithUpdates function with mock requesters returning one updated flight" >> {
    "When I ask to reply with updates" >> {
      val updatedMillis = 100L
      val updatedFlight = ApiFlightWithSplits(ArrivalGenerator.arrival("BA0001"), Set(), lastUpdated = Option(updatedMillis))
      val updatedFlights = FlightsWithSplits(Seq(updatedFlight))
      val emptyQueues = MinutesContainer.empty[CrunchMinute, TQM]
      val emptyStaff = MinutesContainer.empty[StaffMinute, TM]
      val replyWithUpdates = makeReplyWithUpdates(updatedFlights, emptyQueues, emptyStaff)

      "Then I should see an Option of PortStateUpdates send with the update and the latest updated millis" >> {
        replyWithUpdates(0L, 0L, 0L, self)
        expectMsg(Option(PortStateUpdates(updatedMillis, Set(updatedFlight), Set(), Set())))
        success
      }
    }
  }

  "Given a replyWithUpdates function with mock requesters returning one updated flight, and a more recently updated CrunchMinute" >> {
    "When I ask to reply with updates" >> {
      val maxUpdatedMillis = 100L
      val updatedFlight = ApiFlightWithSplits(ArrivalGenerator.arrival("BA0001"), Set(), lastUpdated = Option(10L))
      val updatedQueueMinute = CrunchMinute(T1, EeaDesk, 0L, 0, 0, 0, 0, lastUpdated = Option(maxUpdatedMillis))

      val updatedFlights = FlightsWithSplits(Seq(updatedFlight))
      val updatedQueues = MinutesContainer[CrunchMinute, TQM](Seq(updatedQueueMinute))
      val emptyStaff = MinutesContainer.empty[StaffMinute, TM]
      val replyWithUpdates = makeReplyWithUpdates(updatedFlights, updatedQueues, emptyStaff)

      "Then I should see an Option of PortStateUpdates send with both updates and the latest updated millis" >> {
        replyWithUpdates(0L, 0L, 0L, self)
        expectMsg(Option(PortStateUpdates(maxUpdatedMillis, Set(updatedFlight), Set(updatedQueueMinute), Set())))
        success
      }
    }
  }

  "Given a replyWithUpdates function with mock requesters returning one updated flight, one updated CrunchMinute, and a more recently updated StaffMinute" >> {
    "When I ask to reply with updates" >> {
      val maxUpdatedMillis = 100L
      val updatedFlight = ApiFlightWithSplits(ArrivalGenerator.arrival("BA0001"), Set(), lastUpdated = Option(10L))
      val updatedQueueMinute = CrunchMinute(T1, EeaDesk, 0L, 0, 0, 0, 0, lastUpdated = Option(50L))
      val updatedStaffMinute = StaffMinute(T1, 0L, 0, 0, 0, lastUpdated = Option(maxUpdatedMillis))

      val updatedFlights = FlightsWithSplits(Seq(updatedFlight))
      val updatedQueues = MinutesContainer[CrunchMinute, TQM](Seq(updatedQueueMinute))
      val updatedStaff = MinutesContainer[StaffMinute, TM](Seq(updatedStaffMinute))
      val replyWithUpdates = makeReplyWithUpdates(updatedFlights, updatedQueues, updatedStaff)

      "Then I should see an Option of PortStateUpdates send with both updates and the latest updated millis" >> {
        replyWithUpdates(0L, 0L, 0L, self)
        expectMsg(Option(PortStateUpdates(maxUpdatedMillis, Set(updatedFlight), Set(updatedQueueMinute), Set(updatedStaffMinute))))
        success
      }
    }

    "Given a replyWithPortState function with mock requesters returning no data" >> {
      "When I ask to reply with the PortState" >> {
        val emptyFlights = FlightsWithSplits.empty
        val emptyQueues = MinutesContainer.empty[CrunchMinute, TQM]
        val emptyStaff = MinutesContainer.empty[StaffMinute, TM]
        val replyWithPortState = makeReplyWithPortState(emptyFlights, emptyQueues, emptyStaff)

        "Then I should see an empty PortState being sent" >> {
          replyWithPortState(self, GetStateForDateRange(0L, 0L))
          expectMsg(PortState.empty)
          success
        }
      }
    }

    "Given a replyWithPortState function with mock requesters returning some data" >> {
      "When I ask to reply with the PortState" >> {
        val flight = ApiFlightWithSplits(ArrivalGenerator.arrival("BA0001"), Set(), lastUpdated = Option(10L))
        val queueMinute = CrunchMinute(T1, EeaDesk, 0L, 0, 0, 0, 0, lastUpdated = Option(50L))
        val staffMinute = StaffMinute(T1, 0L, 0, 0, 0, lastUpdated = Option(75L))
        val flights = FlightsWithSplits(Seq(flight))
        val queues = MinutesContainer[CrunchMinute, TQM](Seq(queueMinute))
        val staff = MinutesContainer[StaffMinute, TM](Seq(staffMinute))
        val replyWithPortState = makeReplyWithPortState(flights, queues, staff)

        "Then I should see a PortState containing the mock data being sent" >> {
          replyWithPortState(self, GetStateForDateRange(0L, 0L))
          expectMsg(PortState(Seq(flight), Seq(queueMinute), Seq(staffMinute)))
          success
        }
      }
    }
  }

  private def makeReplyWithUpdates(flights: FlightsWithSplits,
                                   queues: MinutesContainer[CrunchMinute, TQM],
                                   staff: MinutesContainer[StaffMinute, TM]): PortStateUpdatesRequester = {
    val mockFlightsRequester = (_: PortStateRequest) => Future(flights)
    val mockQueuesRequester = (_: PortStateRequest) => Future(queues)
    val mockStaffRequester = (_: PortStateRequest) => Future(staff)
    PartitionedPortStateActor.replyWithUpdatesFn(mockFlightsRequester, mockQueuesRequester, mockStaffRequester)
  }

  private def makeReplyWithPortState(flights: FlightsWithSplits,
                                     queues: MinutesContainer[CrunchMinute, TQM],
                                     staff: MinutesContainer[StaffMinute, TM]): PortStateRequester = {
    val mockFlightsRequester = (_: PortStateRequest) => Future(Source(List(flights)))
    val mockQueuesRequester = (_: PortStateRequest) => Future(queues)
    val mockStaffRequester = (_: PortStateRequest) => Future(staff)
    PartitionedPortStateActor.replyWithPortStateFn(mockFlightsRequester, mockQueuesRequester, mockStaffRequester)
  }

  private def mockResponseActor(response: Any): ActorRef = {
    system.actorOf(Props(new MockRequestHandler(response)))
  }
}
