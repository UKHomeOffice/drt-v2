package feeds.lhr.forecast

import drt.server.feeds.lhr.forecast.{LHRForecastCSVExtractor, LHRForecastFlightRow}
import drt.shared.Terminals.T2
import drt.shared.{Arrival, ForecastFeedSource}
import org.specs2.mutable.Specification
import services.SDate

class LHRForecastCSVSpec extends Specification {
  "Given a CSV containing a flight for T1 " +
    "then I should get a list of one arrival containing a flight for T1" >> {
    val lhrCsvFixture =
      """"Terminal","Schedule Date","Flight Num","Airport","Int or Dom","Total","Direct","Transfer"
        |"2","04/02/2019 06:00","TS 0001","TST","I",290,200,90""".stripMargin

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
        |"2","04/04/2019 06:00","TS 0001","TST","I",290,200,90
        |"2","04/05/2019 06:00","TS 0002","TST","I",290,200,90
        |"2","04/04/2019 23:10","TS 0003","TST","I",290,200,90
        |"2","03/05/2019 00:00","TS 0004","TST","I",290,200,90
        |"2","01/05/2019 23:10","TS 0005","TST","I",290,200,90""".stripMargin

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
        |"2","04/04/2019 06:00","TS 0001","TST","I",290,200,90
        |"3","04/05/2019 06:00","TS 0002","TST","I",290,200,90
        |"3","04/04/2019 23:10","TS 0003","TST","I",290,200,90
        |"4","03/05/2019 00:00","TS 0004","TST","I",290,200,90
        |"5","01/05/2019 23:10","TS 0005","TST","I",290,200,90""".stripMargin

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
        |"2","04/04/2019 06:00","TS 0001","TST","I",290,200,90
        |"3","04/05/2019 06:00","TS 0002","TST","D",290,200,90
        |"3","04/04/2019 23:10","TS 0003","TST","D",290,200,90
        |"4","03/05/2019 00:00","TS 0004","TST","D",290,200,90
        |"5","01/05/2019 23:10","TS 0005","TST","D",290,200,90""".stripMargin

    val result = LHRForecastCSVExtractor.parse(lhrCsvFixture)

    val expected = Seq(LHRForecastFlightRow(SDate("2019-04-04T05:00Z"), "TS 0001", "TST", "I", 290, 90, "T2"))

    result === expected
  }

  "Given a CSV containing invalid flights " +
    "then those should be excluded from the final result and we should still get the correctly foratted ones" >> {
    val lhrCsvFixture =
      """"Terminal","Schedule Date","Flight Num","Airport","Int or Dom","Total","Direct","Transfer"
        |"2","04/04/2019 06:00","TS 0001","TST","I",290,200,90
        |"3","bad date","TS 0002","TST","I",290,200,90
        |"3","04/04/2019 23:10","TS 0003","TST","I",bad number,200,90
        |"5","01/05/2019 23:10","TS 0005","TST","I",290,200,bad number""".stripMargin

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
      Arrival(None,
        "Port Forecast",
        None,
        None,
        None,
        None,
        None,
        None,
        None,
        Option(290),
        Option(90),
        None,
        None,
        "LHR",
        T2,
        "TS0001",
        "TS0001",
        "TST",
        SDate("2019-04-04T05:00Z").millisSinceEpoch,
        None,
        Set(ForecastFeedSource)
      ),
      Arrival(None,
        "Port Forecast",
        None,
        None,
        None,
        None,
        None,
        None,
        None,
        Option(290),
        Option(90),
        None,
        None,
        "LHR",
        T2,
        "TS0002",
        "TS0002",
        "TST",
        SDate("2019-05-04T05:00Z").millisSinceEpoch,
        None,
        Set(ForecastFeedSource)
      )
    )

    result === expected
  }
}
