package drt.server.feeds.lgw

import drt.shared.Terminals.Terminal
import drt.shared._
import drt.shared.api.Arrival
import org.specs2.mutable.Specification
import services.SDate

class LGWForecastXLSExtractorSpec extends Specification {

  "Given an excel file with the LGW forecast format for UTC flights then I should get forecast flights for terminal" >> {
    val path = getClass.getClassLoader.getResource("LGW_Forecast_Fixture.xlsx").getPath

    val result = LGWForecastXLSExtractor
      .rows(path)
      .map(r =>
        (r.scheduledDate.millisSinceEpoch, r.flightCode, r.origin, r.internationalDomestic, r.totalPax, r.transferPax, r.terminal)
      )
      .toSet

    val expected = Set((SDate("2020-07-10T07:40").millisSinceEpoch, "TST005", "RIX", "I", 14, 0, "N"))

    result === expected
  }

  "Given an excel file with the LGW forecast format for UTC flights then I should get Arrival flights for terminal" >> {
    val path = getClass.getClassLoader.getResource("LGW_Forecast_Fixture.xlsx").getPath

    val result = LGWForecastXLSExtractor(path)

    val expected = List(Arrival(None, CarrierCode("TST"), VoyageNumber(5), None, ArrivalStatus("Port Forecast"), None, None, None, None, None, None, None, Some(14), Some(0), None, None, PortCode("LGW"), Terminal("N"), PortCode("RIX"), SDate("2020-07-10T07:40").millisSinceEpoch, None, Set(ForecastFeedSource), None, None, None))

    result === expected
  }

}

