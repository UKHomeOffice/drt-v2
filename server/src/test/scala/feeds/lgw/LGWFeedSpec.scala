package feeds.lgw

import akka.actor.ActorSystem
import akka.testkit.TestKit
import com.typesafe.config.ConfigFactory
import drt.server.feeds.lgw.ResponseToArrivals
import drt.shared.Terminals.N
import drt.shared.{Arrival, LiveFeedSource, PortCode}
import org.specs2.mock.Mockito
import org.specs2.mutable.SpecificationLike
import services.SDate

import scala.collection.immutable.Seq
import scala.io.Source

class LGWFeedSpec extends TestKit(ActorSystem("testActorSystem", ConfigFactory.empty())) with SpecificationLike with Mockito {
  sequential
  isolated

  import drt.server.feeds.Implicits._

  "Can convert response XML into an Arrival" in  {

    val xml: String = Source.fromInputStream(getClass.getClassLoader.getResourceAsStream("lgw.xml")).mkString

    val arrivals: Seq[Arrival] = ResponseToArrivals(xml).getArrivals

    arrivals.size mustEqual 1
    arrivals.head mustEqual new Arrival(
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
      Terminal = N, rawICAO = "VIR808", rawIATA = "VS808", Origin = PortCode("LHR"), FeedSources = Set(LiveFeedSource),
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

}
