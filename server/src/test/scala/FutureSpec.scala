import akka.actor.{Actor, ActorRef, Props}
import akka.pattern.AskableActorRef
import akka.testkit.TestProbe
import akka.util.Timeout
import org.slf4j.{Logger, LoggerFactory}
import services.crunch.CrunchTestLike

import scala.concurrent.duration._

class SlowActor(probeActor: ActorRef, waitMillis: Long) extends Actor {
  val log: Logger = LoggerFactory.getLogger(getClass)

  override def receive: Receive = {
    case _ =>
      log.info(s"Got a message. I'm going to take ${waitMillis}ms to respond")
      Thread.sleep(waitMillis)
      throw new Exception("Woop!")
      probeActor ! "A slow response"
  }
}

class FutureSpec extends CrunchTestLike {
  val delayMillis = 200L

  s"Given a timeout of ${delayMillis}ms and an actor taking 200ms to response " +
  "When I await for a response via the test probe " +
  "I should still get the response despite the timeout being triggered" >>{
    skipped("exploratory")
    val testProbe = TestProbe("slow-test")
    val slowActor: AskableActorRef = system.actorOf(Props(classOf[SlowActor], testProbe.ref, delayMillis))

    slowActor.ask("Some question")(new Timeout(100 milliseconds)).recover {
      case t => println(s"Something went wrong: ", t)
    }

    testProbe.expectMsg("A slow response")

    success
  }
}
