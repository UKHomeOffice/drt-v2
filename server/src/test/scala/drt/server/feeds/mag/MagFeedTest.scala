package drt.server.feeds.mag

import drt.server.feeds.mag.MagFeed.{FlightNumber, IataIcao, MagArrival}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.homeoffice.drt.arrivals.LiveArrival
import uk.gov.homeoffice.drt.ports.Terminals.T1

class MagFeedTest extends AnyWordSpec with Matchers {
  "A MagArrival" should {
    "be able to produce a LiveArrival" in {
      val magArrival = MagArrival(
        uri = "uri",
        operatingAirline = IataIcao("FR", "ABZ"),
        flightNumber = FlightNumber("FR", Option("1234")),
        departureAirport = IataIcao("JFK", "JFK"),
        arrivalAirport = IataIcao("LHR", "LHR"),
        arrivalDeparture = "A",
        domesticInternational = "International",
        flightType = "PAX",
        gate = Option(MagFeed.Gate("G", "1")),
        stand = Option(MagFeed.Stand(Option("S"), Option("2"), true, None, None)),
        passenger = MagFeed.Passenger(Option(100), Option(150), Option(50), Option(50)),
        onBlockTime = MagFeed.Timings("2021-01-01T00:00:00Z", Option("2021-01-01T00:05:00Z"), Option("2021-01-01T00:10:00Z")),
        touchDownTime = MagFeed.Timings("2021-01-01T00:05:00Z", Option("2021-01-01T00:10:00Z"), Option("2021-01-01T00:15:00Z")),
        arrivalDate = "2021-01-01",
        arrival = MagFeed.ArrivalDetails(IataIcao("LHR", "LHR"), "2021-01-01T00:00:00Z", Option("2021-01-01T00:05:00Z"), Option("2021-01-01T00:10:00Z"), Option("t1"), Option("g1")),
        flightStatus = "Landed",
      )

      MagFeed.toArrival(magArrival) should ===(
        LiveArrival(
          operator = Option("FR"),
          maxPax = Option(150),
          totalPax = Option(100),
          transPax = Option(50),
          terminal = T1,
          voyageNumber = 1234,
          carrierCode = "FR",
          flightCodeSuffix = None,
          origin = "JFK",
          scheduled = 1609459200000L,
          estimated = Option(1609459500000L),
          touchdown = Option(1609459800000L),
          estimatedChox = Option(1609459500000L),
          actualChox = Option(1609459800000L),
          status = "On Chocks",
          gate = Option("G"),
          stand = Option("S"),
          runway = None,
          baggageReclaim = None,
        )
      )
    }
  }
}
