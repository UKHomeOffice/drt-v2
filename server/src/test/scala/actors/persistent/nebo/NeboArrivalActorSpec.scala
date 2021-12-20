package actors.persistent.nebo

import actors.persistent.staffing.GetState
import akka.actor.ActorRef
import akka.pattern.ask
import akka.testkit.ImplicitSender
import drt.shared.{NeboArrivals, RedListPassengers}
import services.SDate
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.ports.PortCode
import util.RandomString

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class NeboArrivalActorSpec extends CrunchTestLike with ImplicitSender {
  sequential
  isolated

  "A flight of a port from nebo file has all of red list country passengers urns combine from different set" >> {
    val urnFirstSet = RandomString.getNRandomString(5, 10)
    val urnSecondSet = RandomString.getNRandomString(1, 10)
    val redListPassengers = RedListPassengers("abc", PortCode("ab"), SDate("2017-10-25T00:00:00Z"), urnFirstSet)

    val neboArrivalActor: ActorRef = system.actorOf(NeboArrivalActor.props(redListPassengers, () => SDate("2017-10-25T00:00:00Z")))
    val neboArrivals = Await.result(neboArrivalActor.ask(redListPassengers).mapTo[NeboArrivals], 2 seconds)
    val neboArrivalsCombined = Await.result(neboArrivalActor
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

    val neboArrivals: NeboArrivals = Await.result(newNeboArrivalActor.ask(GetState).mapTo[NeboArrivals], 2 seconds)
    neboArrivals.urns === urns.toSet
  }
}
