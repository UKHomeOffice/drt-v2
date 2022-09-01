package actors.routing

import akka.actor.{ActorRef, Props}
import akka.pattern.ask
import akka.testkit.TestProbe
import services.crunch.CrunchTestLike

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}

class ResourceControlActorSpec extends CrunchTestLike {
  def callResource(probeRefs: Map[String, ActorRef]): (String, String) => Future[String] = (resource: String, request: String) => {
    probeRefs.get(resource).foreach(_ ! ((resource, request)))
    Future({
      Thread.sleep(100)
      probeRefs.get(resource).foreach(_ ! s"$request done")
      resource + request
    })
  }

  "A control actor should send a request" >> {
    val probe = TestProbe()
    val actor = system.actorOf(Props(new ResourceControlActor[String, String, String](callResource(Map("A" -> probe.ref)))))

    actor ! (("A", "A1"))

    probe.expectMsg(("A", "A1"))
    probe.expectMsg("A1 done")

    success
  }

  "A control actor should send a request to the same resource sequentially" >> {
    val probe = TestProbe()
    val actor = system.actorOf(Props(new ResourceControlActor[String, String, String](callResource(Map("A" -> probe.ref)))))

    actor ! (("A", "A1"))
    actor ! (("A", "A2"))

    probe.expectMsg(("A", "A1"))
    probe.expectMsg("A1 done")
    probe.expectMsg(("A", "A2"))
    probe.expectMsg("A2 done")

    success
  }

  "A control actor should send a request to the same resource sequentially when there are multiple resources" >> {
    val probeA = TestProbe()
    val probeB = TestProbe()
    val actor = system.actorOf(Props(new ResourceControlActor[String, String, String](
      callResource(Map("A" -> probeA.ref, "B" -> probeB.ref))
    )))

    actor ! (("A", "A1"))
    actor ! (("B", "B1"))
    actor ! (("A", "A2"))
    actor ! (("B", "B2"))

    probeA.expectMsg(("A", "A1"))
    probeA.expectMsg("A1 done")
    probeB.expectMsg(("B", "B1"))
    probeB.expectMsg("B1 done")

    probeA.expectMsg(("A", "A2"))
    probeA.expectMsg("A2 done")
    probeB.expectMsg(("B", "B2"))
    probeB.expectMsg("B2 done")

    success
  }

  "A control actor should send the response back to caller" >> {
    val actor = system.actorOf(Props(new ResourceControlActor[String, String, String](callResource(Map()))))

    Await.result(actor.ask(("A", "A1")), 1.second)

    success
  }
}
