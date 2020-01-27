package services.persistence

import actors.RecoveryActorLike
import akka.actor.{ActorRef, Props}
import akka.testkit.TestProbe
import drt.shared.SDateLike
import scalapb.GeneratedMessage
import org.slf4j.{Logger, LoggerFactory}
import server.protobuf.messages.FlightsMessage.{FlightMessage, FlightsDiffMessage}
import services.SDate
import services.crunch.CrunchTestLike

import scala.concurrent.duration._

class TestSnapshottingActor(probe: ActorRef, snapshotMessage: GeneratedMessage, override val snapshotBytesThreshold: Int = 0, override val maybeSnapshotInterval: Option[Int] = None) extends RecoveryActorLike {
  override val now: () => SDateLike = () => SDate.now
  override val log: Logger = LoggerFactory.getLogger(getClass)

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case _ => Unit
  }

  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case _ => Unit
  }

  override def stateToMessage: GeneratedMessage = snapshotMessage

  override def receiveCommand: Receive = {
    case m: GeneratedMessage => persistAndMaybeSnapshot(m)
  }

  override def persistenceId: String = "testsnapshotter"

  override def saveSnapshot(snapshot: Any): Unit = probe ! snapshot
}

class SnapshottingSpec extends CrunchTestLike {
  "Given an actor extending the RecoveryActorLike trait " +
    "When I send a message to be persisted and it's over the bytes threshold " +
    "Then I should see the snapshot being saved" >> {
    val probe = TestProbe("snapshotprobe")

    val snapshotMessage = new FlightsDiffMessage(None, Seq(), Seq())

    val snapshotBytesThreshold = 1
    val testActor = system.actorOf(Props(classOf[TestSnapshottingActor], probe.ref, snapshotMessage, snapshotBytesThreshold, None))

    testActor ! new FlightMessage(iATA = Option("BA1010"))

    val received = probe.receiveN(1)

    received === Seq(snapshotMessage)
  }

  "Given an actor extending the RecoveryActorLike trait " +
    "When I send a message to be persisted and it's under the bytes threshold " +
    "Then I should not see the a snapshot being saved" >> {
    val probe = TestProbe("snapshotprobe")

    val snapshotMessage = new FlightsDiffMessage(None, Seq(), Seq())

    val snapshotBytesThreshold = 10
    val testActor = system.actorOf(Props(classOf[TestSnapshottingActor], probe.ref, snapshotMessage, snapshotBytesThreshold, None))

    testActor ! new FlightMessage(iATA = Option("BA1010"))

    probe.expectNoMessage(2 seconds)

    true
  }

  "Given an actor extending the RecoveryActorLike trait and a snapshot interval of 10 " +
    "When I send 9 messages to be persisted " +
    "Then I should not see a snapshot being saved" >> {
    val probe = TestProbe("snapshotprobe")

    val snapshotMessage = new FlightsDiffMessage(None, Seq(), Seq())

    val snapshotInterval = Option(10)
    val snapshotBytesThreshold = 1024
    val testActor = system.actorOf(Props(classOf[TestSnapshottingActor], probe.ref, snapshotMessage, snapshotBytesThreshold, snapshotInterval))

    1 to 9 foreach (_ => testActor ! new FlightMessage(iATA = Option("BA1010")))

    probe.expectNoMessage(2 seconds)

    true
  }

  "Given an actor extending the RecoveryActorLike trait and a snapshot interval of 10 " +
    "When I send the 10th message to be persisted " +
    "Then I should see the snapshot being saved" >> {
    val probe = TestProbe("snapshotprobe")

    val snapshotMessage = new FlightsDiffMessage(None, Seq(), Seq())

    val snapshotInterval = Option(10)
    val snapshotBytesThreshold = 1024
    val testActor = system.actorOf(Props(classOf[TestSnapshottingActor], probe.ref, snapshotMessage, snapshotBytesThreshold, snapshotInterval))

    1 to 10 foreach (_ => testActor ! new FlightMessage(iATA = Option("BA1010")))

    val received = probe.receiveN(1)

    received === Seq(snapshotMessage)
  }
}
