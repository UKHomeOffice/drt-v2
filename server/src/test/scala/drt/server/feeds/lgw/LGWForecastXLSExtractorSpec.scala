package drt.server.feeds.lgw

import org.specs2.mutable.Specification
import services.SDate
import uk.gov.homeoffice.drt.arrivals.{Arrival, ArrivalStatus, CarrierCode, VoyageNumber}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{ForecastFeedSource, PortCode}

class LGWForecastXLSExtractorSpec extends Specification {

  "Given an excel file with the LGW forecast format for UTC flights then I should get forecast flights for terminal" >> {
    val path = getClass.getClassLoader.getResource("LGW_Forecast_Fixture.xlsx").getPath

    val result = LGWForecastXLSExtractor
      .rows(path)
      .map(r =>
        (r.scheduledDate.millisSinceEpoch, r.flightCode, r.origin, r.internationalDomestic, r.totalPax, r.transferPax, r.terminal)
      )
      .toSet

    val expected = Set(
      (SDate("2021-10-22T08:30").millisSinceEpoch, "TA1001", "UVF", "I", 110, 0, "N"),
      (SDate("2021-10-22T06:30").millisSinceEpoch, "TA1002", "MRU", "I", 85, 0, "N"),
      (SDate("2021-10-22T10:40").millisSinceEpoch, "TA1003", "PLS", "I", 331, 0, "N"))

    result === expected
  }

  "Given an excel file with the LGW forecast format for UTC flights then I should get Arrival flights for terminal" >> {
    val path = getClass.getClassLoader.getResource("LGW_Forecast_Fixture.xlsx").getPath

    val result = LGWForecastXLSExtractor(path)
    val expected = List(
      Arrival(
        Operator = None,
        CarrierCode = CarrierCode("TA"),
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
        MaxPax = None,
        ActPax = Some(110),
        TranPax = Some(0),
        RunwayID = None,
        BaggageReclaimId = None,
        AirportID = PortCode("LGW"),
        Terminal = Terminal("N"),
        Origin = PortCode("UVF"),
        Scheduled = SDate("2021-10-22T08:30").millisSinceEpoch,
        PcpTime = None,
        FeedSources = Set(ForecastFeedSource),
        CarrierScheduled = None,
        ApiPax = None,
        ScheduledDeparture = None,
        RedListPax = None,
        TotalPax = Set.empty),
      Arrival(
        Operator = None,
        CarrierCode = CarrierCode("TA"),
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
        MaxPax = None,
        ActPax = Some(85),
        TranPax = Some(0),
        RunwayID = None,
        BaggageReclaimId = None,
        AirportID = PortCode("LGW"),
        Terminal = Terminal("N"),
        Origin = PortCode("MRU"),
        Scheduled = SDate("2021-10-22T06:30").millisSinceEpoch,
        PcpTime = None,
        FeedSources = Set(ForecastFeedSource),
        CarrierScheduled = None,
        ApiPax = None,
        ScheduledDeparture = None,
        RedListPax = None,
        TotalPax = Set.empty),
      Arrival(
        Operator = None,
        CarrierCode = CarrierCode("TA"),
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
        MaxPax = None,
        ActPax = Some(331),
        TranPax = Some(0),
        RunwayID = None,
        BaggageReclaimId = None,
        AirportID = PortCode("LGW"),
        Terminal = Terminal("N"),
        Origin = PortCode("PLS"),
        Scheduled = SDate("2021-10-22T10:40").millisSinceEpoch,
        PcpTime = None,
        FeedSources = Set(ForecastFeedSource),
        CarrierScheduled = None,
        ApiPax = None,
        ScheduledDeparture = None,
        RedListPax = None,
        TotalPax = Set.empty))
    result === expected
  }

  "Given an excel file for LGW forecast , start row is at line where Header `Date` is mentioned" >> {
    val path = getClass.getClassLoader.getResource("LGW_Forecast_Fixture.xlsx").getPath
    val sheet = LGWForecastXLSExtractor.getSheet(path)
    val startRow = LGWForecastXLSExtractor.getStartRow(sheet)
    startRow === 3
  }
}
