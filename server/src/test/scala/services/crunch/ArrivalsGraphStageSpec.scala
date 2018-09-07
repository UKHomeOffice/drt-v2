package services.crunch

import controllers.ArrivalGenerator
import controllers.ArrivalGenerator.apiFlight
import drt.shared.CrunchApi.PortState
import drt.shared.FlightsApi.Flights
import drt.shared._
import org.specs2.matcher.Scope
import passengersplits.parsing.VoyageManifestParser.{PassengerInfoJson, VoyageManifest}
import server.feeds.{ArrivalsFeedSuccess, ManifestsFeedSuccess}
import services.SDate
import services.graphstages.DqManifests

import scala.collection.immutable.List
import scala.concurrent.duration._

class ArrivalsGraphStageSpec extends CrunchTestLike {
  sequential
  isolated

  trait Context extends Scope {
    val arrival_v1_with_no_chox_time: Arrival = apiFlight(flightId = Option(1), iata = "BA0001",
      schDt = "2017-01-01T10:25Z", origin = "JFK",
      actPax = Option(100), feedSources = Set(LiveFeedSource))

    val arrival_v2_with_chox_time: Arrival = arrival_v1_with_no_chox_time.copy(Stand = Some("Stand1"), ActualChox = Some(SDate("2017-01-01T10:25Z").millisSinceEpoch))

    val dateNow: SDateLike = SDate("2017-01-01T00:00Z")

    val crunch: CrunchGraphInputsAndProbes = runCrunchGraph(
      airportConfig = airportConfig.copy(terminalNames = Seq("T1")),
      now = () => dateNow
    )

    offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Flights(Seq(arrival_v1_with_no_chox_time))))
    offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Flights(Seq(arrival_v2_with_chox_time))))

    var messages: Seq[Arrival] = Seq[Arrival]()
  }

  "Arrivals Graph Stage" should {

    "a third arrival with an update to the chox time will change the arrival" in new Context {
      val arrival_v3_with_an_update_to_chox_time: Arrival = arrival_v2_with_chox_time.copy(ActualChox = Some(SDate("2017-01-01T10:30Z").millisSinceEpoch), Stand = Some("I will update"))
      offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Flights(Seq(arrival_v3_with_an_update_to_chox_time))))

      crunch.liveTestProbe.receiveWhile(5 seconds) {
        case ps: PortState => messages = ps.flights.values.map(_.apiFlight).toSeq ++ messages
      }

      messages must be_===(Seq(arrival_v3_with_an_update_to_chox_time, arrival_v2_with_chox_time, arrival_v1_with_no_chox_time))

    }

    "a third arrival update with the same chox time will not update the arrival" in new Context {

      val arrival_v3_with_an_update_and_same_chox_time: Arrival = arrival_v2_with_chox_time.copy(Stand = Some("Should not update"))


      offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Flights(Seq(arrival_v3_with_an_update_and_same_chox_time))))

      crunch.liveTestProbe.receiveWhile(5 seconds) {
        case ps: PortState => messages = ps.flights.values.map(_.apiFlight).toSeq ++ messages
      }

      messages must be_===(Seq(arrival_v2_with_chox_time, arrival_v1_with_no_chox_time))
    }

    "once an API (advanced passenger information) input arrives for the flight, it will update the arrivals FeedSource so that it has a LiveFeed and a ApiFeed" in new Context {

      val voyageManifests = ManifestsFeedSuccess(DqManifests("", Set(
        VoyageManifest(DqEventCodes.CheckIn, "STN", "JFK", "0001", "BA", "2017-01-01", "10:25", List(
          PassengerInfoJson(Some("P"), "GBR", "EEA", Some("22"), Some("LHR"), "N", Some("GBR"), Option("GBR"), None)
        ))
      )))

      offerAndWait(crunch.manifestsInput, voyageManifests)

      var feedSources: Set[FeedSource] = Set[FeedSource]()
      crunch.liveTestProbe.receiveWhile(5 seconds) {
        case ps: PortState =>
          val portStateSources = ps.flights.values.flatMap(_.apiFlight.FeedSources).toSet
          if (portStateSources.size > feedSources.size)
          feedSources =portStateSources
      }
      feedSources === Set(LiveFeedSource, ApiFeedSource)
    }

    "once a acl and a forecast input arrives for the flight, it will update the arrivals FeedSource so that it has a ACLFeed, LiveFeed and a ForecastFeed" in new Context {
      val forecastScheduled = "2017-01-01T10:25Z"

      val aclFlight = Flights(List(
        ArrivalGenerator.apiFlight(flightId = Option(1), actPax = Option(10), schDt = forecastScheduled, iata = "BA0001", feedSources = Set(AclFeedSource))
      ))

      offerAndWait(crunch.baseArrivalsInput, ArrivalsFeedSuccess(aclFlight))

      val forecastArrival = apiFlight(schDt = forecastScheduled, iata = "BA0001", terminal = "T1", actPax = Option(21), feedSources = Set(ForecastFeedSource))
      val forecastArrivals = ArrivalsFeedSuccess(Flights(List(forecastArrival)))


      offerAndWait(crunch.forecastArrivalsInput, forecastArrivals)

      var feedSources: Set[FeedSource] = Set[FeedSource]()
      crunch.liveTestProbe.receiveWhile(5 seconds) {
        case ps: PortState =>
          val portStateSources = ps.flights.values.flatMap(_.apiFlight.FeedSources).toSet
          println("HERE: "+ portStateSources)
          if (portStateSources.size > feedSources.size)
          feedSources =portStateSources
      }
      feedSources === Set(LiveFeedSource, ForecastFeedSource, AclFeedSource)
    }

  }


}