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
        SchDT = "2016-01-01T13:00",
        EstDT = "2016-01-01T13:05",
        ActDT = "2016-01-01T13:10",
        EstChoxDT = "2016-01-01T13:15",
        ActChoxDT = "2016-01-01T13:20",
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
        PcpTime = 1451655000000L // 2016-01-01 13:30:00 UTC
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

    "take a single v1 FlightMessage and return an Arrival representing it" in {
      val flightMessage = FlightMessage(
        operator = Some("Op"),
        schDTOLD = Some("2016-01-01T13:00"),
        actDTOLD = Some("2016-01-01T13:10"),
        estDTOLD = Some("2016-01-01T13:05"),
        estChoxDTOLD = Some("2016-01-01T13:15"),
        actChoxDTOLD = Some("2016-01-01T13:20"),
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
        pcpTime = Some(1451655000000L) // 2016-01-01 13:30:00 UTC
      )

      val apiFlight = flightMessageToApiFlight(flightMessage)

      val expected = Arrival(
        Operator = "Op",
        Status = "scheduled",
        SchDT = "2016-01-01T13:00",
        EstDT = "2016-01-01T13:05",
        ActDT = "2016-01-01T13:10",
        EstChoxDT = "2016-01-01T13:15",
        ActChoxDT = "2016-01-01T13:20",
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
        PcpTime = 1451655000000L // 2016-01-01 13:30:00 UTC
      )

      apiFlight === expected
    }

    "take a single v1 FlightMessage with missing datetimes and return an Arrival representing it" in {
      val flightMessage = FlightMessage(
        operator = Some("Op"),
        schDTOLD = Some("2016-01-01T13:00"),
        actDTOLD = Some("2016-01-01T13:10"),
        estDTOLD = Some("2016-01-01T13:05"),
        estChoxDTOLD = Some("2016-01-01T13:15"),
        actChoxDTOLD = Some("2016-01-01T13:20"),
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
        pcpTime = Some(1451655000000L) // 2016-01-01 13:30:00 UTC
      )

      val apiFlight = flightMessageToApiFlight(flightMessage)

      val expected = Arrival(
        Operator = "Op",
        Status = "scheduled",
        SchDT = "2016-01-01T13:00",
        EstDT = "2016-01-01T13:05",
        ActDT = "2016-01-01T13:10",
        EstChoxDT = "2016-01-01T13:15",
        ActChoxDT = "2016-01-01T13:20",
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
        PcpTime = 1451655000000L // 2016-01-01 13:30:00 UTC
      )

      apiFlight === expected
    }

    "take a single v2 FlightMessage and return an Arrival representing it" in {
      val flightMessage = FlightMessage(
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

      val apiFlight = flightMessageToApiFlight(flightMessage)

      val expected = Arrival(
        Operator = "Op",
        Status = "scheduled",
        SchDT = "2016-01-01T13:00",
        EstDT = "2016-01-01T13:05",
        ActDT = "2016-01-01T13:10",
        EstChoxDT = "2016-01-01T13:15",
        ActChoxDT = "2016-01-01T13:20",
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
        PcpTime = 1451655000000L // 2016-01-01 13:30:00 UTC
      )

      apiFlight === expected
    }

    "take a single v2 FlightMessage with missing datetimes and return an Arrival representing it" in {
      val flightMessage = FlightMessage(
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
        touchdown = None,
        estimatedChox = None,
        actualChox = None
      )

      val apiFlight = flightMessageToApiFlight(flightMessage)

      val expected = Arrival(
        Operator = "Op",
        Status = "scheduled",
        SchDT = "2016-01-01T13:00",
        EstDT = "2016-01-01T13:05",
        ActDT = "",
        EstChoxDT = "",
        ActChoxDT = "",
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
        PcpTime = 1451655000000L // 2016-01-01 13:30:00 UTC
      )

      apiFlight === expected
    }
  }
}
