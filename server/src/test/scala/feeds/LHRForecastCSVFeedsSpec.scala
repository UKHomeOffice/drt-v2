package feeds

import drt.server.feeds.lhr.LHRForecastFeed
import drt.shared.Arrival
import org.specs2.mutable.Specification
import services.SDate

import scala.collection.immutable.Seq

class LHRForecastCSVFeedsSpec extends Specification {

  import drt.server.feeds.lhr.LHRForecastFeed._

  "When parsing a CSV file for LHR Forecast" >> {

    "Given two rows then I should get back two flights" >> {
      val csv =
        """,,,,,,,
          |,,,,,,,
          |,Scheduled Date,Flight Number,Airport,Int or Dom,Total,Direct,Transfer
          |,31/10/2017 16:45,LH 0914,FRA,INTERNATIONAL,23,17,6
          |,31/10/2017 16:35,LX 0348,GVA,INTERNATIONAL,73,71,1""".stripMargin

      val expected = List(
        Arrival(
          "", "Port Forecast", "", "", "", "", "", "", 0, 23, 6, "", "", 0, "LHR", "T2", "LH0914", "LH0914", "FRA",
          SDate("2017-10-31T16:45").toISOString(), SDate("2017-10-31T16:45").millisSinceEpoch, 0, None
        ),
        Arrival("", "Port Forecast", "", "", "", "", "", "", 0, 73, 1, "", "", 0, "LHR", "T2", "LX0348", "LX0348", "GVA",
          SDate("2017-10-31T16:35").toISOString(), SDate("2017-10-31T16:35").millisSinceEpoch, 0, None
        )
      )

      val result = parseCSV(csv, "T2")

      result === expected
    }
  }

  "When parsing a CSV file for LHR Forecast" >> {

    "Given a flight marked as domestic, that flight should not appear in the list" >> {
      val csv =
        """,,,,,,,
          |,,,,,,,
          |,Scheduled Date,Flight Number,Airport,Int or Dom,Total,Direct,Transfer
          |,31/10/2017 16:45,LH 0914,FRA,INTERNATIONAL,23,17,6
          |,31/10/2017 16:35,LX 0348,GVA,DOMESTIC,73,71,1""".stripMargin

      val expected = List(
        Arrival(
          "", "Port Forecast", "", "", "", "", "", "", 0, 23, 6, "", "", 0, "LHR", "T2", "LH0914", "LH0914", "FRA",
          SDate("2017-10-31T16:45").toISOString(), SDate("2017-10-31T16:45").millisSinceEpoch, 0, None
        )
      )

      val result = parseCSV(csv, "T2")

      result === expected
    }
  }


  "When polling for new forecast files" >> {
    "Given a path to a zip file then I should be able to extract each terminal CSV" >> {
      val path = getClass.getClassLoader.getResource("lhr-forecast-fixture.zip").getPath

      val result: List[Seq[Arrival]] = LHRForecastFeed.contentFromFileName(path).toList

      val expected = List(
        List(),
        List(Arrival(
          "","Port Forecast","","","","","","",0,30,4,"","",0,"LHR","T2","SA321","SA321","CPT",
          SDate("2017-10-31T16:45:00Z").toISOString(),SDate("2017-10-31T16:45:00Z").millisSinceEpoch,0,None
        )),
        List(Arrival(
          "","Port Forecast","","","","","","",0,260,19,"","",0,"LHR","T3","SA123","SA123","JHB",
          SDate("2017-10-31T16:55:00Z").toISOString(),SDate("2017-10-31T16:55:00Z").millisSinceEpoch,0,None
        )),
        List(Arrival("","Port Forecast","","","","","","",0,300,40,"","",0,"LHR","T4","SA124","SA124","BUQ",
          SDate("2017-10-31T16:35:00Z").toISOString(),SDate("2017-10-31T16:35:00Z").millisSinceEpoch,0,None
        )),
        List(Arrival("","Port Forecast","","","","","","",0,200,30,"","",0,"LHR","T5","SA1235","SA1235","HRE",
          SDate("2017-10-31T16:40:00Z").toISOString(),SDate("2017-10-31T16:40:00Z").millisSinceEpoch,0,None
        ))
      )

      result === expected
    }
  }
}


