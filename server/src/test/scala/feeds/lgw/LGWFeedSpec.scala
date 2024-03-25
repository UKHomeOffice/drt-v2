package feeds.lgw

import akka.stream.scaladsl.Sink
import akka.testkit.TestProbe
import drt.server.feeds.lgw.{LGWAzureClient, LGWFeed, ResponseToArrivals}
import drt.server.feeds.{ArrivalsFeedFailure, ArrivalsFeedSuccess, Feed}
import org.specs2.mock.Mockito
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.actor.acking.AckingReceiver.StreamCompleted
import uk.gov.homeoffice.drt.arrivals.LiveArrival
import uk.gov.homeoffice.drt.ports.Terminals.N
import uk.gov.homeoffice.drt.time.SDate

import scala.concurrent.duration._
import scala.io.Source

class LGWFeedSpec extends CrunchTestLike with Mockito {
  sequential
  isolated

  "Can convert response XML into an Arrival" in {

    val xml: String = Source.fromInputStream(getClass.getClassLoader.getResourceAsStream("lgw.xml")).mkString

    val arrivals = ResponseToArrivals(xml).getArrivals

    arrivals.size mustEqual 1
    arrivals.head mustEqual LiveArrival(
      operator = None,
      maxPax = Some(308),
      totalPax = Some(120),
      transPax = None,
      terminal = N,
      voyageNumber = 808,
      carrierCode = "VS",
      flightCodeSuffix = None,
      origin = "LHR",
      scheduled = SDate("2018-06-03T19:50:00Z").millisSinceEpoch,
      estimated = Some(SDate("2018-06-03T19:28:00Z").millisSinceEpoch),
      touchdown = Some(SDate("2018-06-03T19:30:00Z").millisSinceEpoch),
      estimatedChox = Some(SDate("2018-06-03T19:37:00Z").millisSinceEpoch),
      actualChox = Some(SDate("2018-06-03T19:36:00Z").millisSinceEpoch),
      status = "Landed",
      gate = None,
      stand = None,
      runway = Some("08R"),
      baggageReclaim = None,
    )
  }

  "Given a feed item with 0 pax in act and max then I should see that reflected in the arrival" in {

    val xml: String = Source.fromInputStream(getClass.getClassLoader.getResourceAsStream("lgwWith0Pax.xml")).mkString

    val arrivals = ResponseToArrivals(xml).getArrivals

    arrivals.size mustEqual 1
    arrivals.head mustEqual LiveArrival(
      operator = None,
      maxPax = Some(0),
      totalPax = Some(0),
      transPax = None,
      terminal = N,
      voyageNumber = 808,
      carrierCode = "VS",
      flightCodeSuffix = None,
      origin = "LHR",
      scheduled = SDate("2018-06-03T19:50:00Z").millisSinceEpoch,
      estimated = Some(SDate("2018-06-03T19:28:00Z").millisSinceEpoch),
      touchdown = Some(SDate("2018-06-03T19:30:00Z").millisSinceEpoch),
      estimatedChox = Some(SDate("2018-06-03T19:37:00Z").millisSinceEpoch),
      actualChox = Some(SDate("2018-06-03T19:36:00Z").millisSinceEpoch),
      status = "Landed",
      gate = None,
      stand = None,
      runway = Some("08R"),
      baggageReclaim = None,
    )
  }

  "An empty response returns an empty list of arrivals" in {
    val xml: String = ""

    val arrivals = ResponseToArrivals(xml).getArrivals

    arrivals mustEqual List()

  }
  "A bad response returns an empty list of arrivals" in {
    val xml: String = "<thing>some not valid xml</thing>"

    val arrivals = ResponseToArrivals(xml).getArrivals

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
      case _ => false
    }

    true
  }

}
