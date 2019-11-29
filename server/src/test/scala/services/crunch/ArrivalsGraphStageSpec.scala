package services.crunch

import controllers.ArrivalGenerator
import controllers.ArrivalGenerator.arrival
import drt.shared.CrunchApi.{CrunchMinute, StaffMinute}
import drt.shared.FlightsApi.Flights
import drt.shared.PaxTypes.EeaMachineReadable
import drt.shared.Queues.EeaDesk
import drt.shared.SplitRatiosNs.SplitSources.TerminalAverage
import drt.shared.Terminals.T1
import drt.shared._
import org.specs2.matcher.Scope
import passengersplits.core.PassengerTypeCalculatorValues.DocumentType
import passengersplits.parsing.VoyageManifestParser.{ManifestDateOfArrival, ManifestTimeOfArrival, PassengerInfoJson, VoyageManifest}
import server.feeds.{ArrivalsFeedSuccess, DqManifests, ManifestsFeedSuccess}
import services.SDate

import scala.collection.immutable.{List, SortedMap}
import scala.collection.mutable
import scala.concurrent.duration._

class ArrivalsGraphStageSpec extends CrunchTestLike {
  sequential
  isolated

  trait Context extends Scope {
    val arrival_v1_with_no_chox_time: Arrival = arrival(iata = "BA0001", schDt = "2017-01-01T10:25Z", actPax = Option(100), origin = PortCode("JFK"), feedSources = Set(LiveFeedSource))

    val arrival_v2_with_chox_time: Arrival = arrival_v1_with_no_chox_time.copy(Stand = Option("Stand1"), ActualChox = Option(SDate("2017-01-01T10:25Z").millisSinceEpoch))

    val dateNow: SDateLike = SDate("2017-01-01T00:00Z")

    val terminalSplits = Splits(Set(
      ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 100.0, None)), TerminalAverage, None, Percentage)

    val crunch: CrunchGraphInputsAndProbes = runCrunchGraph(
      airportConfig = airportConfig.copy(terminals = Seq(T1)),
      now = () => dateNow,
      initialPortState = Option(PortState(SortedMap(arrival_v2_with_chox_time.unique -> ApiFlightWithSplits(arrival_v2_with_chox_time, Set(terminalSplits))), SortedMap[TQM, CrunchMinute](), SortedMap[TM, StaffMinute]())),
      initialLiveArrivals = mutable.SortedMap[UniqueArrival, Arrival]() ++ List(arrival_v2_with_chox_time).map(a => (a.unique, a))
    )

    var messages: Set[Arrival] = Set()
  }

  "Arrivals Graph Stage" should {

    "a third arrival with an update to the chox time will change the arrival" in new Context {
      val arrival_v3_with_an_update_to_chox_time: Arrival = arrival_v2_with_chox_time.copy(ActualChox = Option(SDate("2017-01-01T10:30Z").millisSinceEpoch), Stand = Option("I will update"))
      offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Flights(Seq(arrival_v3_with_an_update_to_chox_time))))

      val expectedArrivals = List(arrival_v3_with_an_update_to_chox_time)

      crunch.portStateTestProbe.fishForMessage(5 seconds) {
        case ps: PortState =>
          val arrivals = ps.flights.values.map(_.apiFlight)
          arrivals == expectedArrivals
      }

      crunch.liveArrivalsInput.complete()

      success
    }

    "once an API (advanced passenger information) input arrives for the flight, it will update the arrivals FeedSource so that it has a LiveFeed and a ApiFeed" in new Context {

      val voyageManifests = ManifestsFeedSuccess(DqManifests("", Set(
        VoyageManifest(EventTypes.DC, PortCode("STN"), PortCode("JFK"), VoyageNumber("0001"), CarrierCode("BA"), ManifestDateOfArrival("2017-01-01"), ManifestTimeOfArrival("10:25"), List(
          PassengerInfoJson(Option(DocumentType("P")), Nationality("GBR"), "EEA", Option("22"), Option(PortCode("LHR")), "N", Option(Nationality("GBR")), Option(Nationality("GBR")), None)
        ))
      )))

      offerAndWait(crunch.manifestsLiveInput, voyageManifests)

      val expected = Set(LiveFeedSource, ApiFeedSource)

      crunch.portStateTestProbe.fishForMessage(5 seconds) {
        case ps: PortState =>
          val portStateSources = ps.flights.values.flatMap(_.apiFlight.FeedSources).toSet
          portStateSources == expected
      }

      crunch.liveArrivalsInput.complete()

      success
    }

    "once an acl and a forecast input arrives for the flight, it will update the arrivals FeedSource so that it has ACLFeed and ForecastFeed" in new Context {
      val forecastScheduled = "2017-01-01T10:25Z"

      val aclFlight = Flights(List(
        ArrivalGenerator.arrival(iata = "BA0002", schDt = forecastScheduled, actPax = Option(10), feedSources = Set(AclFeedSource))
      ))

      offerAndWait(crunch.baseArrivalsInput, ArrivalsFeedSuccess(aclFlight))

      val forecastArrival = arrival(schDt = forecastScheduled, iata = "BA0002", terminal = T1, actPax = Option(21), feedSources = Set(ForecastFeedSource))
      val forecastArrivals = ArrivalsFeedSuccess(Flights(List(forecastArrival)))

      offerAndWait(crunch.forecastArrivalsInput, forecastArrivals)

      val expected = Set(ForecastFeedSource, AclFeedSource)

      crunch.portStateTestProbe.fishForMessage(5 seconds) {
        case ps: PortState =>
          val portStateSources = ps.flights.get(forecastArrival.unique).map(_.apiFlight.FeedSources).getOrElse(Set())
          println(s"sources: $portStateSources")
          portStateSources == expected
      }

      crunch.liveArrivalsInput.complete()

      success
    }

    "Given 2 arrivals, one international and the other domestic " +
      "I should only see the international arrival in the port state" in new Context {
      val scheduled = "2017-01-01T10:25Z"

      private val arrivalInt: Arrival = ArrivalGenerator.arrival(iata = "BA0002", origin = PortCode("JFK"), schDt = scheduled, actPax = Option(10), feedSources = Set(AclFeedSource))
      private val arrivalDom: Arrival = ArrivalGenerator.arrival(iata = "BA0003", origin = PortCode("BHX"), schDt = scheduled, actPax = Option(10), feedSources = Set(AclFeedSource))

      val aclFlight = Flights(List(arrivalInt, arrivalDom))

      offerAndWait(crunch.baseArrivalsInput, ArrivalsFeedSuccess(aclFlight))

      crunch.portStateTestProbe.fishForMessage(5 seconds) {
        case ps: PortState =>
          val intExists = ps.flights.contains(UniqueArrival(arrivalInt))
          val domDoesNotExist = !ps.flights.contains(UniqueArrival(arrivalDom))
          intExists && domDoesNotExist
      }

      crunch.liveArrivalsInput.complete()

      success
    }

  }

}
