package actors

import actors.ExceptionThrowingActor.{Ack, StopYourself}
import actors.supervised.RestartOnStopActor
import akka.actor.{Actor, ActorSystem, Props}
import akka.pattern.BackoffOpts
import akka.testkit.{ImplicitSender, TestKit}
import org.slf4j.LoggerFactory
import org.specs2.mutable.SpecificationLike
import org.specs2.specification.BeforeAfterAll

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.DurationInt


object ExceptionThrowingActor {
  case object StopYourself

  case object Ack
}

class ExceptionThrowingActor extends Actor {
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

class ArrivalsActorDeathSpec()
  extends TestKit(ActorSystem())
    with ImplicitSender
    with SpecificationLike
    with BeforeAfterAll {

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }

  override def beforeAll(): Unit = {}

  "A supervisor" >> {
    "I should see the exception get caught" in {
      val maxBackoff = 1.millis
      val backoffOpts = BackoffOpts.onStop(
        childProps = Props[ExceptionThrowingActor],
        childName = "my-lovely-actor",
        minBackoff = 1.millis,
        maxBackoff = maxBackoff,
        randomFactor = 0
      )
      val actor = system.actorOf(Props(classOf[RestartOnStopActor], backoffOpts))

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
