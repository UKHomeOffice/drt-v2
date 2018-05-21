package feeds

import com.box.sdk.{BoxConfig, BoxDeveloperEditionAPIConnection}
import drt.server.feeds.lgw.LGWForecastFeed
import drt.shared.Arrival
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import org.specs2.specification.Scope

class LGWForecastFeedSpec extends Specification with Mockito {

  trait Context extends Scope {
    val filePath: String = getClass.getClassLoader.getResource("box-config.json").getPath
    val userId = ""
    val ukBfGalForecastFolderId = ""
  }

  trait ExampleContext extends Context {
    val exampleData: String =
      """Date,Flight Number,POA Forecast Version,Seats,Aircraft Type,Terminal,ArrDep,Orig Dest,Airport Code,Scheduled Time,POA Pax,Transfer Pax,CSA Pax,Prefix,Time,Hour,Int/Dom,Date/Time
        |19-May-18,3O0101,POA FCST 17-05-18,174,,South,Arrival,"Tangier (Ibn Batuta), Morocco",TNG,1105,134,0,134,3O,11:05,11,INTL,19/05/2018 11:05
        |,,,,,,,,,,,,,,,,,
        |,,,,,,,,,,,,,,,,,
        |
      """.stripMargin
  }

  "Can access a Box" should {

    "Can parse the arrivals given a CSV" in new ExampleContext {
      val feed: LGWForecastFeed = new LGWForecastFeed(filePath, userId, ukBfGalForecastFolderId) {
        override def getBoxConfig: BoxConfig = mock[BoxConfig]
        override def getApiConnection: BoxDeveloperEditionAPIConnection = mock[BoxDeveloperEditionAPIConnection]
      }

      val arrivals: List[Arrival] = feed.getArrivalsFromData("aFile.csv", exampleData)
      arrivals.length mustEqual 1
      arrivals.head mustEqual new Arrival(
        Operator = "",
        Status = "Port Forecast",
        EstDT = "",
        ActDT = "",
        EstChoxDT = "",
        ActChoxDT = "",
        Gate = "",
        Stand = "",
        MaxPax = 174,
        ActPax = 134,
        TranPax = 0,
        RunwayID = "",
        BaggageReclaimId = "",
        FlightID = 0,
        AirportID = "LGW",
        Terminal = "S",
        rawICAO = "3O0101",
        rawIATA = "3O0101",
        Origin = "TNG",
        SchDT = "2018-05-19T11:05:00.000Z",
        Scheduled = 1526727900000L,
        PcpTime = 0,
        LastKnownPax = None)
    }

    "Can parse the arrivals in the latest file" in new Context {
      skipped("exploratory test for the LGW forecast feed")
      val feed = new LGWForecastFeed(filePath, userId, ukBfGalForecastFolderId)

      val arrivals: List[Arrival] = feed.getArrivals

      arrivals.foreach(println)

      println(s"Got ${arrivals.size} arrivals.")

      arrivals.length mustNotEqual 0

    }.pendingUntilFixed("This is not a test")
  }
}
