package feeds.lgw

import actors.acking.AckingReceiver.StreamCompleted
import akka.stream.scaladsl.Sink
import akka.testkit.TestProbe
import drt.server.feeds.{ArrivalsFeedFailure, ArrivalsFeedSuccess, Feed}
import drt.server.feeds.lgw.{LGWAzureClient, LGWFeed, ResponseToArrivals}
import org.specs2.mock.Mockito
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.arrivals.{Arrival, Passengers, Predictions, TotalPaxSource}
import uk.gov.homeoffice.drt.ports.Terminals.N
import uk.gov.homeoffice.drt.ports.{LiveFeedSource, PortCode}
import uk.gov.homeoffice.drt.time.SDate

import scala.collection.immutable.Seq
import scala.concurrent.duration._
import scala.io.Source

class LGWFeedSpec extends CrunchTestLike with Mockito {
  sequential
  isolated

  import drt.server.feeds.Implicits._

  "Can convert response XML into an Arrival" in {

    val xml: String = Source.fromInputStream(getClass.getClassLoader.getResourceAsStream("lgw.xml")).mkString

    val arrivals: Seq[Arrival] = ResponseToArrivals(xml).getArrivals

    arrivals.size mustEqual 1
    arrivals.head mustEqual Arrival(
      Operator = None,
      Status = "Landed",
      Estimated = Some(SDate("2018-06-03T19:28:00Z").millisSinceEpoch),
      Predictions = Predictions(0L, Map()),
      Actual = Some(SDate("2018-06-03T19:30:00Z").millisSinceEpoch),
      EstimatedChox = Some(SDate("2018-06-03T19:37:00Z").millisSinceEpoch),
      ActualChox = Some(SDate("2018-06-03T19:36:00Z").millisSinceEpoch),
      Gate = None,
      Stand = None,
      MaxPax = Some(308),
      RunwayID = Some("08R"),
      BaggageReclaimId = None,
      AirportID = PortCode("LGW"),
      Terminal = N,
      rawICAO = "VIR808",
      rawIATA = "VS808",
      Origin = PortCode("LHR"),
      FeedSources = Set(LiveFeedSource),
      Scheduled = SDate("2018-06-03T19:50:00Z").millisSinceEpoch, PcpTime = None,
      TotalPax = Map(LiveFeedSource -> Passengers(Option(120), None)))

  }

  "Given a feed item with 0 pax in act and max then I should see that reflected in the arrival" in {

    val xml: String = Source.fromInputStream(getClass.getClassLoader.getResourceAsStream("lgwWith0Pax.xml")).mkString

    val arrivals: Seq[Arrival] = ResponseToArrivals(xml).getArrivals

    arrivals.size mustEqual 1
    arrivals.head mustEqual Arrival(
      Operator = None,
      Status = "Landed",
      Estimated = Some(SDate("2018-06-03T19:28:00Z").millisSinceEpoch),
      Predictions = Predictions(0L, Map()),
      Actual = Some(SDate("2018-06-03T19:30:00Z").millisSinceEpoch),
      EstimatedChox = Some(SDate("2018-06-03T19:37:00Z").millisSinceEpoch),
      ActualChox = Some(SDate("2018-06-03T19:36:00Z").millisSinceEpoch),
      Gate = None,
      Stand = None,
      MaxPax = Some(0),
      RunwayID = Some("08R"),
      BaggageReclaimId = None,
      AirportID = PortCode("LGW"),
      Terminal = N,
      rawICAO = "VIR808",
      rawIATA = "VS808",
      Origin = PortCode("LHR"),
      FeedSources = Set(LiveFeedSource),
      Scheduled = SDate("2018-06-03T19:50:00Z").millisSinceEpoch,
      PcpTime = None,
      TotalPax = Map(LiveFeedSource -> Passengers(Some(0), None)))
  }

  "An empty response returns an empty list of arrivals" in {
    val xml: String = ""

    val arrivals: Seq[Arrival] = ResponseToArrivals(xml).getArrivals

    arrivals mustEqual List()

  }
  "A bad response returns an empty list of arrivals" in {
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
    val actorSource = LGWFeed(azureClient)(system).source(Feed.actorRefSource).map {
      case _: ArrivalsFeedSuccess =>
      case _: ArrivalsFeedFailure =>
    }.to(Sink.actorRef(probe.ref, StreamCompleted)).run()
    actorSource ! Feed.Tick

    probe.fishForMessage(5.seconds) {
      case x => println(x)
        false
    }

    true
  }

}
