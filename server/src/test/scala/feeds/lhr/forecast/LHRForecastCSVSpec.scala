package feeds.lhr.forecast

import drt.server.feeds.lhr.forecast.{LHRForecastCSVExtractor, LHRForecastFlightRow}
import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.time.SDate
import uk.gov.homeoffice.drt.arrivals.{Arrival, ArrivalStatus}
import uk.gov.homeoffice.drt.ports.Terminals.T2
import uk.gov.homeoffice.drt.ports.{ForecastFeedSource, PortCode}

class LHRForecastCSVSpec extends Specification {
  "Given a CSV containing a flight for T1 " +
    "then I should get a list of one arrival containing a flight for T1" >> {
    val lhrCsvFixture =
      """"Terminal","Schedule Date","Flight Num","Airport","Int or Dom","Total","Direct","Transfer"
        |"2","2019-02-04 06:00:00","TS 0001","TST","I",290,200,90""".stripMargin

    val result = LHRForecastCSVExtractor.parse(lhrCsvFixture)

    val expected = Seq(LHRForecastFlightRow(
      SDate("2019-02-04T06:00Z"),
      "TS 0001",
      "TST",
      "I",
      290,
      90,
      "T2"
    ))

    result === expected
  }

  "Given a CSV containing a flights during BST " +
    "then I should get those flights back with their scheduled dates parsed correctly" >> {
    val lhrCsvFixture =
      """"Terminal","Schedule Date","Flight Num","Airport","Int or Dom","Total","Direct","Transfer"
        |"2","2019-04-04 06:00:00","TS 0001","TST","I",290,200,90
        |"2","2019-05-04 06:00:00","TS 0002","TST","I",290,200,90
        |"2","2019-04-04 23:10:00","TS 0003","TST","I",290,200,90
        |"2","2019-05-03 00:00:00","TS 0004","TST","I",290,200,90
        |"2","2019-05-01 23:10:00","TS 0005","TST","I",290,200,90""".stripMargin

    val result = LHRForecastCSVExtractor.parse(lhrCsvFixture)

    val expected = Seq(
      LHRForecastFlightRow(SDate("2019-04-04T05:00Z"), "TS 0001", "TST", "I", 290, 90, "T2"),
      LHRForecastFlightRow(SDate("2019-05-04T05:00Z"), "TS 0002", "TST", "I", 290, 90, "T2"),
      LHRForecastFlightRow(SDate("2019-04-04T22:10Z"), "TS 0003", "TST", "I", 290, 90, "T2"),
      LHRForecastFlightRow(SDate("2019-05-02T23:00Z"), "TS 0004", "TST", "I", 290, 90, "T2"),
      LHRForecastFlightRow(SDate("2019-05-01T22:10Z"), "TS 0005", "TST", "I", 290, 90, "T2")
    )

    result === expected
  }

  "Given a CSV containing a flights for multiple terminals " +
    "then I should get those flights back with terminal allocated correctly" >> {
    val lhrCsvFixture =
      """"Terminal","Schedule Date","Flight Num","Airport","Int or Dom","Total","Direct","Transfer"
        |"2","2019-04-04 06:00:00","TS 0001","TST","I",290,200,90
        |"3","2019-05-04 06:00:00","TS 0002","TST","I",290,200,90
        |"3","2019-04-04 23:10:00","TS 0003","TST","I",290,200,90
        |"4","2019-05-03 00:00:00","TS 0004","TST","I",290,200,90
        |"5","2019-05-01 23:10:00","TS 0005","TST","I",290,200,90""".stripMargin

    val result = LHRForecastCSVExtractor.parse(lhrCsvFixture)

    val expected = Seq(
      LHRForecastFlightRow(SDate("2019-04-04T05:00Z"), "TS 0001", "TST", "I", 290, 90, "T2"),
      LHRForecastFlightRow(SDate("2019-05-04T05:00Z"), "TS 0002", "TST", "I", 290, 90, "T3"),
      LHRForecastFlightRow(SDate("2019-04-04T22:10Z"), "TS 0003", "TST", "I", 290, 90, "T3"),
      LHRForecastFlightRow(SDate("2019-05-02T23:00Z"), "TS 0004", "TST", "I", 290, 90, "T4"),
      LHRForecastFlightRow(SDate("2019-05-01T22:10Z"), "TS 0005", "TST", "I", 290, 90, "T5")
    )

    result === expected
  }

