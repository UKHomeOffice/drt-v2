package services.graphstages

import controllers.ArrivalGenerator
import controllers.ArrivalGenerator.arrival
import drt.server.feeds.{ArrivalsFeedSuccess, DqManifests, ManifestsFeedResponse, ManifestsFeedSuccess}
import drt.shared.FlightsApi.Flights
import drt.shared._
import passengersplits.core.PassengerTypeCalculatorValues.DocumentType
import passengersplits.parsing.VoyageManifestParser._
import services.PcpArrival.pcpFrom
import services.crunch.VoyageManifestGenerator.{euIdCard, xOfPaxType}
import services.crunch.{CrunchGraphInputsAndProbes, CrunchTestLike, TestConfig}
import uk.gov.homeoffice.drt.Nationality
import uk.gov.homeoffice.drt.arrivals.SplitStyle.Percentage
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.ports.PaxTypes.EeaMachineReadable
import uk.gov.homeoffice.drt.ports.Queues.EeaDesk
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources.TerminalAverage
import uk.gov.homeoffice.drt.ports.Terminals.{T1, T2}
import uk.gov.homeoffice.drt.ports._
import uk.gov.homeoffice.drt.prediction.arrival.OffScheduleModelAndFeatures
import uk.gov.homeoffice.drt.time.{MilliDate, SDate, SDateLike}

import scala.collection.immutable.{List, SortedMap}
import scala.concurrent.Future
import scala.concurrent.duration._

class ArrivalsGraphStageSpec extends CrunchTestLike {
  private val date = "2017-01-01"
  private val hour = "00:25"
  val scheduled = s"${date}T${hour}Z"

  val dateNow: SDateLike = SDate(date + "T00:00Z")
  val arrival_v1_with_no_chox_time: Arrival = arrival(iata = "BA0001", schDt = scheduled, passengerSources = Map(LiveFeedSource -> Passengers(Option(100), None)), origin = PortCode("JFK"), feedSources = Set(LiveFeedSource))

  val arrival_v2_with_chox_time: Arrival = arrival_v1_with_no_chox_time.copy(Stand = Option("Stand1"), EstimatedChox = Option(SDate(scheduled).millisSinceEpoch))

