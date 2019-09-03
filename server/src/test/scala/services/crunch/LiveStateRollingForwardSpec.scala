package services.crunch

import actors.GetState
import akka.pattern.AskableActorRef
import controllers.ArrivalGenerator
import drt.shared.CrunchApi.PortState
import drt.shared.FlightsApi.Flights
import drt.shared.SDateLike
import server.feeds.ArrivalsFeedSuccess
import services.SDate

import scala.concurrent.Await
import scala.concurrent.duration._

class LiveStateRollingForwardSpec extends CrunchTestLike {
  var nowDate: SDateLike = SDate("2019-01-01")
  val myNow: () => SDateLike = () => nowDate

  "Given a flight that applies to the few minutes after the live state window, ie in forecast state only " +
    "Followed by an updated after midnight that brings the previous update into live state scope " +
    "When I probe the port state " +
    "I should see the first update's flight in live, and both flights in forecast" >> {
    val tuesday = "2019-01-01T00:00"
    val wednesday = "2019-01-02T00:00"

    val fridayMidnight30 = "2019-01-04T00:30"
    val saturdayMidnight30 = "2019-01-05T00:30"

    val futureArrival = ArrivalGenerator.arrival(iata = "BA0001", origin = "JFK", schDt = fridayMidnight30, terminal = "T1", actPax = Option(100))
    val futureArrival2 = ArrivalGenerator.arrival(iata = "BA0002", origin = "JFK", schDt = saturdayMidnight30, terminal = "T1", actPax = Option(200))

    nowDate = SDate(tuesday)

    val crunch = runCrunchGraph(now = myNow)

    offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Flights(Seq(futureArrival))))

    crunch.forecastTestProbe.fishForMessage(2 seconds) {
      case ps: PortState => ps.flights.contains(futureArrival.uniqueId)
    }
    val askableLiveActor: AskableActorRef = crunch.liveCrunchActor
    val livePsDay1 = Await.result(askableLiveActor.ask(GetState)(2 seconds), 2 seconds).asInstanceOf[Option[PortState]]

    nowDate = SDate(wednesday)
    offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Flights(Seq(futureArrival2))))

    crunch.liveTestProbe.fishForMessage(2 seconds) {
      case ps: PortState => ps.flights.contains(futureArrival.uniqueId)
    }

    crunch.forecastTestProbe.fishForMessage(2 seconds) {
      case ps: PortState =>
        ps.flights.contains(futureArrival.uniqueId) && ps.flights.contains(futureArrival2.uniqueId)
    }

    val day1LiveFlightsEmpty = livePsDay1.get.flights.isEmpty

    day1LiveFlightsEmpty
  }
}
