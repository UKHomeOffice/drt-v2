package actors

import drt.shared.Terminals.T1
import drt.shared.api.{Arrival, FlightCodeSuffix}
import drt.shared.{AclFeedSource, ApiFeedSource, FeedSource, LiveFeedSource, Operator, PortCode}
import org.specs2.mutable.Specification
import server.protobuf.messages.FlightsMessage.FlightMessage

class FlightMessageConversionSpec extends Specification {

  import drt.server.feeds.Implicits._

  val arrival = Arrival(
    Operator = Option(Operator("BA")),
    Status = "landed",
    Estimated = Option(2L),
    Actual = Option(3L),
    EstimatedChox = Option(4L),
    ActualChox = Option(5L),
    Gate = Option("G1"),
    Stand = Option("S1"),
    MaxPax = Option(350),
    ActPax = Option(122),
    TranPax = Option(10),
    RunwayID = Option("R1"),
    BaggageReclaimId = Option("B1"),
    AirportID = PortCode("LHR"),
    Terminal = T1,
    rawICAO = "BAA1111",
    rawIATA = "BA1111",
    Origin = PortCode("JFK"),
    Scheduled = 1L,
    PcpTime = Option(10L),
    FeedSources = Set(AclFeedSource, LiveFeedSource),
    CarrierScheduled = Option(4L)
    )

  "Given an Arrival with no suffix" >> {
    "When I convert it to a protobuf message and then back to an Arrival" >> {
      val arrivalMessage = FlightMessageConversion.apiFlightToFlightMessage(arrival)
      val restoredArrival = FlightMessageConversion.flightMessageToApiFlight(arrivalMessage)
      "Then the converted Arrival should match the original" >> {
        restoredArrival === arrival
      }
    }
  }

  "Given an Arrival with a suffix" >> {
    val arrivalWithSuffix = arrival.copy(FlightCodeSuffix = Option(FlightCodeSuffix("P")))
    "When I convert it to a protobuf message and then back to an Arrival" >> {
      val arrivalMessage = FlightMessageConversion.apiFlightToFlightMessage(arrivalWithSuffix)
      val restoredArrival = FlightMessageConversion.flightMessageToApiFlight(arrivalMessage)
      "Then the converted Arrival should match the original" >> {
        restoredArrival === arrivalWithSuffix
      }
    }
  }

  "Given an arrival with 0 Passengers" >> {
    val arrivalWith0Pax = arrival.copy(ActPax = Option(0), TranPax = Option(0), MaxPax = Option(0))
    "When I convert it to a protobuf message and then back to an Arrival" >> {
      val arrivalMessage = FlightMessageConversion.apiFlightToFlightMessage(arrivalWith0Pax)
      val restoredArrival = FlightMessageConversion.flightMessageToApiFlight(arrivalMessage)
      "Then the converted Arrival should match the original" >> {
        restoredArrival === arrivalWith0Pax
      }
    }
  }
}