  val terminalSplits: Splits = Splits(Set(ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 100.0, None, None)), TerminalAverage, None, Percentage)

  val airportConfig: AirportConfig = defaultAirportConfig.copy(
    queuesByTerminal = defaultAirportConfig.queuesByTerminal.view.filterKeys(_ == T1).to(SortedMap),
    useTimePredictions = true,
  )
  val defaultWalkTime = 300000L
  val pcpCalc: Arrival => MilliDate = pcpFrom(airportConfig.firstPaxOffMillis, _ => defaultWalkTime)

  val setPcpTime: ArrivalsDiff => Future[ArrivalsDiff] =
    diff => Future.successful(diff.copy(toUpdate = diff.toUpdate.view.mapValues(arrival => arrival.copy(PcpTime = Option(pcpCalc(arrival).millisSinceEpoch))).to(SortedMap)))

  def withPcpTime(arrival: Arrival): Arrival =
    arrival.copy(PcpTime = Option(pcpCalc(arrival).millisSinceEpoch))

  "An Arrivals Graph Stage configured to use predicted times" should {
    implicit val crunch: CrunchGraphInputsAndProbes = runCrunchGraph(TestConfig(
      airportConfig = airportConfig,
      now = () => dateNow,
      setPcpTimes = setPcpTime
    ))

    "set the correct pcp time given an arrival with a predicted touchdown time" >> {
      val offScheduleMinutes = 10
      val predictions = Predictions(0L, Map(OffScheduleModelAndFeatures.targetName -> offScheduleMinutes))
      val arrival = ArrivalGenerator.arrival(iata = "BA1111", schDt = scheduled, predictions = predictions, origin = PortCode("JFK"), feedSources = Set(LiveFeedSource))

      offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Flights(Iterable(arrival))))

      val expectedPcp = SDate(scheduled)
        .addMinutes(offScheduleMinutes)
        .addMinutes(Arrival.defaultMinutesToChox)
        .addMillis(airportConfig.firstPaxOffMillis)
        .addMillis(defaultWalkTime)

      crunch.portStateTestProbe.fishForMessage(1.seconds, s"looking for ${expectedPcp.toISOString}") {
        case ps: PortState =>
          ps.flights.values.exists(_.apiFlight.PcpTime == Option(expectedPcp.millisSinceEpoch))
      }

      success
    }

    "a third arrival with an update to the chox time will change the arrival" >> {
      val arrival_v3_with_an_update_to_chox_time: Arrival =
        arrival_v2_with_chox_time.copy(ActualChox = Option(SDate(scheduled).millisSinceEpoch), Stand = Option("I will update"))

      offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Flights(Iterable(arrival_v2_with_chox_time))))
      expectArrivals(Iterable(withPcpTime(arrival_v2_with_chox_time.copy(PassengerSources = Map(LiveFeedSource -> Passengers(Option(100), None))))))

      offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Flights(Iterable(arrival_v3_with_an_update_to_chox_time))))
      expectArrivals(Iterable(withPcpTime(arrival_v3_with_an_update_to_chox_time.copy(PassengerSources = Map(LiveFeedSource -> Passengers(Option(100), None))))))

      success
    }

    "FeedSource is not updated if the live API does not meet the trust threshold" >> {
      val voyageManifests: ManifestsFeedResponse = ManifestsFeedSuccess(DqManifests(0, Set(
        VoyageManifest(EventTypes.DC, PortCode("STN"), PortCode("JFK"), VoyageNumber("0001"), CarrierCode("BA"), ManifestDateOfArrival(date), ManifestTimeOfArrival(hour), List(
          PassengerInfoJson(Option(DocumentType("P")), Nationality("GBR"), EeaFlag("EEA"), Option(PaxAge(22)), Option(PortCode("LHR")), InTransit("N"), Option(Nationality("GBR")), Option(Nationality("GBR")), None)
        ))
      )))

      offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Flights(Seq(arrival_v2_with_chox_time))))
      expectArrivals(Iterable(withPcpTime(arrival_v2_with_chox_time.copy(PassengerSources = arrival_v2_with_chox_time.PassengerSources))))

      offerAndWait(crunch.manifestsLiveInput, voyageManifests)
      expectFeedSources(Set(LiveFeedSource))

      success
    }

    "once an API (advanced passenger information) input arrives for the flight, it will update the arrivals FeedSource so that it has a LiveFeed and an ApiFeed" >> {
      val paxCount = arrival_v2_with_chox_time.PassengerSources.get(LiveFeedSource).flatMap(_.actual).getOrElse(0)
      val voyageManifests: ManifestsFeedResponse = ManifestsFeedSuccess(DqManifests(0, Set(
        VoyageManifest(EventTypes.DC, PortCode("STN"), PortCode("JFK"), VoyageNumber("0001"), CarrierCode("BA"), ManifestDateOfArrival(date), ManifestTimeOfArrival(hour), xOfPaxType(paxCount, euIdCard))
      )))

      offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Flights(Seq(arrival_v2_with_chox_time))))
      expectArrivals(Iterable(withPcpTime(arrival_v2_with_chox_time.copy(PassengerSources = arrival_v2_with_chox_time.PassengerSources))))

      offerAndWait(crunch.manifestsLiveInput, voyageManifests)
      expectFeedSources(Set(LiveFeedSource, ApiFeedSource))

      success
    }

    "once an acl and a forecast input arrives for the flight, it will update the arrivals FeedSource so that it has ACLFeed and ForecastFeed" >> {
      val forecastScheduled = "2017-01-01T10:25Z"
      val aclFlight = arrival(iata = "BA0002", schDt = forecastScheduled, passengerSources = Map(AclFeedSource -> Passengers(Option(10), None)), feedSources = Set(AclFeedSource))
      val forecastArrival = arrival(schDt = forecastScheduled, iata = "BA0002", terminal = T1, passengerSources = Map(ForecastFeedSource -> Passengers(Option(21), None)), feedSources = Set(ForecastFeedSource))

      offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(Flights(Seq(aclFlight))))
      offerAndWait(crunch.forecastArrivalsInput, ArrivalsFeedSuccess(Flights(List(forecastArrival))))

      expectFeedSources(Set(ForecastFeedSource, AclFeedSource))

      success
    }

    "Given 2 arrivals, one international and the other domestic " >> {
      "I should only see the international arrival in the port state" >> {
        val scheduled = "2017-01-01T10:25Z"
        val arrivalInt: Arrival = ArrivalGenerator.arrival(iata = "BA0002", origin = PortCode("JFK"), schDt = scheduled, passengerSources = Map(AclFeedSource -> Passengers(Option(10), None)), feedSources = Set(AclFeedSource))
        val arrivalDom: Arrival = ArrivalGenerator.arrival(iata = "BA0003", origin = PortCode("BHX"), schDt = scheduled, passengerSources = Map(AclFeedSource -> Passengers(Option(10), None)), feedSources = Set(AclFeedSource))

        offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(Flights(List(arrivalInt, arrivalDom))))
        expectUniqueArrival(arrivalInt.unique)
        expectNoUniqueArrival(arrivalDom.unique)

        success
      }
    }

    "Given 3 arrivals, one international, one domestic and one CTA " >> {
      "I should only see the international and CTA arrivals in the port state" >> {
        val scheduled = "2017-01-01T10:25Z"
        val arrivalInt: Arrival = ArrivalGenerator.arrival(iata = "BA0002", origin = PortCode("JFK"), schDt = scheduled, passengerSources = Map(AclFeedSource -> Passengers(Option(10), None)), feedSources = Set(AclFeedSource))
        val arrivalDom: Arrival = ArrivalGenerator.arrival(iata = "BA0003", origin = PortCode("BHX"), schDt = scheduled, passengerSources = Map(AclFeedSource -> Passengers(Option(10), None)), feedSources = Set(AclFeedSource))
        val arrivalCta: Arrival = ArrivalGenerator.arrival(iata = "BA0004", origin = PortCode("IOM"), schDt = scheduled, passengerSources = Map(AclFeedSource -> Passengers(Option(10), None)), feedSources = Set(AclFeedSource))

        val aclFlight: Flights = Flights(List(arrivalInt, arrivalDom, arrivalCta))

        offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(aclFlight))
        expectUniqueArrival(arrivalInt.unique)
        expectUniqueArrival(arrivalCta.unique)
        expectNoUniqueArrival(arrivalDom.unique)

        success
      }
    }

    "Given 3 arrivals, one international, one domestic and one CTA " >> {
      "I should only see the international flight's passengers and NOT and domestic or CTA pax" >> {
        val scheduled = "2017-01-01T00:05Z"
        val arrivalInt: Arrival = ArrivalGenerator.arrival(iata = "BA0002", origin = PortCode("JFK"), schDt = scheduled, passengerSources = Map(AclFeedSource -> Passengers(Option(15), None)), feedSources = Set(AclFeedSource))
        val arrivalDom: Arrival = ArrivalGenerator.arrival(iata = "BA0003", origin = PortCode("BHX"), schDt = scheduled, passengerSources = Map(AclFeedSource -> Passengers(Option(11), None)), feedSources = Set(AclFeedSource))
        val arrivalCta: Arrival = ArrivalGenerator.arrival(iata = "BA0004", origin = PortCode("IOM"), schDt = scheduled, passengerSources = Map(AclFeedSource -> Passengers(Option(12), None)), feedSources = Set(AclFeedSource))

        offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(Flights(List(arrivalInt, arrivalDom, arrivalCta))))
        expectPaxNos(15)

        success
      }
    }

    "Given an empty PortState I should only see arrivals without a suffix in the port state" >> {
      val withSuffixP: Arrival = ArrivalGenerator.arrival(iata = "BA0001P", origin = PortCode("JFK"), schDt = "2017-01-01T10:25Z", passengerSources = Map(AclFeedSource -> Passengers(Option(10), None)), feedSources = Set(AclFeedSource))
      val withSuffixF: Arrival = ArrivalGenerator.arrival(iata = "BA0002F", origin = PortCode("JFK"), schDt = "2017-01-01T11:25Z", passengerSources = Map(AclFeedSource -> Passengers(Option(10), None)), feedSources = Set(AclFeedSource))
      val withoutSuffix: Arrival = ArrivalGenerator.arrival(iata = "BA0003", origin = PortCode("JFK"), schDt = "2017-01-01T12:25Z", passengerSources = Map(AclFeedSource -> Passengers(Option(10), None)), feedSources = Set(AclFeedSource))

      "Given 3 international ACL arrivals, one with suffix F, another with P, and another with no suffix" >> {
        val aclFlight: Flights = Flights(List(withSuffixP, withSuffixF, withoutSuffix))

        offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(aclFlight))
        expectArrivals(Iterable(withPcpTime(withoutSuffix.copy(PassengerSources = withoutSuffix.PassengerSources))))

        success
      }

      "Given 3 international live arrivals, one with suffix F, another with P, and another with no suffix" >> {
        val aclFlight: Flights = Flights(List(withSuffixP, withSuffixF, withoutSuffix))

        offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(aclFlight))
        expectUniqueArrival(withoutSuffix.unique)

        success
      }
    }

    "Given a live arrival and a cirium arrival" >> {
      "When they have matching number, schedule, terminal and origin" >> {
        "I should see the live arrival with the cirium arrival's status merged" >> {
          val liveArrival = ArrivalGenerator.arrival("AA0001", schDt = scheduled, terminal = T1, origin = PortCode("AAA"),
            feedSources = Set(LiveFeedSource), passengerSources = Map(LiveFeedSource -> Passengers(None, None)))
          val ciriumArrival = ArrivalGenerator.arrival("AA0001", schDt = scheduled, terminal = T1, origin = PortCode("AAA"), estDt = scheduled,
            feedSources = Set(LiveBaseFeedSource), passengerSources = Map(LiveBaseFeedSource -> Passengers(None, None)))

          offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Flights(List(liveArrival))))
          expectUniqueArrival(liveArrival.unique)

          offerAndWait(crunch.ciriumArrivalsInput, ArrivalsFeedSuccess(Flights(List(ciriumArrival))))
          val expected = liveArrival.copy(
            Estimated = Option(SDate(scheduled).millisSinceEpoch),
            FeedSources = Set(LiveFeedSource, LiveBaseFeedSource),
            PassengerSources = liveArrival.PassengerSources ++ ciriumArrival.PassengerSources)

          expectArrivals(Iterable(withPcpTime(expected)))

          success
        }
      }

      "When they have matching number, schedule, terminal but different origins" >> {
        "I should see the live arrival without the cirium arrival's status merged" >> {
          val liveArrival = ArrivalGenerator.arrival("AA0002", schDt = scheduled, terminal = T1, origin = PortCode("AAA"),
            feedSources = Set(LiveFeedSource), passengerSources = Map(LiveFeedSource -> Passengers(None, None)))
          val ciriumArrival = ArrivalGenerator.arrival("AA0002", schDt = scheduled, terminal = T1, origin = PortCode("BBB"),
            feedSources = Set(LiveFeedSource), passengerSources = Map(LiveFeedSource -> Passengers(None, None)), estDt = scheduled)
          val updatedArrival = liveArrival.copy(ActualChox = Option(SDate(scheduled).millisSinceEpoch), PassengerSources = Map(LiveFeedSource -> Passengers(None, None)))

          offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Flights(List(liveArrival))))
          expectUniqueArrival(liveArrival.unique)

          offerAndWait(crunch.ciriumArrivalsInput, ArrivalsFeedSuccess(Flights(List(ciriumArrival))))
          expectArrivals(Iterable(withPcpTime(liveArrival.copy(FeedSources = Set(LiveFeedSource), PassengerSources = updatedArrival.PassengerSources))))

          offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Flights(List(updatedArrival))))
          expectArrivals(Iterable(withPcpTime(updatedArrival.copy(FeedSources = Set(LiveFeedSource), PassengerSources = updatedArrival.PassengerSources))))

          success
        }
      }
    }
  }

  "Given an ACL arrival and a cirium arrival scheduled within 5 minutes of each other" >> {
    "When they have matching number, terminal & origin and are scheduled within the next 24 hours" >> {
      "I should see cirium arrival's data merged" >> {
        val now = "2021-06-01T12:00"
        val aclScheduled = "2021-06-02T11:00"
        val ciriumScheduled = "2021-06-02T11:05"
        val aclArrival = ArrivalGenerator.arrival("AA0001", schDt = aclScheduled, terminal = T1, origin = PortCode("AAA"))
        val ciriumArrival = ArrivalGenerator.arrival("AA0001", schDt = ciriumScheduled, terminal = T1, origin = PortCode("AAA"), estDt = scheduled)

        val crunch: CrunchGraphInputsAndProbes = runCrunchGraph(TestConfig(now = () => SDate(now)))

        offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(Flights(List(aclArrival))))

        crunch.portStateTestProbe.fishForMessage(1.second) {
          case PortState(flights, _, _) => flights.nonEmpty
        }

        offerAndWait(crunch.ciriumArrivalsInput, ArrivalsFeedSuccess(Flights(List(ciriumArrival))))

        crunch.portStateTestProbe.fishForMessage(1.second) {
          case PortState(flights, _, _) => flights.values.exists(_.apiFlight.Estimated == Option(SDate(scheduled).millisSinceEpoch))
        }

        success
      }
    }
  }

  "Given an ACL flight into T1, when it changes to T2 we should no longer see it in T1" >> {
    val scheduled = "2021-06-01T12:40"
    val aclArrival = ArrivalGenerator.arrival("AA0001", schDt = scheduled, terminal = T1, origin = PortCode("AAA"))

    val crunch: CrunchGraphInputsAndProbes = runCrunchGraph(TestConfig(now = () => SDate(scheduled)))

    offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(Flights(List(aclArrival))))

    crunch.portStateTestProbe.fishForMessage(1.second) {
      case PortState(flights, _, _) => flights.values.map(a => a.apiFlight.Terminal) == Iterable(T1)
    }

    offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(Flights(List(aclArrival.copy(Terminal = T2)))))

    crunch.portStateTestProbe.fishForMessage(1.second) {
      case PortState(flights, _, _) =>
        val terminals = flights.values.map(a => a.apiFlight.Terminal)
        terminals == Iterable(T2)
    }

    success
  }

  "Max pax from ACL" should {
    "Remain after receiving a live feed without max pax" in {
      val aclFlight = ArrivalGenerator.arrival("BA0001", schDt = "2021-05-01T12:50", origin = PortCode("JFK"), passengerSources = Map(AclFeedSource -> Passengers(Option(80), None)), maxPax = Option(100))
      val liveFlight = ArrivalGenerator.arrival("BA0001", schDt = "2021-05-01T12:50", origin = PortCode("JFK"), passengerSources = Map(LiveFeedSource -> Passengers(Option(95), None)), maxPax = None)

      val crunch: CrunchGraphInputsAndProbes = runCrunchGraph(TestConfig(now = () => SDate("2021-05-01T12:50")))

      offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(Flights(List(aclFlight))))

      crunch.portStateTestProbe.fishForMessage(1.second) {
        case PortState(flights, _, _) =>
          flights.values.exists(a => a.apiFlight.bestPcpPaxEstimate(List(AclFeedSource)) == Option(80))
      }

      offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Flights(List(liveFlight))))

      crunch.portStateTestProbe.fishForMessage(1.second) {
        case PortState(flights, _, _) =>
          flights.values.exists(a => a.apiFlight.bestPcpPaxEstimate(List(LiveFeedSource)) == Option(95) && a.apiFlight.MaxPax == Option(100))
      }

      success
    }
  }
}
