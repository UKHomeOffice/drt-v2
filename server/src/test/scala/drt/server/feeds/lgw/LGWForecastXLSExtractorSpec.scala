package drt.server.feeds.lgw

import drt.shared._
import drt.shared.api.Arrival
import org.specs2.mutable.Specification
import services.SDate
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
      Arrival(None, CarrierCode("TA"), VoyageNumber(1001), None, ArrivalStatus("Port Forecast"), None, None, None, None, None, None, None, Some(110), Some(0), None, None, PortCode("LGW"), Terminal("N"), PortCode("UVF"), SDate("2021-10-22T08:30").millisSinceEpoch, None, Set(ForecastFeedSource), None, None, None, None),
      Arrival(None, CarrierCode("TA"), VoyageNumber(1002), None, ArrivalStatus("Port Forecast"), None, None, None, None, None, None, None, Some(85), Some(0), None, None, PortCode("LGW"), Terminal("N"), PortCode("MRU"), SDate("2021-10-22T06:30").millisSinceEpoch, None, Set(ForecastFeedSource), None, None, None, None),
      Arrival(None, CarrierCode("TA"), VoyageNumber(1003), None, ArrivalStatus("Port Forecast"), None, None, None, None, None, None, None, Some(331), Some(0), None, None, PortCode("LGW"), Terminal("N"), PortCode("PLS"), SDate("2021-10-22T10:40").millisSinceEpoch, None, Set(ForecastFeedSource), None, None, None, None))
    result === expected
  }

}
