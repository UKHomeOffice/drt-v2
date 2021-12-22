package actors.persistent.nebo

import actors.persistent.staffing.GetState
import actors.serializers.NeboArrivalMessageConversion
import akka.actor.{ActorRef, PoisonPill, Props}
import akka.pattern.ask
import akka.persistence.{RecoveryCompleted, SaveSnapshotSuccess, SnapshotOffer}
import akka.testkit.{ImplicitSender, TestProbe}
import com.typesafe.config.ConfigFactory
import drt.shared.{NeboArrivals, RedListPassengers, SDateLike}
import org.specs2.specification.BeforeEach
import services.SDate
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.ports.PortCode
import util.RandomString

import java.io.File
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt


object NeboArrivalActorTest {

  def props(redListPassengers: RedListPassengers, now: () => SDateLike, testProbeRef: ActorRef): Props =
    Props(new NeboArrivalActor(redListPassengers, () => SDate("2017-10-25T00:00:00Z"), None) {
      override val maybeSnapshotInterval: Option[Int] = Option(1)

      override def receiveCommand: PartialFunction[Any, Unit] = {
        case incoming =>
          testProbeRef ! incoming
          super.receiveCommand(incoming)
      }

      override def receiveRecover: PartialFunction[Any, Unit] = {
        case incoming =>
          testProbeRef ! incoming
          super.receiveRecover(incoming)
      }
    })
}

class NeboArrivalActorSpec extends CrunchTestLike with ImplicitSender with BeforeEach {
  sequential
  isolated

  override def before(): Unit = cleanupSnapshotFiles()

  "A flight of a port from nebo file has all of red list country passengers urns combine from different set" >> {
    val urnFirstSet = RandomString.getNRandomString(5, 10)
    val urnSecondSet = RandomString.getNRandomString(1, 10)
    val redListPassengers = RedListPassengers("abc", PortCode("ab"), SDate("2017-10-25T00:00:00Z"), urnFirstSet)

    val neboArrivalActor: ActorRef = system.actorOf(NeboArrivalActor.props(redListPassengers, () => SDate("2017-10-25T00:00:00Z")))
    val neboArrivals = Await.result(neboArrivalActor.ask(redListPassengers).mapTo[NeboArrivals], 1.seconds)
    val newNeboArrivalActor: ActorRef = system.actorOf(NeboArrivalActor.props(redListPassengers, () => SDate("2017-10-25T00:00:00Z")))

    val neboArrivalsCombined = Await.result(newNeboArrivalActor
      .ask(redListPassengers.copy(urns = urnSecondSet))
      .mapTo[NeboArrivals], 1.seconds)
    neboArrivals.urns === urnFirstSet.toSet
    neboArrivalsCombined.urns === urnFirstSet.toSet ++ urnSecondSet.toSet
  }

  "A flight of a port from nebo file with a red list country passenger urns are persisted and recovered" >> {

    val urns = RandomString.getNRandomString(5, 10)
    val redListPassengers = RedListPassengers("abc", PortCode("ab"), SDate("2017-10-25T00:00:00Z"), urns)
    val neboArrivalActor: ActorRef = system.actorOf(NeboArrivalActor.props(redListPassengers, () => SDate("2017-10-25T00:00:00Z")))

    //sending the red list passengers to persist which test serialisation
    Await.ready(neboArrivalActor.ask(redListPassengers), 1.second)

    //using new actor to get actor state and test deSerialisation
    val newNeboArrivalActor: ActorRef = system.actorOf(NeboArrivalActor.props(redListPassengers, () => SDate("2017-10-25T00:00:00Z")))

    val neboArrivals: NeboArrivals = Await.result(newNeboArrivalActor.ask(GetState).mapTo[NeboArrivals], 1.seconds)
    neboArrivals.urns === urns.toSet
  }

  "Events for NeboArrivalActor to be happen as expected while persisting and recovering" >> {
    val urns = RandomString.getNRandomString(5, 10)
    val urns2 = RandomString.getNRandomString(5, 10)

    val redListPassengers = RedListPassengers("abc", PortCode("ab"), SDate("2017-10-25T00:00:00Z"), urns)
    val redListPassengers2 = RedListPassengers("abc", PortCode("ab"), SDate("2017-10-25T00:00:00Z"), urns2)

    val probe: TestProbe = TestProbe()

    val actor = system.actorOf(NeboArrivalActorTest.props(redListPassengers, () => SDate("2017-10-25T00:00:00Z"), probe.ref))

    actor.ask(redListPassengers).flatMap(_ => actor.ask(redListPassengers2))
    probe.expectMsgClass(1.second, classOf[RecoveryCompleted])
    probe.expectMsgClass(1.second, classOf[RedListPassengers])
    probe.expectMsgClass(1.second, classOf[RedListPassengers])
    probe.expectMsgClass(1.second, classOf[SaveSnapshotSuccess])
    actor ! PoisonPill

    val newProbe: TestProbe = TestProbe()
    val newNeboArrivalActor: ActorRef = system.actorOf(NeboArrivalActorTest.props(redListPassengers, () => SDate("2017-10-25T00:00:00Z"), newProbe.ref))

    newProbe.expectMsgClass(1.second, classOf[SnapshotOffer])
    newProbe.expectMsgClass(1.second, classOf[RecoveryCompleted])

    val neboArrivalsNew: NeboArrivals = Await.result(newNeboArrivalActor.ask(GetState).mapTo[NeboArrivals], 1.seconds)

    neboArrivalsNew.urns === (urns ++ urns2).toSet
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

  private def cleanupSnapshotFiles(): Unit = {
    val config = ConfigFactory.load()
    val file = new File(config.getString("akka.persistence.snapshot-store.local.dir"))
    file.listFiles().map { file =>
      log.info(s"deleting snapshot file ${file.getName}")
      file.delete()
    }
  }
}
