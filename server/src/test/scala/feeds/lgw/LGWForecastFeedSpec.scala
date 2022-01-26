package feeds.lgw

import com.box.sdk.{BoxConfig, BoxDeveloperEditionAPIConnection}
import drt.server.feeds.lgw.LGWForecastFeed
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import org.specs2.specification.Scope
import services.SDate
import uk.gov.homeoffice.drt.arrivals.Arrival
import uk.gov.homeoffice.drt.ports.Terminals.S
import uk.gov.homeoffice.drt.ports.{ForecastFeedSource, PortCode}

import scala.util.{Failure, Success, Try}

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

    val exampleDataWith0Pax: String =
      """Date,Flight Number,POA Forecast Version,Seats,Aircraft Type,Terminal,ArrDep,Orig Dest,Airport Code,Scheduled Time,POA Pax,Transfer Pax,CSA Pax,Prefix,Time,Hour,Int/Dom,Date/Time
        |19-May-18,3O0101,POA FCST 17-05-18,0,,South,Arrival,"Tangier (Ibn Batuta), Morocco",TNG,1105,0,0,0,3O,11:05,11,INTL,19/05/2018 11:05
        |,,,,,,,,,,,,,,,,,
        |,,,,,,,,,,,,,,,,,
        |
      """.stripMargin
  }

  import drt.server.feeds.Implicits._

  "The LGW Forecast Feed" should {

    "parse the arrivals given a CSV" in new ExampleContext {
      val feed: LGWForecastFeed = new LGWForecastFeed(filePath, userId, ukBfGalForecastFolderId) {
        override def getBoxConfig: BoxConfig = mock[BoxConfig]
        override def getApiConnection: Try[BoxDeveloperEditionAPIConnection] = Try(mock[BoxDeveloperEditionAPIConnection])
      }

      val arrivals: List[Arrival] = feed.getArrivalsFromData("aFile.csv", exampleData)
      arrivals.length mustEqual 1
      arrivals.head mustEqual Arrival(
        Operator = None,
        Status = "Port Forecast",
        Estimated = None,
        Actual = None,
        EstimatedChox = None,
        ActualChox = None,
        Gate = None,
        Stand = None,
        MaxPax = Option(174),
        ActPax = Option(134),
        TranPax = Some(0),
        RunwayID = None,
        BaggageReclaimId = None,
        AirportID = PortCode("LGW"),
        Terminal = S,
        rawICAO = "3O0101",
        rawIATA = "3O0101",
        Origin = PortCode("TNG"),
        Scheduled = SDate("2018-05-19T10:05:00Z").millisSinceEpoch,
        PcpTime = None,
        FeedSources = Set(ForecastFeedSource)
      )
    }

    "Given 0 passengers for Act and Max and Trans should reflect this in the parsed arrival" in new ExampleContext {
      val feed: LGWForecastFeed = new LGWForecastFeed(filePath, userId, ukBfGalForecastFolderId) {
        override def getBoxConfig: BoxConfig = mock[BoxConfig]
        override def getApiConnection: Try[BoxDeveloperEditionAPIConnection] = Try(mock[BoxDeveloperEditionAPIConnection])
      }

      val arrivals: List[Arrival] = feed.getArrivalsFromData("aFile.csv", exampleDataWith0Pax)
      arrivals.length mustEqual 1
      arrivals.head mustEqual Arrival(
        Operator = None,
        Status = "Port Forecast",
        Estimated = None,
        Actual = None,
        EstimatedChox = None,
        ActualChox = None,
        Gate = None,
        Stand = None,
        MaxPax = Option(0),
        ActPax = Option(0),
        TranPax = Some(0),
        RunwayID = None,
        BaggageReclaimId = None,
        AirportID = PortCode("LGW"),
        Terminal = S,
        rawICAO = "3O0101",
        rawIATA = "3O0101",
        Origin = PortCode("TNG"),
        Scheduled = SDate("2018-05-19T10:05:00Z").millisSinceEpoch,
        PcpTime = None,
        FeedSources = Set(ForecastFeedSource)
      )
    }

    "Can return the exception if we cannot get the latest file" in new Context {
      val expectedError = "an error"
      val feed: LGWForecastFeed = new LGWForecastFeed(filePath, userId, ukBfGalForecastFolderId) {
        override def getBoxConfig: BoxConfig = mock[BoxConfig]
        override def getApiConnection: Try[BoxDeveloperEditionAPIConnection] = Try(throw new Exception(expectedError))
      }

      feed.getArrivals must beLike {
        case Failure(e) => e.getMessage mustEqual expectedError
      }
    }

    "Can parse the arrivals in the latest file" in new Context {
      skipped("exploratory test for the LGW forecast feed")
      val feed = new LGWForecastFeed(filePath, userId, ukBfGalForecastFolderId)

      val Success(arrivals: List[Arrival]) = feed.getArrivals

      arrivals.foreach(println)

      println(s"Got ${arrivals.size} arrivals.")

      arrivals.length mustNotEqual 0

    }.pendingUntilFixed("This is not a test")
  }
}
