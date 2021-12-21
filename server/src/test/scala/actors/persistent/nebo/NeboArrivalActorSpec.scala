package actors.persistent.nebo

import actors.persistent.nebo.NeboArrivalActor.getRedListPassengerFlightKey
import actors.persistent.staffing.GetState
import actors.serializers.NeboArrivalMessageConversion
import actors.serializers.NeboArrivalMessageConversion.{redListPassengersToNeboArrivalMessage, snapshotMessageToNeboArrival}
import akka.actor.{ActorRef, Props}
import akka.pattern.ask
import akka.persistence.{RecoveryCompleted, SaveSnapshotFailure, SaveSnapshotSuccess, SnapshotOffer}
import akka.testkit.{ImplicitSender, TestProbe}
import drt.shared.{NeboArrivals, RedListPassengers, SDateLike}
import scalapb.GeneratedMessage
import server.protobuf.messages.NeboPassengersMessage.NeboArrivalSnapshotMessage
import services.SDate
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.ports.PortCode
import util.RandomString

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Try}


object NeboArrivalActorTest {

  def props(redListPassengers: RedListPassengers, now: () => SDateLike, testProbeRef: ActorRef): Props =
    Props(new NeboArrivalActor(redListPassengers, () => SDate("2017-10-25T00:00:00Z"), Option(SDate("2017-10-25T00:00:00Z").millisSinceEpoch)) {
      override val maybeSnapshotInterval = Option(1)

      override def receiveRecover: Receive = {
        case SnapshotOffer(md, ss) =>
          testProbeRef ! "snapshotOffer"
          logSnapshotOffer(md)
          playSnapshotMessage(ss)

        case RecoveryCompleted =>
          testProbeRef ! "recoveryCompleted"
          postRecoveryComplete()

        case event: GeneratedMessage =>
          Try {
            testProbeRef ! "event-playRecoveryMessage"
            bytesSinceSnapshotCounter += event.serializedSize
            messagesPersistedSinceSnapshotCounter += 1
            playRecoveryMessage(event)
          } match {
            case Failure(exception) =>
              log.error(s"Failed to reply $event", exception)
            case _ =>
          }
      }

      override def receiveCommand: Receive = {
        case redListPassengers: RedListPassengers =>
          val arrivalKey = getRedListPassengerFlightKey(redListPassengers)
          state = NeboArrivals(state.urns ++ redListPassengers.urns.toSet)
          val replyToAndMessage = Option((testProbeRef, "replyToAndMessage"))
          persistAndMaybeSnapshotWithAck(redListPassengersToNeboArrivalMessage(redListPassengers), replyToAndMessage)
          log.info(s"Update arrivalKey $arrivalKey")
          testProbeRef ! "receiveCommand"
          sender() ! state

        case GetState =>
          log.debug(s"Received GetState")
          testProbeRef ! "getState"
          sender() ! state

        case _: SaveSnapshotSuccess =>
          testProbeRef ! "saveSnapshotSuccess"
          ackIfRequired()

        case SaveSnapshotFailure(md, cause) =>
          testProbeRef ! "SaveSnapshotFailure"
          log.error(s"Save snapshot failure: $md", cause)

        case m => log.warn(s"Got unexpected message: $m")
      }

      override def takeSnapshot(stateToSnapshot: GeneratedMessage): Unit = {
        log.debug(s"Snapshotting ${stateToSnapshot.serializedSize} bytes of ${stateToSnapshot.getClass}. Resetting counters to zero")
        saveSnapshot(stateToSnapshot)
        bytesSinceSnapshotCounter = 0
        messagesPersistedSinceSnapshotCounter = 0
        postSaveSnapshot()
        testProbeRef ! "takeSnapshot"
      }

      override def processSnapshotMessage: PartialFunction[Any, Unit] = {
        case snapshot: NeboArrivalSnapshotMessage =>
          state = snapshotMessageToNeboArrival(snapshot)
          testProbeRef ! "processSnapshotMessage"
      }
    })

}

class NeboArrivalActorSpec extends CrunchTestLike with ImplicitSender {
  sequential
  isolated

