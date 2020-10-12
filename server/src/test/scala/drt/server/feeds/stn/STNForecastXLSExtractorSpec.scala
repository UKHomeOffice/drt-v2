package drt.server.feeds.stn

import drt.shared.Terminals.Terminal
import drt.shared.api.Arrival
import drt.shared._
import org.specs2.mutable.Specification
import services.SDate

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
      (SDate("2020-08-04T00:00").millisSinceEpoch, "TST1001", "AGP", "INTERNATIONAL", 50),
      (SDate("2020-08-04T01:15").millisSinceEpoch, "TST1002", "DLM", "INTERNATIONAL", 50),
      (SDate("2020-08-04T07:05").millisSinceEpoch, "TST1003", "CGN", "INTERNATIONAL", 50),
      (SDate("2020-08-04T07:20").millisSinceEpoch, "TST1005", "SXF", "INTERNATIONAL", 50)

    )


    result === expected
  }

  "Given an excel file with the STN forecast format for BST flights then I should get Arrival flights for terminal" >> {
    val path = getClass.getClassLoader.getResource("STN_Forecast_Fixture.xlsx").getPath

    val result = STNForecastXLSExtractor(path)

    val expected = List(
      Arrival(None, CarrierCode("TST"), VoyageNumber(1001), None, ArrivalStatus("Port Forecast"), None, None, None, None, None, None, Some(100), Some(50), Some(0), None, None, PortCode("STN"), Terminal("T1"), PortCode("AGP"), SDate("2020-08-04T00:00").millisSinceEpoch, None, Set(ForecastFeedSource), None, None),
      Arrival(None, CarrierCode("TST"), VoyageNumber(1002), None, ArrivalStatus("Port Forecast"), None, None, None, None, None, None, Some(100), Some(50), Some(0), None, None, PortCode("STN"), Terminal("T1"), PortCode("DLM"), SDate("2020-08-04T01:15").millisSinceEpoch, None, Set(ForecastFeedSource), None, None),
      Arrival(None, CarrierCode("TST"), VoyageNumber(1003), None, ArrivalStatus("Port Forecast"), None, None, None, None, None, None, Some(100), Some(50), Some(0), None, None, PortCode("STN"), Terminal("T1"), PortCode("CGN"), SDate("2020-08-04T07:05").millisSinceEpoch, None, Set(ForecastFeedSource), None, None),
      Arrival(None, CarrierCode("TST"), VoyageNumber(1005), None, ArrivalStatus("Port Forecast"), None, None, None, None, None, None, Some(100), Some(50), Some(0), None, None, PortCode("STN"), Terminal("T1"), PortCode("SXF"), SDate("2020-08-04T07:20").millisSinceEpoch, None, Set(ForecastFeedSource), None, None))

    result === expected
  }

}
