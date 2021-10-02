package feeds.lgw

import akka.stream.scaladsl.Sink
import akka.testkit.TestProbe
import drt.server.feeds.lgw.{LGWAzureClient, LGWFeed, ResponseToArrivals}
import drt.shared.api.Arrival
import org.specs2.mock.Mockito
import server.feeds.{ArrivalsFeedFailure, ArrivalsFeedSuccess}
import services.SDate
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.ports.Terminals.N
import uk.gov.homeoffice.drt.ports.{LiveFeedSource, PortCode}

import scala.collection.immutable.Seq
import scala.concurrent.duration._
import scala.io.Source

class LGWFeedSpec extends CrunchTestLike with Mockito {
  sequential
  isolated

  import drt.server.feeds.Implicits._

  "Can convert response XML into an Arrival" in  {

    val xml: String = Source.fromInputStream(getClass.getClassLoader.getResourceAsStream("lgw.xml")).mkString

    val arrivals: Seq[Arrival] = ResponseToArrivals(xml).getArrivals

    arrivals.size mustEqual 1
    arrivals.head mustEqual Arrival(
      Operator = None,
      Status = "Landed",
      Estimated = Some(SDate("2018-06-03T19:28:00Z").millisSinceEpoch),
      Actual =  Some(SDate("2018-06-03T19:30:00Z").millisSinceEpoch),
      EstimatedChox =  Some(SDate("2018-06-03T19:37:00Z").millisSinceEpoch),
      ActualChox =  Some(SDate("2018-06-03T19:36:00Z").millisSinceEpoch),
      Gate = None,
      Stand = None,
      MaxPax = Some(308),
      ActPax = Some(120),
      TranPax = None,
      RunwayID = Some("08R"),
      BaggageReclaimId = None,
      AirportID = PortCode("LGW"),
      Terminal = N,
      rawICAO = "VIR808",
      rawIATA = "VS808",
      Origin = PortCode("LHR"),
      FeedSources = Set(LiveFeedSource),
      Scheduled = SDate("2018-06-03T19:50:00Z").millisSinceEpoch, PcpTime = None)


  }

  "Given a feed item with 0 pax in act and max then I should see that reflected in the arrival" in  {

    val xml: String = Source.fromInputStream(getClass.getClassLoader.getResourceAsStream("lgwWith0Pax.xml")).mkString

    val arrivals: Seq[Arrival] = ResponseToArrivals(xml).getArrivals

    arrivals.size mustEqual 1
    arrivals.head mustEqual Arrival(
      Operator = None,
      Status = "Landed",
      Estimated = Some(SDate("2018-06-03T19:28:00Z").millisSinceEpoch),
      Actual =  Some(SDate("2018-06-03T19:30:00Z").millisSinceEpoch),
      EstimatedChox =  Some(SDate("2018-06-03T19:37:00Z").millisSinceEpoch),
      ActualChox =  Some(SDate("2018-06-03T19:36:00Z").millisSinceEpoch),
      Gate = None,
      Stand = None,
      MaxPax = Some(0),
      ActPax = Some(0),
      TranPax = None,
      RunwayID = Some("08R"),
      BaggageReclaimId = None,
      AirportID = PortCode("LGW"),
      Terminal = N,
      rawICAO = "VIR808",
      rawIATA = "VS808",
      Origin = PortCode("LHR"),
      FeedSources = Set(LiveFeedSource),
      Scheduled = SDate("2018-06-03T19:50:00Z").millisSinceEpoch, PcpTime = None)


  }

  "An empty response returns an empty list of arrivals" in  {
    val xml: String = ""

    val arrivals: Seq[Arrival] = ResponseToArrivals(xml).getArrivals

    arrivals mustEqual List()

  }
  "A bad response returns an empty list of arrivals" in  {
    val xml: String = "<thing>some not valid xml</thing>"

    val arrivals: Seq[Arrival] = ResponseToArrivals(xml).getArrivals

    arrivals mustEqual List()

  }

  "Exploratory test" >> {
    skipped("Exploratory")
    val lgwNamespace = ""
    val lgwSasToKey = ""
    val lgwServiceBusUri = ""
    val azureClient = LGWAzureClient(LGWFeed.serviceBusClient(lgwNamespace, lgwSasToKey, lgwServiceBusUri))

    val probe = TestProbe()
    LGWFeed(azureClient)(system).source().map{
      case s: ArrivalsFeedSuccess =>
        println(s.arrivals)
      case f: ArrivalsFeedFailure =>
        println(f.responseMessage)
    }.runWith(Sink.foreach(probe.ref ! _ ))

    probe.fishForMessage(5 minutes){
      case x => println(x)
        false
    }

    true
  }

}
