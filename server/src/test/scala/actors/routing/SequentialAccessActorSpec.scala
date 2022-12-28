package actors.routing

import actors.AddUpdatesSubscriber
import actors.acking.AckingReceiver.Ack
import actors.persistent.QueueLikeActor.UpdatedMillis
import akka.actor.{ActorRef, Props}
import akka.pattern.ask
import akka.testkit.TestProbe
import drt.shared.CrunchApi.{CrunchMinute, DeskRecMinute, MinuteLike, MinutesContainer, PassengersMinute}
import drt.shared.DataUpdates.Combinable
import drt.shared.TQM
import uk.gov.homeoffice.drt.time.SDate
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.arrivals.WithTimeAccessor
import uk.gov.homeoffice.drt.ports.Queues
import uk.gov.homeoffice.drt.ports.Queues.EeaDesk
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.UtcDate

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}

class SequentialAccessActorSpec extends CrunchTestLike {
  def callResource(probeRefs: Map[String, ActorRef]): (String, String) => Future[Strings] = (resource: String, request: String) => {
    probeRefs.get(resource).foreach(_ ! ((resource, request)))
    Future({
      Thread.sleep(100)
      probeRefs.get(resource).foreach(_ ! ((resource, s"$request done")))
      Strings(List(s"$resource <- $request"))
    })
  }

  val splitByResource: String => Iterable[(String, String)] = (request: String) =>
    request
      .split(",")
      .map(r => (r.take(1), r.takeRight(1)))

  case class Strings(strings: List[String]) extends Combinable[Strings] {
    override def ++(other: Strings): Strings = copy(strings = strings ++ other.strings)
  }

  "A control actor should send a request" >> {
    val probe = TestProbe()
    val actor = system.actorOf(Props(new SequentialAccessActor[String, String, Strings](callResource(Map("A" -> probe.ref)), splitByResource)))

    actor ! "A1,A2"

    probe.expectMsg(("A", "1"))
    probe.expectMsg(("A", "1 done"))
    probe.expectMsg(("A", "2"))
    probe.expectMsg(("A", "2 done"))

    success
  }

  "A control actor should send a requests sequentially in the order they were received" >> {
    val probeA = TestProbe()
    val probeB = TestProbe()
    val actor = system.actorOf(Props(new SequentialAccessActor[String, String, Strings](callResource(Map(
      "A" -> probeA.ref,
      "B" -> probeB.ref,
    )), splitByResource)))

    actor ! "A1,A2,B1"
    actor ! "B2"


    probeA.expectMsg(("A", "1"))
    probeA.expectMsg(("A", "1 done"))
    probeA.expectMsg(("A", "2"))
    probeA.expectMsg(("A", "2 done"))
    probeB.expectMsg(("B", "1"))
    probeB.expectMsg(("B", "1 done"))
    probeB.expectMsg(("B", "2"))
    probeB.expectMsg(("B", "2 done"))

    success
  }

  "A control actor should send updates to subscribers, and an Ack to the caller" >> {
    val probeA = TestProbe()
    val probeB = TestProbe()
    val actor = system.actorOf(Props(new SequentialAccessActor[String, String, Strings](callResource(Map()), splitByResource)))

    actor ! AddUpdatesSubscriber(probeA.ref)
    actor ! AddUpdatesSubscriber(probeB.ref)

    val ackReceived = Await.result(actor.ask("A1,A2,B1"), 1.second) === Ack

    probeA.expectMsg(Strings(List("A <- 1", "A <- 2", "B <- 1")))
    probeB.expectMsg(Strings(List("A <- 1", "A <- 2", "B <- 1")))

    ackReceived
  }

  type PaxMinutes = MinutesContainer[PassengersMinute, TQM]

