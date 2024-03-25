package drt.server.feeds.stn

import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.SDate

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
      ForecastArrival(
        operator = None,
        maxPax = Some(100),
        totalPax = Option(50),
        transPax = None,
        terminal = Terminal("T1"),
        voyageNumber = 1001,
        carrierCode = "TST",
        flightCodeSuffix = None,
        origin = "AGP",
        scheduled = SDate("2020-08-03T23:00").millisSinceEpoch,
      ),
      ForecastArrival(
        operator = None,
        maxPax = Some(100),
        totalPax = Option(50),
        transPax = None,
        terminal = Terminal("T1"),
        voyageNumber = 1002,
        carrierCode = "TST",
        flightCodeSuffix = None,
        origin = "DLM",
        scheduled = SDate("2020-08-04T00:15").millisSinceEpoch,
      ),
      ForecastArrival(
        operator = None,
        maxPax = Some(100),
        totalPax = Option(50),
        transPax = None,
        terminal = Terminal("T1"),
        voyageNumber = 1003,
        carrierCode = "TST",
        flightCodeSuffix = None,
        origin = "CGN",
        scheduled = SDate("2020-08-04T06:05").millisSinceEpoch,
      ),
      ForecastArrival(
        operator = None,
        maxPax = Some(100),
        totalPax = Option(50),
        transPax = None,
        terminal = Terminal("T1"),
        voyageNumber = 1005,
        carrierCode = "TST",
        flightCodeSuffix = None,
        origin = "SXF",
        scheduled = SDate("2020-08-04T06:20").millisSinceEpoch,
      )
    )
    result === expected
  }

}