  "Given a CSV containing domestic flights " +
    "then those should be excluded from the final result" >> {
    val lhrCsvFixture =
      """"Terminal","Schedule Date","Flight Num","Airport","Int or Dom","Total","Direct","Transfer"
        |"2","2019-04-04 06:00:00","TS 0001","TST","I",290,200,90
        |"3","2019-05-04 06:00:00","TS 0002","TST","D",290,200,90
        |"3","2019-04-04 23:10:00","TS 0003","TST","D",290,200,90
        |"4","2019-05-03 00:00:00","TS 0004","TST","D",290,200,90
        |"5","2019-05-01 23:10:00","TS 0005","TST","D",290,200,90""".stripMargin

    val result = LHRForecastCSVExtractor.parse(lhrCsvFixture)

    val expected = Seq(LHRForecastFlightRow(SDate("2019-04-04T05:00Z"), "TS 0001", "TST", "I", 290, 90, "T2"))

    result === expected
  }

  "Given a CSV containing invalid flights " +
    "then those should be excluded from the final result and we should still get the correctly foratted ones" >> {
    val lhrCsvFixture =
      """"Terminal","Schedule Date","Flight Num","Airport","Int or Dom","Total","Direct","Transfer"
        |"2","2019-04-04 06:00:00","TS 0001","TST","I",290,200,90
        |"3","bad date","TS 0002","TST","I",290,200,90
        |"3","2019-04-04 23:10:00","TS 0003","TST","I",bad number,200,90
        |"5","2019-05-01 23:10:00","TS 0005","TST","I",290,200,bad number""".stripMargin

    val result = LHRForecastCSVExtractor.parse(lhrCsvFixture)

    val expected = Seq(
      LHRForecastFlightRow(SDate("2019-04-04T05:00Z"), "TS 0001", "TST", "I", 290, 90, "T2")
    )

    result === expected
  }

  "Given a path to a CSV file " +
    "then that file should be parsed successfully to Arrivals" >> {

    val filePath = getClass.getResource("/LHRForecastCSVFixture.csv").getPath

    val result = LHRForecastCSVExtractor(filePath)

    val expected = Seq(
      Arrival(
        Operator = None,
        Status = ArrivalStatus("Port Forecast"),
        Estimated = None,
        PredictedTouchdown = None,
        Actual = None,
        EstimatedChox = None,
        ActualChox = None,
        Gate = None,
        Stand = None,
        MaxPax = None,
        ActPax = Option(290),
        TranPax = Option(90),
        RunwayID = None,
        BaggageReclaimId = None,
        AirportID = PortCode("LHR"),
        Terminal = T2,
        rawICAO = "TS0001",
        rawIATA = "TS0001",
        Origin = PortCode("TST"),
        Scheduled = SDate("2019-04-04T05:00Z").millisSinceEpoch,
        PcpTime = None,
        FeedSources = Set(ForecastFeedSource)
      ),

      Arrival(
        Operator = None,
        Status = ArrivalStatus("Port Forecast"),
        Estimated = None,
        PredictedTouchdown = None,
        Actual = None,
        EstimatedChox = None,
        ActualChox = None,
        Gate = None,
        Stand = None,
        MaxPax = None,
        ActPax = Option(290),
        TranPax = Option(90),
        RunwayID = None,
        BaggageReclaimId = None,
        AirportID = PortCode("LHR"),
        Terminal = T2,
        rawICAO = "TS0002",
        rawIATA = "TS0002",
        Origin = PortCode("TST"),
        Scheduled = SDate("2019-05-04T05:00Z").millisSinceEpoch,
        PcpTime = None,
        FeedSources = Set(ForecastFeedSource)
      )
    )

    result === expected
  }
}
