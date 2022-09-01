package actors.routing

import akka.actor.{ActorRef, Props}
import akka.pattern.ask
import akka.testkit.TestProbe
import drt.shared.DataUpdates.Combinable
import services.crunch.CrunchTestLike

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

  "A control actor should send the response back to caller" >> {
    val actor = system.actorOf(Props(new SequentialAccessActor[String, String, Strings](callResource(Map()), splitByResource)))

    Await.result(actor.ask("A1"), 1.second) === Strings(List("A <- 1"))
  }
}
