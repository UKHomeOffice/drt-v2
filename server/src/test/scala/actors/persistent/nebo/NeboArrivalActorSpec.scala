package actors.persistent.nebo

import akka.actor.ActorRef
import akka.pattern.ask
import drt.shared.{NeboArrivals, RedListPassengers}
import services.SDate
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.ports.PortCode
import util.RandomString

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class NeboArrivalActorSpec extends CrunchTestLike {

  "A flight of a port from nebo file has all of red list country passengers urns combine from different set" >> {
    val urnFirstSet = RandomString.getNRandomString(5, 10)
    val urnSecondSet = RandomString.getNRandomString(1, 10)
    val redListPassengers = RedListPassengers("abc", PortCode("ab"), SDate("2017-10-25T00:00:00Z"), urnFirstSet)

    val neboArrivalActor: ActorRef = system.actorOf(NeboArrivalActor.props(redListPassengers, () => SDate("2017-10-25T00:00:00Z")))
    val neboArrivals = Await.result(neboArrivalActor.ask(redListPassengers).mapTo[NeboArrivals].mapTo[NeboArrivals], 2 seconds)
    val neboArrivalsCombined = Await.result(neboArrivalActor
      .ask(redListPassengers.copy(urns = urnSecondSet))
      .mapTo[NeboArrivals], 2 seconds)
    neboArrivals.urns === urnFirstSet.toSet
    neboArrivalsCombined.urns === urnFirstSet.toSet ++ urnSecondSet.toSet
  }
}
