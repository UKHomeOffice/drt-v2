package drt.server.feeds.stn

import drt.shared.Terminals.Terminal
import drt.shared._
import drt.shared.api.Arrival
import org.specs2.mutable.Specification
import services.SDate

class STNForecastXLSExtractorSpec extends Specification {

  "Given an excel file with the STN forecast format for BST flights then I should get forecast flights for terminal" >> {
    val path = getClass.getClassLoader.getResource("STN_Forecast_Fixture.xlsx").getPath

    val result = STNForecastXLSExtractor
      .rows(path)
      .map(r =>
        (r.scheduledDate.millisSinceEpoch, r.flightCode, r.origin, r.internationalDomestic, r.totalPax, r.transferPax, r.terminal)
      )
      .toSet


    val expected = Set(
      (SDate("2020-04-01T00:00").millisSinceEpoch, "TST 0001", "AGP", "I", 73, 73, "T1"),
      (SDate("2020-04-01T00:00").millisSinceEpoch, "TST 0002", "NCE", "I", 74, 74, "T1"),
      (SDate("2020-04-01T00:15").millisSinceEpoch, "TST 0003", "AGA", "I", 98, 98, "T1"),
      (SDate("2020-04-01T00:50").millisSinceEpoch, "TST 0004", "LJU", "I", 43, 43, "T1")
    )


    result === expected
  }

  "Given an excel file with the STN forecast format for BST flights then I should get Arrival flights for terminal" >> {
    val path = getClass.getClassLoader.getResource("STN_Forecast_Fixture.xlsx").getPath

    val result = STNForecastXLSExtractor(path)

    val expected = List(
      Arrival(None, CarrierCode("TST"), VoyageNumber(1), None, ArrivalStatus("Port Forecast"), None, None, None, None, None, None, None, Some(73), Some(73), None, None, PortCode("LGW"), Terminal("T1"), PortCode("AGP"), SDate("2020-04-01T00:00").millisSinceEpoch, None, Set(ForecastFeedSource), None, None),
      Arrival(None, CarrierCode("TST"), VoyageNumber(2), None, ArrivalStatus("Port Forecast"), None, None, None, None, None, None, None, Some(74), Some(74), None, None, PortCode("LGW"), Terminal("T1"), PortCode("NCE"), SDate("2020-04-01T00:00").millisSinceEpoch, None, Set(ForecastFeedSource), None, None),
      Arrival(None, CarrierCode("TST"), VoyageNumber(3), None, ArrivalStatus("Port Forecast"), None, None, None, None, None, None, None, Some(98), Some(98), None, None, PortCode("LGW"), Terminal("T1"), PortCode("AGA"), SDate("2020-04-01T00:15").millisSinceEpoch, None, Set(ForecastFeedSource), None, None),
      Arrival(None, CarrierCode("TST"), VoyageNumber(4), None, ArrivalStatus("Port Forecast"), None, None, None, None, None, None, None, Some(43), Some(43), None, None, PortCode("LGW"), Terminal("T1"), PortCode("LJU"), SDate("2020-04-01T00:50").millisSinceEpoch, None, Set(ForecastFeedSource), None, None))

    result === expected
  }

}
