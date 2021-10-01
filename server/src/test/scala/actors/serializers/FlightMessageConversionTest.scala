package actors.serializers

import uk.gov.homeoffice.drt.ports.Terminals.T1
import drt.shared.api.{Arrival, FlightCodeSuffix}
import drt.shared._
import org.specs2.mutable.Specification

class FlightMessageConversionTest extends Specification {
  "FlightMessageConversionTest" should {
    "Convert an arrival to a message and back again without data loss" in {
      val arrival = Arrival(
        Operator = Option(Operator("British Airways")),
        Status = ArrivalStatus("Delayed"),
        Estimated = Option(1L),
        CarrierCode = CarrierCode("BA"),
        VoyageNumber = VoyageNumber(1),
        FlightCodeSuffix = Option(FlightCodeSuffix("G")),
        Actual = Option(2L),
        EstimatedChox = Option(3L),
        ActualChox = Option(4L),
        Gate = Option("A"),
        Stand = Option("A1"),
        MaxPax = Option(101),
        TranPax = Option(5),
        ActPax = Option(95),
        RunwayID = Option("1"),
        BaggageReclaimId = Option("abc"),
        AirportID = PortCode("LHR"),
        Terminal = T1,
        Origin = PortCode("CDG"),
        Scheduled = 5L,
        PcpTime = Option(6L),
        FeedSources = Set(LiveFeedSource, AclFeedSource, ForecastFeedSource, LiveBaseFeedSource, ApiFeedSource),
        CarrierScheduled = Option(7L),
        ApiPax = Option(96),
        ScheduledDeparture = Option(8L),
        RedListPax = Option(26)
      )
      val msg = FlightMessageConversion.apiFlightToFlightMessage(arrival)
      val recovered = FlightMessageConversion.flightMessageToApiFlight(msg)

      recovered === arrival
    }
  }
}
