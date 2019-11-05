package services.crunch.deskrecs

import actors.acking.AckingReceiver.{Ack, StreamCompleted, StreamFailure, StreamInitialized}
import akka.actor.{Actor, ActorRef, Props}
import akka.pattern.AskableActorRef
import akka.testkit.TestProbe
import akka.util.Timeout
import drt.shared.CrunchApi.DeskRecMinutes
import drt.shared.FlightsApi.FlightsWithSplits
import org.slf4j.{Logger, LoggerFactory}
import services.crunch.CrunchTestLike
import services.graphstages.TestableCrunchLoadStage
import services.{OptimizerConfig, OptimizerCrunchResult, SDate}

import scala.concurrent.duration._
import scala.util.Try


class MockPortStateActor(probe: TestProbe, var responseDelayMillis: Long = 0L) extends Actor {
  val log: Logger = LoggerFactory.getLogger(getClass)

  override def receive: Receive = {
    case StreamInitialized =>
      sender() ! Ack

    case StreamCompleted =>
      log.info(s"Completed")
      probe.ref ! StreamCompleted

    case StreamFailure(t) =>
      log.error(s"Failed", t)
      probe.ref ! StreamFailure

    case getFlights: GetFlights =>
      Thread.sleep(responseDelayMillis)
      sender() ! FlightsWithSplits(List(), List())
      probe.ref ! getFlights

    case drm: DeskRecMinutes =>
      sender() ! Ack
      probe.ref ! drm

    case message =>
      log.warn(s"Hmm got a $message")
      sender() ! Ack
      probe.ref ! message
  }
}

class RunnableDeskRecsSpec extends CrunchTestLike {
  implicit val timeout: Timeout = new Timeout(250 milliseconds)

  val mockCrunch: (Seq[Double], Seq[Int], Seq[Int], OptimizerConfig) => Try[OptimizerCrunchResult] = TestableCrunchLoadStage.mockCrunch
  val noDelay: Long = 0L
  val longDelay = 500L

  "Given a RunnableDescRecs with a mock PortStateActor and mock crunch " +
    "When I give it a millisecond of 2019-01-01T00:00 " +
    "I should see a request for flights for 2019-01-01 00:00 to 00:30" >> {
    val portStateProbe = TestProbe("port-state")
    val mockPortStateActor = system.actorOf(Props(classOf[MockPortStateActor], portStateProbe, noDelay))
    val (millisToCrunchSourceActor: ActorRef, _) = RunnableDeskRecs(mockPortStateActor, 30, mockCrunch, airportConfig).run()
    val askableSource: AskableActorRef = millisToCrunchSourceActor

    val midnight20190101 = SDate("2019-01-01T00:00")
    askableSource ? List(midnight20190101.millisSinceEpoch)

    val expectedStart = midnight20190101.millisSinceEpoch
    val expectedEnd = midnight20190101.addMinutes(30).millisSinceEpoch

    portStateProbe.fishForMessage(1 second) {
      case GetFlights(start, end) => start == expectedStart && end == expectedEnd
    }

    success
  }

  "Given a RunnableDescRecs with a mock PortStateActor and mock crunch " +
    "When I give it a long response delay " +
    "I should not see a StreamComplete message (because the timeout exception is handled)" >> {
    val portStateProbe = TestProbe("port-state")
    val mockPortStateActor = system.actorOf(Props(classOf[MockPortStateActor], portStateProbe, longDelay))

    val (millisToCrunchSourceActor: ActorRef, _) = RunnableDeskRecs(mockPortStateActor, 30, mockCrunch, airportConfig).run()
    val askableSource: AskableActorRef = millisToCrunchSourceActor

    val midnight20190101 = SDate("2019-01-01T00:00")
    askableSource ? List(midnight20190101.millisSinceEpoch)

    val expectedStart = midnight20190101.millisSinceEpoch
    val expectedEnd = midnight20190101.addMinutes(30).millisSinceEpoch

    portStateProbe.fishForMessage(1 second) {
      case GetFlights(start, end) => start == expectedStart && end == expectedEnd
    }

    portStateProbe.expectNoMessage(500 milliseconds)

    success
  }
}
