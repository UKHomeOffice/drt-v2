package actors

import actors.PartitionedPortStateActor.{GetStateForDateRange, PointInTimeQuery}
import akka.actor.{Actor, ActorRef, Props}
import akka.testkit.TestProbe
import uk.gov.homeoffice.drt.ports.Queues.Queue
import drt.shared.SDateLike
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import services.SDate
import services.crunch.CrunchTestLike
import services.graphstages.Crunch

import scala.concurrent.duration._


class DummyActor extends Actor {
  override def receive: Receive = {
    case _ =>
  }
}

class PartitionedPortStateActorSpec extends CrunchTestLike {
  val probe: TestProbe = TestProbe("port-state")
  val flightsActor: ActorRef = system.actorOf(Props(new DummyActor))
  val queuesActor: ActorRef = system.actorOf(Props(new DummyActor))
  val staffActor: ActorRef = system.actorOf(Props(new DummyActor))
  val queueUpdatesActor: ActorRef = system.actorOf(Props(new DummyActor))
  val staffUpdatesActor: ActorRef = system.actorOf(Props(new DummyActor))
  val flightUpdatesActor: ActorRef = system.actorOf(Props(new DummyActor))
  val pointInTime = "2020-07-06T12:00"
  val myNow: () => SDateLike = () => SDate(pointInTime)
  val queues: Map[Terminal, Seq[Queue]] = defaultAirportConfig.queuesByTerminal
  val journalType: StreamingJournalLike = InMemoryStreamingJournal

  "Given a PartitionedPortStateActor, a legacy data cutoff off of 2020-07-06T12:00" >> {
    "When I request GetStateForDateRange wrapped in a PointInTimeQuery for 2020-07-06T12:00 (matching the cutoff)" >> {
      "I should see that no request is forwarded to the CrunchStateReadActor" >> {
        val portStateActor = system.actorOf(Props(new PartitionedPortStateActor(
          flightsActor,
          queuesActor,
          staffActor,
          queueUpdatesActor,
          staffUpdatesActor,
          flightUpdatesActor,
          myNow,
          queues,
          journalType
        )))
        val rangeStart = SDate("2020-10-10")
        val rangeEnd = rangeStart.addDays(1)
        val dateRangeMessage = GetStateForDateRange(rangeStart.millisSinceEpoch, rangeEnd.millisSinceEpoch)
        val pitMessage = PointInTimeQuery(SDate(pointInTime).millisSinceEpoch, dateRangeMessage)
        portStateActor ! pitMessage
        probe.expectNoMessage(250 milliseconds)
        success
      }
    }

    "When I request GetStateForDateRange with an end date whose last local midnight is before the legacy cutoff" >> {
      "I should see that no request is forwarded to the CrunchStateReadActor" >> {
        val portStateActor = system.actorOf(Props(new PartitionedPortStateActor(
          flightsActor,
          queuesActor,
          staffActor,
          queueUpdatesActor,
          staffUpdatesActor,
          flightUpdatesActor,
          myNow,
          queues,
          journalType
        )))
        val rangeStart = SDate("2020-07-06T00:00", Crunch.utcTimeZone)
        val rangeEnd = SDate("2020-07-07T12:59", Crunch.utcTimeZone)
        val message = GetStateForDateRange(rangeStart.millisSinceEpoch, rangeEnd.millisSinceEpoch)
        portStateActor ! message
        probe.expectNoMessage(250 milliseconds)
        success
      }
    }

  }
}
