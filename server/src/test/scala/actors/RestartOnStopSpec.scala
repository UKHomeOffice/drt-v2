package actors

import actors.StoppableActor.{Ack, StopYourself}
import actors.supervised.RestartOnStop
import akka.actor.{Actor, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit}
import org.slf4j.LoggerFactory
import org.specs2.mutable.SpecificationLike
import org.specs2.specification.BeforeAfterAll

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.DurationInt


object StoppableActor {
  case object StopYourself

  case object Ack
}

class StoppableActor extends Actor {
  private val log = LoggerFactory.getLogger(getClass)
  implicit val ec: ExecutionContextExecutor = context.dispatcher

  override def receive: Receive = {
    case StopYourself =>
      log.info(s"Received StopYourself. Stopping")
      context.stop(self)

    case other =>
      log.info(s"Received a message: $other")
      sender() ! Ack
  }
}

class RestartOnStopSpec()
  extends TestKit(ActorSystem())
    with ImplicitSender
    with SpecificationLike
    with BeforeAfterAll {

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }

  override def beforeAll(): Unit = {}

  "A supervisor" >> {
    val maxBackoff = 1.millis
    val restartOnStop = RestartOnStop(1.millis, maxBackoff)
    "I should see the exception get caught" in {
      val actor = restartOnStop.actorOf(Props[StoppableActor], "my-actor")

      watch(actor)

      actor ! "some message"
      expectMsgType[Ack.type]

      actor ! StopYourself

      val backoffTimePlusBuffer = maxBackoff + 100.millis
      Thread.sleep(backoffTimePlusBuffer.toMillis)

      actor ! "some message"
      expectMsgType[Ack.type]

      actor ! "some message"
      expectMsgType[Ack.type]

      success
    }
  }
}
