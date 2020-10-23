package actors

import drt.shared.SplitRatiosNs.SplitSources
import drt.shared.FlightsApi.FlightsWithSplitsDiff
import drt.shared.SplitRatiosNs.SplitSources.Historical
import drt.shared.Terminals.T1
import drt.shared._
import drt.shared.api.{Arrival, FlightCodeSuffix}
import drt.shared.{AclFeedSource, ApiFeedSource, ApiFlightWithSplits, ApiPaxTypeAndQueueCount, FeedSource, LiveFeedSource, Nationality, Operator, PaxNumbers, PaxTypes, PortCode, Queues, Splits}
import org.specs2.mutable.Specification
import passengersplits.core.PassengerTypeCalculatorValues.CountryCodes
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

  "Given a flight with splits containing API Splits" >> {
    val fws = ApiFlightWithSplits(
      arrival,
      Set(
        Splits(
          Set(
            ApiPaxTypeAndQueueCount(
              PaxTypes.EeaMachineReadable,
              Queues.EeaDesk,
              10,
              Option(Map(
                Nationality("GBR") -> 8,
                Nationality("ITA") -> 2
              )),
              Option(Map(
                PaxAge(5) -> 5,
                PaxAge(32) -> 5
              ))
            )
          ),
          SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages,
          Option(EventType("DC")
        )
      )
    )
    )
    "When I convert it to a protobuf message and then back to an Arrival" >> {
      val fwsMessage = FlightMessageConversion.flightWithSplitsToMessage(fws)
      println(fwsMessage)
      val restoredFWS = FlightMessageConversion.flightWithSplitsFromMessage(fwsMessage)
      "Then the converted Arrival should match the original" >> {
        restoredFWS === fws
      }
    }
  }

  "Given a FlightsWithSplitsDiff" >> {
    val diff = FlightsWithSplitsDiff(
      List(ApiFlightWithSplits(arrival, Set(Splits(
        Set(
          ApiPaxTypeAndQueueCount(PaxTypes.EeaBelowEGateAge, Queues.EeaDesk, 1, None, None),
          ApiPaxTypeAndQueueCount(PaxTypes.EeaBelowEGateAge, Queues.EeaDesk, 1, None, None),
          ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 3, None, None),
          ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, 1, None, None)
        ),
        Historical,
        None,
        PaxNumbers
      )))), List(arrival.unique))
    "When I convert it to a protobuf message and then back to an FlightsWithSplitsDiff" >> {
      val diffMessage = FlightMessageConversion.flightWithSplitsDiffToMessage(diff)
      val restoredDiff = FlightMessageConversion.flightWithSplitsDiffFromMessage(diffMessage)
      "Then the converted FlightsWithSplitsDiff should match the original" >> {
        restoredDiff === diff
      }
    }
  }
}
