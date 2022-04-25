package drt.server.feeds.stn

import org.specs2.mutable.Specification
import services.SDate
import services.graphstages.Crunch
import uk.gov.homeoffice.drt.arrivals.{Arrival, ArrivalStatus, CarrierCode, VoyageNumber}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{ForecastFeedSource, PortCode}

class STNForecastXLSExtractorSpec extends Specification {

  "Given an excel file with the STN forecast format for BST flights then I should get forecast flights for terminal" >> {
    val path = getClass.getClassLoader.getResource("STN_Forecast_Fixture.xlsx").getPath

    val result = STNForecastXLSExtractor
      .rows(path)
      .map(r =>
        (r.scheduledDate.millisSinceEpoch, r.flightCode, r.origin, r.internationalDomestic, r.totalPax)
      )
      .toSet


    val expected = Set(
      (SDate("2020-08-03T23:00").millisSinceEpoch, "TST1001", "AGP", "INTERNATIONAL", 50),
      (SDate("2020-08-04T00:15").millisSinceEpoch, "TST1002", "DLM", "INTERNATIONAL", 50),
      (SDate("2020-08-04T06:05").millisSinceEpoch, "TST1003", "CGN", "INTERNATIONAL", 50),
      (SDate("2020-08-04T06:20").millisSinceEpoch, "TST1005", "SXF", "INTERNATIONAL", 50)
    )

    result === expected
  }

  "Given an excel file with the STN forecast format for BST flights then I should get Arrival flights for terminal" >> {
    val path = getClass.getClassLoader.getResource("STN_Forecast_Fixture.xlsx").getPath

    val result = STNForecastXLSExtractor(path)

    val expected = List(
      Arrival(
        Operator = None,
        CarrierCode = CarrierCode("TST"),
        VoyageNumber = VoyageNumber(1001),
        FlightCodeSuffix = None,
        Status = ArrivalStatus("Port Forecast"),
        Estimated = None,
        PredictedTouchdown = None,
        Actual = None,
        EstimatedChox = None,
        ActualChox = None,
        Gate = None,
        Stand = None,
        MaxPax = Some(100),
        ActPax = Some(50),
        TranPax = Some(0),
        RunwayID = None,
        BaggageReclaimId = None,
        AirportID = PortCode("STN"),
        Terminal = Terminal("T1"),
        Origin = PortCode("AGP"),
        Scheduled = SDate("2020-08-03T23:00").millisSinceEpoch,
        PcpTime = None,
        FeedSources = Set(ForecastFeedSource),
        CarrierScheduled = None,
        ApiPax = None,
        ScheduledDeparture = None,
        RedListPax = None),
      Arrival(
        Operator = None,
        CarrierCode = CarrierCode("TST"),
        VoyageNumber = VoyageNumber(1002),
        FlightCodeSuffix = None,
        Status = ArrivalStatus("Port Forecast"),
        Estimated = None,
        PredictedTouchdown = None,
        Actual = None,
        EstimatedChox = None,
        ActualChox = None,
        Gate = None,
        Stand = None,
        MaxPax = Some(100),
        ActPax = Some(50),
        TranPax = Some(0),
        RunwayID = None,
        BaggageReclaimId = None,
        AirportID = PortCode("STN"),
        Terminal = Terminal("T1"),
        Origin = PortCode("DLM"),
        Scheduled = SDate("2020-08-04T00:15").millisSinceEpoch,
        PcpTime = None,
        FeedSources = Set(ForecastFeedSource),
        CarrierScheduled = None,
        ApiPax = None,
        ScheduledDeparture = None,
        RedListPax = None),
      Arrival(
        Operator = None,
        CarrierCode = CarrierCode("TST"),
        VoyageNumber = VoyageNumber(1003),
        FlightCodeSuffix = None,
        Status = ArrivalStatus("Port Forecast"),
        Estimated = None,
        PredictedTouchdown = None,
        Actual = None,
        EstimatedChox = None,
        ActualChox = None,
        Gate = None,
        Stand = None,
        MaxPax = Some(100),
        ActPax = Some(50),
        TranPax = Some(0),
        RunwayID = None,
        BaggageReclaimId = None,
        AirportID = PortCode("STN"),
        Terminal = Terminal("T1"),
        Origin = PortCode("CGN"),
        Scheduled = SDate("2020-08-04T06:05").millisSinceEpoch,
        PcpTime = None,
        FeedSources = Set(ForecastFeedSource),
        CarrierScheduled = None,
        ApiPax = None,
        ScheduledDeparture = None,
        RedListPax = None),
      Arrival(
        Operator = None,
        CarrierCode = CarrierCode("TST"),
        VoyageNumber = VoyageNumber(1005),
        FlightCodeSuffix = None,
        Status = ArrivalStatus("Port Forecast"),
        Estimated = None,
        PredictedTouchdown = None,
        Actual = None,
        EstimatedChox = None,
        ActualChox = None,
        Gate = None,
        Stand = None,
        MaxPax = Some(100),
        ActPax = Some(50),
        TranPax = Some(0),
        RunwayID = None,
        BaggageReclaimId = None,
        AirportID = PortCode("STN"),
        Terminal = Terminal("T1"),
        Origin = PortCode("SXF"),
        Scheduled = SDate("2020-08-04T06:20").millisSinceEpoch,
        PcpTime = None,
        FeedSources = Set(ForecastFeedSource),
        CarrierScheduled = None,
        ApiPax = None,
        ScheduledDeparture = None,
        RedListPax = None))

    result === expected
  }

}