  "A flight of a port from nebo file has all of red list country passengers urns combine from different set" >> {
    val urnFirstSet = RandomString.getNRandomString(5, 10)
    val urnSecondSet = RandomString.getNRandomString(1, 10)
    val redListPassengers = RedListPassengers("abc", PortCode("ab"), SDate("2017-10-25T00:00:00Z"), urnFirstSet)

    val neboArrivalActor: ActorRef = system.actorOf(NeboArrivalActor.props(redListPassengers, () => SDate("2017-10-25T00:00:00Z")))
    val neboArrivals = Await.result(neboArrivalActor.ask(redListPassengers).mapTo[NeboArrivals], 2 seconds)
    val newNeboArrivalActor: ActorRef = system.actorOf(NeboArrivalActor.props(redListPassengers, () => SDate("2017-10-25T00:00:00Z")))

    val neboArrivalsCombined = Await.result(newNeboArrivalActor
      .ask(redListPassengers.copy(urns = urnSecondSet))
      .mapTo[NeboArrivals], 2 seconds)
    neboArrivals.urns === urnFirstSet.toSet
    neboArrivalsCombined.urns === urnFirstSet.toSet ++ urnSecondSet.toSet
  }

  "A flight of a port from nebo file with a red list country passenger urns are persisted and recovered" >> {

    val urns = RandomString.getNRandomString(5, 10)
    val redListPassengers = RedListPassengers("abc", PortCode("ab"), SDate("2017-10-25T00:00:00Z"), urns)
    val neboArrivalActor: ActorRef = system.actorOf(NeboArrivalActor.props(redListPassengers, () => SDate("2017-10-25T00:00:00Z")))

    //sending the red list passengers to persist which test serialisation
    neboArrivalActor.ask(redListPassengers)

    //using new actor to get actor state and test deSerialisation
    val newNeboArrivalActor: ActorRef = system.actorOf(NeboArrivalActor.props(redListPassengers, () => SDate("2017-10-25T00:00:00Z")))

    val neboArrivals: NeboArrivals = Await.result(newNeboArrivalActor.ask(GetState).mapTo[NeboArrivals], 15 seconds)
    neboArrivals.urns === urns.toSet
  }

  "Events for NeboArrivalActor to be happen as expected while persisting and recovering" >> {
    val urns = RandomString.getNRandomString(5, 10)

    val redListPassengers = RedListPassengers("abc", PortCode("ab"), SDate("2017-10-25T00:00:00Z"), urns)

    val probe: TestProbe = TestProbe()

    val actor = system.actorOf(NeboArrivalActorTest.props(redListPassengers, () => SDate("2017-10-25T00:00:00Z"), probe.ref))

    val neboArrivals: NeboArrivals = Await.result(actor.ask(redListPassengers).mapTo[NeboArrivals], 5 seconds)
    probe.expectMsg(10 seconds, "recoveryCompleted")
    probe.expectMsg(10 seconds, "receiveCommand")
    probe.expectMsg(10 seconds, "takeSnapshot")
    probe.expectMsg(10 seconds, "saveSnapshotSuccess")
    probe.expectMsg(10 seconds, "replyToAndMessage")

    val newProbe: TestProbe = TestProbe()
    val newNeboArrivalActor: ActorRef = system.actorOf(NeboArrivalActorTest.props(redListPassengers, () => SDate("2017-10-25T00:00:00Z"), newProbe.ref))

    val neboArrivalsNew: NeboArrivals = Await.result(newNeboArrivalActor.ask(GetState).mapTo[NeboArrivals], 5 seconds)
    newProbe.expectMsg(10 seconds, "event-playRecoveryMessage")
    newProbe.expectMsg(10 seconds, "recoveryCompleted")
    newProbe.expectMsg(10 seconds, "getState")

    neboArrivals.urns === urns.toSet
    neboArrivalsNew.urns === urns.toSet
  }

  "Conversion check for NeboArrivalsMessage from protobuf to object and vice versa" >> {
    val urns = RandomString.getNRandomString(5, 10)

    val redListPassengers = RedListPassengers("abc", PortCode("ab"), SDate("2017-10-25T00:00:00Z"), urns)

    val neboArrivals = NeboArrivals(redListPassengers.urns.toSet)

    val neboArrivalMessage = NeboArrivalMessageConversion.redListPassengersToNeboArrivalMessage(redListPassengers)

    val resultNeboArrival = NeboArrivalMessageConversion.messageToNeboArrival(neboArrivalMessage)

    resultNeboArrival === neboArrivals
  }

  "Conversion check for NeboArrivalSnapshotMessage from protobuf to object and vice versa" >> {
    val urns = RandomString.getNRandomString(5, 10)

    val redListPassengers = RedListPassengers("abc", PortCode("ab"), SDate("2017-10-25T00:00:00Z"), urns)

    val neboArrivals = NeboArrivals(redListPassengers.urns.toSet)

    val neboArrivalSnapshotMessage = NeboArrivalMessageConversion.stateToNeboArrivalSnapshotMessage(neboArrivals)

    val resultNeboArrival = NeboArrivalMessageConversion.snapshotMessageToNeboArrival(neboArrivalSnapshotMessage)

    resultNeboArrival === neboArrivals
  }
}
