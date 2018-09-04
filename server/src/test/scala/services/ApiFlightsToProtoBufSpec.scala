package services

import drt.shared.{ApiFeed, Arrival, MilliDate, SDateLike}
import org.specs2.mutable.Specification
import server.protobuf.messages.FlightsMessage.FlightMessage
import actors.FlightMessageConversion._

class ApiFlightsToProtoBufSpec extends Specification {

  "apiFlightToFlightMessage" should {
    "take a single Arrival and return a FlightMessage representing it" in {
      val apiFlight = Arrival(
        Operator = Some("Op"),
        Status = "scheduled",
        Estimated = Some(SDate("2016-01-01T13:05:00Z").millisSinceEpoch),
        Actual = Some(SDate("2016-01-01T13:10:00Z").millisSinceEpoch),
        EstimatedChox = Some(SDate("2016-01-01T13:15:00Z").millisSinceEpoch),
        ActualChox = Some(SDate("2016-01-01T13:20:00Z").millisSinceEpoch),
        Gate = Some("10"),
        Stand = Some("10A"),
        MaxPax = Some(200),
        ActPax = Some(150),
        TranPax = Some(10),
        RunwayID = Some("1"),
        BaggageReclaimId = Some("A"),
        FlightID = Some(1000),
        AirportID = "LHR",
        Terminal = "T2",
        rawICAO = "BA0001",
        rawIATA = "BAA0001",
        Origin = "JFK",
        PcpTime = Some(1451655000000L), // 2016-01-01 13:30:00 UTC
        Scheduled = SDate("2016-01-01T13:00:00Z").millisSinceEpoch,
        FeedSources = Set(ApiFeed)
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
        feedSources = Seq("ApiFeed"),
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
