package services.crunch

import controllers.ArrivalGenerator
import drt.server.feeds.ArrivalsFeedSuccess
import drt.shared.FlightsApi.Flights
import drt.shared._
import services.graphstages.CrunchMocks
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.time.SDate

import scala.concurrent.duration._


class ForecastBaseArrivalsSpec extends CrunchTestLike {
  "Given some existing base flights for yesterday" >> {
    "When I import a new base set of arrivals that do not include yesterday's flights" >> {
      "I should still see yesterday's flights in the port state" >> {
        val yesterdayScheduled = "2023-03-22T12:00Z"
        val todayScheduled = "2023-03-22T12:00Z"
        val today1812 = "2023-03-22T18:12Z"

        val baseArrivalYesterday = ArrivalGenerator.arrival(schDt = yesterdayScheduled, iata = "BA0001", terminal = T1, actPax = Option(21))
        val yesterdayArrivals = Iterable(ApiFlightWithSplits(baseArrivalYesterday, Set()))

        val crunch = runCrunchGraph(TestConfig(
          now = () => SDate(today1812),
          maxDaysToCrunch = 4,
          cruncher = CrunchMocks.mockCrunchWholePax,
          initialPortState = Option(PortState(yesterdayArrivals, Iterable(), Iterable()))
        ))

        val baseArrivalToday = ArrivalGenerator.arrival(schDt = todayScheduled, iata = "BA0001", terminal = T1, actPax = Option(21))
        val baseFlightsToday = Flights(List(baseArrivalToday))

        offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(baseFlightsToday))

        crunch.portStateTestProbe.fishForMessage(10.seconds) {
          case PortState(flights, _, _) =>
            flights.contains(baseArrivalYesterday.unique) && flights.contains(baseArrivalToday.unique)
        }

        success
      }
    }
  }
}