  "A PassengerMinute control actor should send updates to subscribers, and an Ack to the caller" >> {
    val probeA = TestProbe()
    val probeB = TestProbe()

    def callResource(probeRefs: Map[(Terminal, UtcDate), ActorRef]): ((Terminal, UtcDate), PaxMinutes) => Future[UpdatedMillis] =
      (resource: (Terminal, UtcDate), request: PaxMinutes) => {
        probeRefs.get(resource).foreach(_ ! ((resource, request)))
        Future({
          Thread.sleep(100)
          probeRefs.get(resource).foreach(_ ! ((resource, s"$request done")))
          UpdatedMillis(Set(0, 1))
        })
      }

    val splitByResource = (request: PaxMinutes) => {
      request.minutes.groupBy(m => (m.terminal, SDate(m.minute).toUtcDate)).map {
        case ((terminal, date), minutes) => ((terminal, date), MinutesContainer(minutes))
      }
    }

    val props = Props(new SequentialAccessActor[(Terminal, UtcDate), PaxMinutes, UpdatedMillis](
      callResource(Map()), splitByResource))
    val actor = system.actorOf(props)

    actor ! AddUpdatesSubscriber(probeA.ref)
    actor ! AddUpdatesSubscriber(probeB.ref)

    val ackReceived = Await.result(actor.ask(MinutesContainer(Seq(
      PassengersMinute(Terminal("T1"), EeaDesk, SDate("2022-09-01T08:00").millisSinceEpoch, Seq(1, 2, 3), None),
      PassengersMinute(Terminal("T2"), EeaDesk, SDate("2022-09-02T08:00").millisSinceEpoch, Seq(4, 5, 6), None),
      PassengersMinute(Terminal("T1"), EeaDesk, SDate("2022-09-03T08:00").millisSinceEpoch, Seq(7, 8, 9), None),
      PassengersMinute(Terminal("T2"), EeaDesk, SDate("2022-09-04T08:00").millisSinceEpoch, Seq(10, 11, 12), None),
    ))), 1.second) === Ack

    probeA.expectMsg(UpdatedMillis(Set(0, 1)))
    probeB.expectMsg(UpdatedMillis(Set(0, 1)))

    ackReceived
  }

  type CrunchMinutes = MinutesContainer[MinuteLike[CrunchMinute, TQM], TQM]

  "A CrunchMinute control actor should send updates to subscribers if they're DeskRecMinutes, and an Ack to the caller" >> {
    val probeA = TestProbe()
    val probeB = TestProbe()

    def callResource[RES, REQ, RESP](probeRefs: Map[RES, ActorRef]): (RES, REQ) => Future[UpdatedMillis] =
      (resource: RES, request: REQ) => {
        probeRefs.get(resource).foreach(_ ! ((resource, request)))
        Future({
          probeRefs.get(resource).foreach(_ ! ((resource, s"$request done")))
          UpdatedMillis(Set(0, 1))
        })
      }

    def splitByResource[A, B <: WithTimeAccessor](request: MinutesContainer[A, B]): Map[(Terminal, UtcDate), MinutesContainer[A, B]] = {
      request.minutes.groupBy(m => (m.terminal, SDate(m.minute).toUtcDate)).map {
        case ((terminal, date), minutes) => ((terminal, date), MinutesContainer(minutes))
      }
    }

    val props = Props(new SequentialAccessActor[(Terminal, UtcDate), CrunchMinutes, UpdatedMillis](
      callResource(Map()), splitByResource) {
      override def shouldSendEffectsToSubscribers(request: CrunchMinutes): Boolean = request.minutes.exists(_.isInstanceOf[DeskRecMinute])
    })
    val actor = system.actorOf(props)

    actor ! AddUpdatesSubscriber(probeA.ref)
    actor ! AddUpdatesSubscriber(probeB.ref)

    val crunchMinute = CrunchMinute(Terminal("T1"), EeaDesk, SDate("2022-09-01T08:00").millisSinceEpoch, 1, 1, 1, 1, None)
    val deskRecMinute = DeskRecMinute(Terminal("T1"), EeaDesk, SDate("2022-09-01T08:00").millisSinceEpoch, 1, 1, 1, 1, None)

    val ack1Received = Await.result(actor.ask(MinutesContainer(Seq(crunchMinute))), 1.second) === Ack

    probeA.expectNoMessage(250.millis)
    probeB.expectNoMessage(250.millis)

    val ack2Received = Await.result(actor.ask(MinutesContainer(Seq(deskRecMinute))), 1.second) === Ack

    probeA.expectMsg(UpdatedMillis(Set(0, 1)))
    probeB.expectMsg(UpdatedMillis(Set(0, 1)))

    ack1Received && ack2Received
  }
}
