package services.crunch

import actors.GetState
import akka.pattern.AskableActorRef
import akka.testkit.TestProbe
import controllers.ArrivalGenerator
import drt.shared.CrunchApi.PortState
import drt.shared.FlightsApi.Flights
import drt.shared.{Arrival, SDateLike}
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

    stateContainsArrivals(crunch.forecastTestProbe, Seq(futureArrival))

    val day1LiveFlightsEmpty = getState(crunch.liveCrunchActor).flights.isEmpty

    nowDate = SDate(wednesday)
    offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Flights(Seq(futureArrival2))))

    stateContainsArrivals(crunch.liveTestProbe, Seq(futureArrival))
    stateContainsArrivals(crunch.forecastTestProbe, Seq(futureArrival, futureArrival2))

    day1LiveFlightsEmpty
  }

  "Given a flight that applies to the few minutes after the live state window, ie in forecast state only " +
    "Followed by another update that applies to the few minutes after the live state window  " +
    "When I probe the port state " +
    "I should still see no flights in live, but both flights in forecast" >> {
    val tuesday = "2019-01-01T00:00"

    val fridayMidnight30 = "2019-01-04T00:30"
    val saturdayMidnight30 = "2019-01-05T00:30"

    val futureArrival = ArrivalGenerator.arrival(iata = "BA0001", origin = "JFK", schDt = fridayMidnight30, terminal = "T1", actPax = Option(100))
    val futureArrival2 = ArrivalGenerator.arrival(iata = "BA0002", origin = "JFK", schDt = saturdayMidnight30, terminal = "T1", actPax = Option(200))

    nowDate = SDate(tuesday)

    val crunch = runCrunchGraph(now = myNow)

    offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Flights(Seq(futureArrival))))

    stateContainsArrivals(crunch.forecastTestProbe, Seq(futureArrival))

    offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Flights(Seq(futureArrival2))))

    stateContainsArrivals(crunch.forecastTestProbe, Seq(futureArrival, futureArrival2))
    val day1LiveFlightsEmpty = getState(crunch.liveCrunchActor).flights.isEmpty

    day1LiveFlightsEmpty
  }

  private def getState(actor: AskableActorRef): PortState = {
    Await.result(actor.ask(GetState)(2 seconds), 2 seconds).asInstanceOf[Option[PortState]].get
  }

  private def stateContainsArrivals(probe: TestProbe, arrivals: Seq[Arrival]) = probe.fishForMessage(2 seconds) {
    case ps: PortState => arrivals.foldLeft(true) { case (soFar, a) => soFar && ps.flights.contains(a.unique) }
  }
}
