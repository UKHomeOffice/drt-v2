package actors

import actors.PartitionedPortStateActor.{DateRangeLike, GetStateForDateRange, PointInTimeQuery}
import actors.pointInTime.CrunchStateReadActor
import akka.actor.{Actor, ActorRef, Props}
import akka.testkit.TestProbe
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.Queues.Queue
import drt.shared.SDateLike
import drt.shared.Terminals.Terminal
import services.SDate
import services.crunch.CrunchTestLike
import services.graphstages.Crunch

import scala.concurrent.duration._

class TestCrunchStateReadActor(probe: ActorRef,
                               pointInTime: SDateLike,
                               expireAfterMillis: Int,
                               portQueues: Map[Terminal, Seq[Queue]],
                               startMillis: MillisSinceEpoch,
                               endMillis: MillisSinceEpoch) extends CrunchStateReadActor(pointInTime, expireAfterMillis, portQueues, startMillis, endMillis, 1000) {
  override def receiveCommand: Receive = {
    case msg => probe ! msg
  }
}

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
  val cutOff = "2020-07-06T12:00"
  val myNow: () => SDateLike = () => SDate(cutOff)
  val queues: Map[Terminal, Seq[Queue]] = defaultAirportConfig.queuesByTerminal
  val journalType: StreamingJournalLike = InMemoryStreamingJournal
  val legacyDataCutoff: SDateLike = SDate(cutOff)

  def tempLegacyActorProps(pointInTime: SDateLike,
                           message: DateRangeLike,
                           queues: Map[Terminal, Seq[Queue]],
                           expireAfterMillis: Int): Props = {
    Props(new TestCrunchStateReadActor(probe.ref, pointInTime, expireAfterMillis, queues, message.from, message.to))
  }

  "Given a PartitionedPortStateActor, a legacy data cutoff off of 2020-07-06T12:00" >> {
    "Legacy requests">> {
      "When I request GetStateForDateRange wrapped in a PointInTimeQuery for 2020-07-06T11:59 (one minute before the cutoff)" >> {
        "I should see that the unwrapped request is forwarded to the CrunchStateReadActor" >> {
          val portStateActor = system.actorOf(Props(new PartitionedPortStateActor(flightsActor, queuesActor, staffActor, queueUpdatesActor, staffUpdatesActor, myNow, queues, journalType, legacyDataCutoff, tempLegacyActorProps)))
          val beforeCutoff = SDate(cutOff).addMinutes(-1)
          val rangeStart = SDate("2020-10-10")
          val rangeEnd = rangeStart.addDays(1)
          val dateRangeMessage = GetStateForDateRange(rangeStart.millisSinceEpoch, rangeEnd.millisSinceEpoch)
          val legacyPitMessage = PointInTimeQuery(beforeCutoff.millisSinceEpoch, dateRangeMessage)
          portStateActor ! legacyPitMessage
          probe.expectMsg(dateRangeMessage)
          success
        }
      }

      "When I request GetStateForDateRange with an end date whose last local midnight is before the legacy cutoff" >> {
        "I should see that the request is forwarded to the CrunchStateReadActor" >> {
          val portStateActor = system.actorOf(Props(new PartitionedPortStateActor(flightsActor, queuesActor, staffActor, queueUpdatesActor, staffUpdatesActor, myNow, queues, journalType, legacyDataCutoff, tempLegacyActorProps)))
          val rangeStart = SDate("2020-07-05T00:00", Crunch.utcTimeZone)
          val rangeEnd = SDate("2020-07-06T11:59", Crunch.utcTimeZone)
          val message = GetStateForDateRange(rangeStart.millisSinceEpoch, rangeEnd.millisSinceEpoch)
          portStateActor ! message
          probe.expectMsg(message)
          success
        }
      }
    }

    "Non-legacy requests" >> {
      "When I request GetStateForDateRange wrapped in a PointInTimeQuery for 2020-07-06T12:00 (matching the cutoff)" >> {
        "I should see that no request is forwarded to the CrunchStateReadActor" >> {
          val portStateActor = system.actorOf(Props(new PartitionedPortStateActor(flightsActor, queuesActor, staffActor, queueUpdatesActor, staffUpdatesActor, myNow, queues, journalType, legacyDataCutoff, tempLegacyActorProps)))
          val rangeStart = SDate("2020-10-10")
          val rangeEnd = rangeStart.addDays(1)
          val dateRangeMessage = GetStateForDateRange(rangeStart.millisSinceEpoch, rangeEnd.millisSinceEpoch)
          val legacyPitMessage = PointInTimeQuery(SDate(cutOff).millisSinceEpoch, dateRangeMessage)
          portStateActor ! legacyPitMessage
          probe.expectNoMessage(250 milliseconds)
          success
        }
      }

      "When I request GetStateForDateRange with an end date whose last local midnight is before the legacy cutoff" >> {
        "I should see that no request is forwarded to the CrunchStateReadActor" >> {
          val portStateActor = system.actorOf(Props(new PartitionedPortStateActor(flightsActor, queuesActor, staffActor, queueUpdatesActor, staffUpdatesActor, myNow, queues, journalType, legacyDataCutoff, tempLegacyActorProps)))
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
}
