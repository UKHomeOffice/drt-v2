package services

import drt.shared.{Arrival, MilliDate, SDateLike}
import org.specs2.mutable.Specification
import server.protobuf.messages.FlightsMessage.FlightMessage
import actors.FlightMessageConversion._

class ApiFlightsToProtoBufSpec extends Specification {

  "apiFlightToFlightMessage" should {
    "take a single Arrival and return a FlightMessage representing it" in {
      val apiFlight = Arrival(
        Operator = "Op",
        Status = "scheduled",
        Estimated = SDate("2016-01-01T13:05:00Z").millisSinceEpoch,
        Actual = SDate("2016-01-01T13:10:00Z").millisSinceEpoch,
        EstimatedChox = SDate("2016-01-01T13:15:00Z").millisSinceEpoch,
        ActualChox = SDate("2016-01-01T13:20:00Z").millisSinceEpoch,
        Gate = "10",
        Stand = "10A",
        MaxPax = 200,
        ActPax = 150,
        TranPax = 10,
        RunwayID = "1",
        BaggageReclaimId = "A",
        FlightID = 1000,
        AirportID = "LHR",
        Terminal = "T2",
        rawICAO = "BA0001",
        rawIATA = "BAA0001",
        Origin = "JFK",
        PcpTime = 1451655000000L, // 2016-01-01 13:30:00 UTC
        Scheduled = SDate("2016-01-01T13:00:00Z").millisSinceEpoch
      )
      val flightMessage = apiFlightToFlightMessage(apiFlight)

      val expected = FlightMessage(
        operator = Some("Op"),
        gate = Some("10"),
        stand = Some("10A"),
        status = Some("scheduled"),
        maxPax = Some(200),
        actPax = Some(150),
        tranPax = Some(10),
        runwayID = Some("1"),
        baggageReclaimId = Some("A"),
        flightID = Some(1000),
        airportID = Some("LHR"),
        terminal = Some("T2"),
        iCAO = Some("BA0001"),
        iATA = Some("BAA0001"),
        origin = Some("JFK"),
        pcpTime = Some(1451655000000L), // 2016-01-01 13:30:00 UTC
        scheduled = Some(1451653200000L), // 2016-01-01 13:00:00 UTC
        estimated = Some(1451653500000L), // 2016-01-01 13:05:00 UTC
        touchdown = Some(1451653800000L), // 2016-01-01 13:10:00 UTC
        estimatedChox = Some(1451654100000L), // 2016-01-01 13:15:00 UTC
        actualChox = Some(1451654400000L) // 2016-01-01 13:20:00 UTC
      )

      flightMessage === expected
    }
  }
}
