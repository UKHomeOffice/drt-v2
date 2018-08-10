package services.persistence

import actors.RecoveryActorLike
import akka.actor.{ActorRef, Props}
import akka.testkit.TestProbe
import com.trueaccord.scalapb.GeneratedMessage
import org.slf4j.{Logger, LoggerFactory}
import server.protobuf.messages.FlightsMessage.{FlightMessage, FlightsDiffMessage}
import services.crunch.CrunchTestLike

class TestSnapshottingActor(probe: ActorRef, snapshotMessage: GeneratedMessage) extends RecoveryActorLike {
  override val log: Logger = LoggerFactory.getLogger(getClass)
  override val snapshotBytesThreshold: Int = 0

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

    val testActor = system.actorOf(Props(classOf[TestSnapshottingActor], probe.ref, snapshotMessage))

    testActor ! new FlightMessage(iATA = Option("BA1010"))

    val received = probe.receiveN(1)

    received === Seq(snapshotMessage)
  }
}