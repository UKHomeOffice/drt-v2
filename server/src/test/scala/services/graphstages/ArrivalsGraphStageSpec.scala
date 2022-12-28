package services.graphstages

import controllers.ArrivalGenerator.arrival
import controllers.{ArrivalGenerator, PaxFlow}
import drt.server.feeds.{ArrivalsFeedSuccess, DqManifests, ManifestsFeedResponse, ManifestsFeedSuccess}
import drt.shared.FlightsApi.Flights
import drt.shared._
import passengersplits.core.PassengerTypeCalculatorValues.DocumentType
import passengersplits.parsing.VoyageManifestParser._
import services.crunch.VoyageManifestGenerator.{euIdCard, xOfPaxType}
import services.crunch.{CrunchGraphInputsAndProbes, CrunchTestLike, TestConfig}
import uk.gov.homeoffice.drt.Nationality
import uk.gov.homeoffice.drt.arrivals.SplitStyle.Percentage
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.ports.PaxTypes.EeaMachineReadable
import uk.gov.homeoffice.drt.ports.Queues.EeaDesk
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources.TerminalAverage
import uk.gov.homeoffice.drt.ports.Terminals.{T1, T2, T3}
import uk.gov.homeoffice.drt.ports._
import uk.gov.homeoffice.drt.redlist.RedListUpdates
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

import scala.collection.immutable.List
import scala.concurrent.Future
import scala.concurrent.duration._

class ArrivalsGraphStageSpec extends CrunchTestLike {
  private val date = "2017-01-01"
  private val hour = "00:25"
  val scheduled = s"${date}T${hour}Z"

  val dateNow: SDateLike = SDate(date + "T00:00Z")
  val arrival_v1_with_no_chox_time: Arrival = arrival(iata = "BA0001", schDt = scheduled, actPax = Option(100), origin = PortCode("JFK"), feedSources = Set(LiveFeedSource))

  val arrival_v2_with_chox_time: Arrival = arrival_v1_with_no_chox_time.copy(Stand = Option("Stand1"), EstimatedChox = Option(SDate(scheduled).millisSinceEpoch))

  val terminalSplits: Splits = Splits(Set(ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 100.0, None, None)), TerminalAverage, None, Percentage)

  val airportConfig: AirportConfig = defaultAirportConfig.copy(
    queuesByTerminal = defaultAirportConfig.queuesByTerminal.filterKeys(_ == T1),
    useTimePredictions = true,
  )
  val defaultWalkTime = 300000L
  val pcpCalc: Arrival => MilliDate = PaxFlow.pcpArrivalTimeForFlight(airportConfig.timeToChoxMillis, airportConfig.firstPaxOffMillis, airportConfig.useTimePredictions)((_, _) => defaultWalkTime)(RedListUpdates.empty)

  val setPcpTime: ArrivalsDiff => Future[ArrivalsDiff] =
    diff => Future.successful(diff.copy(toUpdate = diff.toUpdate.mapValues(arrival => arrival.copy(PcpTime = Option(pcpCalc(arrival).millisSinceEpoch)))))

  def withPcpTime(arrival: Arrival): Arrival =
    arrival.copy(PcpTime = Option(pcpCalc(arrival).millisSinceEpoch))

  "An Arrivals Graph Stage configured to use predicted times" should {
    implicit val crunch: CrunchGraphInputsAndProbes = runCrunchGraph(TestConfig(
      airportConfig = airportConfig,
      now = () => dateNow,
      setPcpTimes = setPcpTime
    ))

    "set the correct pcp time given an arrival with a predicted touchdown time" >> {
      val predicted = SDate(scheduled).addMinutes(10)
      val arrival = ArrivalGenerator.arrival(iata = "BA1111", schDt = scheduled, predTouchdownDt = predicted.toISOString(), origin = PortCode("JFK"), feedSources = Set(LiveFeedSource))

      offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Flights(Iterable(arrival))))

      val expectedPcp = predicted
        .addMillis(airportConfig.timeToChoxMillis)
        .addMillis(airportConfig.firstPaxOffMillis)
        .addMillis(defaultWalkTime)

      crunch.portStateTestProbe.fishForMessage(1.seconds, s"looking for ${expectedPcp.toISOString()}") {
        case ps: PortState =>
          ps.flights.values.exists(_.apiFlight.PcpTime == Option(expectedPcp.millisSinceEpoch))
      }

      success
    }

    "a third arrival with an update to the chox time will change the arrival" >> {
      val arrival_v3_with_an_update_to_chox_time: Arrival =
        arrival_v2_with_chox_time.copy(ActualChox = Option(SDate(scheduled).millisSinceEpoch), Stand = Option("I will update"))

      offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Flights(Iterable(arrival_v2_with_chox_time))))
      expectArrivals(Iterable(withPcpTime(arrival_v2_with_chox_time.copy(TotalPax = Set(TotalPaxSource(Option(100), LiveFeedSource))))))

      offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Flights(Iterable(arrival_v3_with_an_update_to_chox_time))))
      expectArrivals(Iterable(withPcpTime(arrival_v3_with_an_update_to_chox_time.copy(TotalPax = Set(TotalPaxSource(Option(100), LiveFeedSource))))))

      success
    }

    "FeedSource is not updated if the live API does not meet the trust threshold" >> {
      val voyageManifests: ManifestsFeedResponse = ManifestsFeedSuccess(DqManifests(0, Set(
        VoyageManifest(EventTypes.DC, PortCode("STN"), PortCode("JFK"), VoyageNumber("0001"), CarrierCode("BA"), ManifestDateOfArrival(date), ManifestTimeOfArrival(hour), List(
          PassengerInfoJson(Option(DocumentType("P")), Nationality("GBR"), EeaFlag("EEA"), Option(PaxAge(22)), Option(PortCode("LHR")), InTransit("N"), Option(Nationality("GBR")), Option(Nationality("GBR")), None)
        ))
      )))

      offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Flights(Seq(arrival_v2_with_chox_time))))
      expectArrivals(Iterable(withPcpTime(arrival_v2_with_chox_time.copy(TotalPax = Set(TotalPaxSource(arrival_v2_with_chox_time.ActPax, LiveFeedSource))))))

      offerAndWait(crunch.manifestsLiveInput, voyageManifests)
      expectFeedSources(Set(LiveFeedSource))

      success
    }

    "once an API (advanced passenger information) input arrives for the flight, it will update the arrivals FeedSource so that it has a LiveFeed and a ApiFeed" >> {
      val voyageManifests: ManifestsFeedResponse = ManifestsFeedSuccess(DqManifests(0, Set(
        VoyageManifest(EventTypes.DC, PortCode("STN"), PortCode("JFK"), VoyageNumber("0001"), CarrierCode("BA"), ManifestDateOfArrival(date), ManifestTimeOfArrival(hour), xOfPaxType(arrival_v2_with_chox_time.ActPax.get, euIdCard))
      )))

      offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Flights(Seq(arrival_v2_with_chox_time))))
      expectArrivals(Iterable(withPcpTime(arrival_v2_with_chox_time.copy(TotalPax = Set(TotalPaxSource(arrival_v2_with_chox_time.ActPax, LiveFeedSource))))))

      offerAndWait(crunch.manifestsLiveInput, voyageManifests)
      expectFeedSources(Set(LiveFeedSource, ApiFeedSource))

      success
    }

    "once an acl and a forecast input arrives for the flight, it will update the arrivals FeedSource so that it has ACLFeed and ForecastFeed" >> {
      val forecastScheduled = "2017-01-01T10:25Z"
      val aclFlight = arrival(iata = "BA0002", schDt = forecastScheduled, actPax = Option(10), feedSources = Set(AclFeedSource))
      val forecastArrival = arrival(schDt = forecastScheduled, iata = "BA0002", terminal = T1, actPax = Option(21), feedSources = Set(ForecastFeedSource))

      offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(Flights(Seq(aclFlight))))
      offerAndWait(crunch.forecastArrivalsInput, ArrivalsFeedSuccess(Flights(List(forecastArrival))))

      expectFeedSources(Set(ForecastFeedSource, AclFeedSource))

      success
    }

    "Given 2 arrivals, one international and the other domestic " >> {
      "I should only see the international arrival in the port state" >> {
        val scheduled = "2017-01-01T10:25Z"
        val arrivalInt: Arrival = ArrivalGenerator.arrival(iata = "BA0002", origin = PortCode("JFK"), schDt = scheduled, actPax = Option(10), feedSources = Set(AclFeedSource))
        val arrivalDom: Arrival = ArrivalGenerator.arrival(iata = "BA0003", origin = PortCode("BHX"), schDt = scheduled, actPax = Option(10), feedSources = Set(AclFeedSource))

        offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(Flights(List(arrivalInt, arrivalDom))))
        expectUniqueArrival(arrivalInt.unique)
        expectNoUniqueArrival(arrivalDom.unique)

        success
      }
    }

    "Given 3 arrivals, one international, one domestic and one CTA " >> {
      "I should only see the international and CTA arrivals in the port state" >> {
        val scheduled = "2017-01-01T10:25Z"
        val arrivalInt: Arrival = ArrivalGenerator.arrival(iata = "BA0002", origin = PortCode("JFK"), schDt = scheduled, actPax = Option(10), feedSources = Set(AclFeedSource))
        val arrivalDom: Arrival = ArrivalGenerator.arrival(iata = "BA0003", origin = PortCode("BHX"), schDt = scheduled, actPax = Option(10), feedSources = Set(AclFeedSource))
        val arrivalCta: Arrival = ArrivalGenerator.arrival(iata = "BA0004", origin = PortCode("IOM"), schDt = scheduled, actPax = Option(10), feedSources = Set(AclFeedSource))

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
        val arrivalInt: Arrival = ArrivalGenerator.arrival(iata = "BA0002", origin = PortCode("JFK"), schDt = scheduled, actPax = Option(15), feedSources = Set(AclFeedSource))
        val arrivalDom: Arrival = ArrivalGenerator.arrival(iata = "BA0003", origin = PortCode("BHX"), schDt = scheduled, actPax = Option(11), feedSources = Set(AclFeedSource))
        val arrivalCta: Arrival = ArrivalGenerator.arrival(iata = "BA0004", origin = PortCode("IOM"), schDt = scheduled, actPax = Option(12), feedSources = Set(AclFeedSource))

        offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(Flights(List(arrivalInt, arrivalDom, arrivalCta))))
        expectPaxNos(15)

        success
      }
    }

    "Given an empty PortState I should only see arrivals without a suffix in the port state" >> {
      val withSuffixP: Arrival = ArrivalGenerator.arrival(iata = "BA0001P", origin = PortCode("JFK"), schDt = "2017-01-01T10:25Z", actPax = Option(10), feedSources = Set(AclFeedSource))
      val withSuffixF: Arrival = ArrivalGenerator.arrival(iata = "BA0002F", origin = PortCode("JFK"), schDt = "2017-01-01T11:25Z", actPax = Option(10), feedSources = Set(AclFeedSource))
      val withoutSuffix: Arrival = ArrivalGenerator.arrival(iata = "BA0003", origin = PortCode("JFK"), schDt = "2017-01-01T12:25Z", actPax = Option(10), feedSources = Set(AclFeedSource))

      "Given 3 international ACL arrivals, one with suffix F, another with P, and another with no suffix" >> {
        val aclFlight: Flights = Flights(List(withSuffixP, withSuffixF, withoutSuffix))

        offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(aclFlight))
        expectArrivals(Iterable(withPcpTime(withoutSuffix.copy(TotalPax = Set(TotalPaxSource(withoutSuffix.ActPax, AclFeedSource))))))

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
          val liveArrival = ArrivalGenerator.arrival("AA0001", schDt = scheduled, terminal = T1, origin = PortCode("AAA"))
          val ciriumArrival = ArrivalGenerator.arrival("AA0001", schDt = scheduled, terminal = T1, origin = PortCode("AAA"), estDt = scheduled)

          offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Flights(List(liveArrival))))
          expectUniqueArrival(liveArrival.unique)

          offerAndWait(crunch.ciriumArrivalsInput, ArrivalsFeedSuccess(Flights(List(ciriumArrival))))
          val expected = liveArrival.copy(
            Estimated = Option(SDate(scheduled).millisSinceEpoch),
            FeedSources = Set(LiveFeedSource, LiveBaseFeedSource),
            TotalPax = Set(TotalPaxSource(liveArrival.ActPax, LiveFeedSource), TotalPaxSource(ciriumArrival.ActPax, LiveBaseFeedSource)))

          expectArrivals(Iterable(withPcpTime(expected)))

          success
        }
      }

      "When they have matching number, schedule, terminal but different origins" >> {
        "I should see the live arrival without the cirium arrival's status merged" >> {
          val liveArrival = ArrivalGenerator.arrival("AA0002", schDt = scheduled, terminal = T1, origin = PortCode("AAA"))
          val ciriumArrival = ArrivalGenerator.arrival("AA0002", schDt = scheduled, terminal = T1, origin = PortCode("BBB"), estDt = scheduled)
          val updatedArrival = liveArrival.copy(ActualChox = Option(SDate(scheduled).millisSinceEpoch))

          offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Flights(List(liveArrival))))
          expectUniqueArrival(liveArrival.unique)

          offerAndWait(crunch.ciriumArrivalsInput, ArrivalsFeedSuccess(Flights(List(ciriumArrival))))
          expectArrivals(Iterable(withPcpTime(liveArrival.copy(FeedSources = Set(LiveFeedSource), TotalPax = Set(TotalPaxSource(updatedArrival.ActPax, LiveFeedSource))))))

          offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Flights(List(updatedArrival))))
          expectArrivals(Iterable(withPcpTime(updatedArrival.copy(FeedSources = Set(LiveFeedSource), TotalPax = Set(TotalPaxSource(updatedArrival.ActPax, LiveFeedSource))))))

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

    "When they have matching number, terminal & origin and are scheduled further than 24 hours ahead" >> {
      "I should not see the cirium arrival's data merged because we only look for fuzzy matches for the next 24 hours of flights" >> {
        val now = "2021-06-01T12:00"
        val aclScheduled = "2021-06-02T11:00"
        val ciriumScheduled = "2021-06-02T12:05"
        val aclArrival = ArrivalGenerator.arrival("AA0001", schDt = aclScheduled, terminal = T1, origin = PortCode("AAA"), actPax = Option(100))
        val ciriumArrival = ArrivalGenerator.arrival("AA0001", schDt = ciriumScheduled, terminal = T1, origin = PortCode("AAA"), estDt = ciriumScheduled)
        val forecastArrival = ArrivalGenerator.arrival("AA0001", schDt = aclScheduled, terminal = T1, origin = PortCode("AAA"), actPax = Option(101))

        val crunch: CrunchGraphInputsAndProbes = runCrunchGraph(TestConfig(now = () => SDate(now)))

        offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(Flights(List(aclArrival))))

        crunch.portStateTestProbe.fishForMessage(1.second) {
          case PortState(flights, _, _) => flights.nonEmpty
        }

        offerAndWait(crunch.ciriumArrivalsInput, ArrivalsFeedSuccess(Flights(List(ciriumArrival))))
        offerAndWait(crunch.forecastArrivalsInput, ArrivalsFeedSuccess(Flights(List(forecastArrival))))

        val expected = forecastArrival.copy(
          FeedSources = Set(AclFeedSource, ForecastFeedSource),
          TotalPax = Set(
            TotalPaxSource(aclArrival.ActPax, AclFeedSource),
            TotalPaxSource(forecastArrival.ActPax, ForecastFeedSource),
          ))

        crunch.portStateTestProbe.fishForMessage(1.second) {
          case PortState(flights, _, _) =>
            flights.values.exists(_.apiFlight == expected)
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

  "Given a live feed flight into T1, when it changes to T2 we should no longer see it in T1" >> {
    val scheduled = "2021-06-01T12:40"
    val arrival = ArrivalGenerator.arrival("AA0001", schDt = scheduled, terminal = T1, origin = PortCode("AAA"))

    val crunch: CrunchGraphInputsAndProbes = runCrunchGraph(TestConfig(now = () => SDate(scheduled)))

    offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Flights(List(arrival))))

    crunch.portStateTestProbe.fishForMessage(1.second) {
      case PortState(flights, _, _) => flights.values.map(a => a.apiFlight.Terminal) == Iterable(T1)
    }

    offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Flights(List(arrival.copy(Terminal = T2)))))

    crunch.portStateTestProbe.fishForMessage(1.second) {
      case PortState(flights, _, _) =>
        val terminals = flights.values.map(a => a.apiFlight.Terminal)
        terminals == Iterable(T2)
    }

    success
  }

  "terminalRemovals" should {
    val arrivalT1 = ArrivalGenerator.arrival("BA0001", terminal = T1, schDt = "2021-05-01T12:50", origin = PortCode("JFK"))
    val arrivalT2 = arrivalT1.copy(Terminal = T2)
    "give no removals given no arrivals" >> {
      val removals = ArrivalsGraphStage.terminalRemovals(Seq(), Seq())
      removals === Iterable.empty
    }
    "give no removals given a new arrival" >> {
      val removals = ArrivalsGraphStage.terminalRemovals(Seq(arrivalT1), Seq())
      removals === Iterable.empty
    }
    "give no removals given no incoming, but one existing arrival" >> {
      val removals = ArrivalsGraphStage.terminalRemovals(Seq(), Seq(arrivalT1))
      removals === Iterable.empty
    }
    "give no removals given no changes" >> {
      val removals = ArrivalsGraphStage.terminalRemovals(Seq(arrivalT1), Seq(arrivalT1))
      removals === Iterable.empty
    }
    "give 1 removal given an arrival at T1 which was previously at T2" >> {
      val removals = ArrivalsGraphStage.terminalRemovals(Seq(arrivalT1), Seq(arrivalT2))
      removals === Iterable(arrivalT2)
    }
    "give 1 removal given an arrival at T2 which was previously at T1" >> {
      val removals = ArrivalsGraphStage.terminalRemovals(Seq(arrivalT2), Seq(arrivalT1))
      removals === Iterable(arrivalT1)
    }
  }

  "Unmatched arrivals" >> {
    "Given 3 incoming arrivals, and 2 matching existing arrivals" >> {
      "The percentage unmatched should be 33 when rounded" >> {
        val incoming = Seq(
          ArrivalGenerator.arrival(iata = "BA001", schDt = "2022-06-01T00:00", terminal = T1, origin = PortCode("JFK")),
          ArrivalGenerator.arrival(iata = "BA002", schDt = "2022-06-01T00:05", terminal = T2, origin = PortCode("ABC")),
          ArrivalGenerator.arrival(iata = "BA003", schDt = "2022-06-01T00:30", terminal = T3, origin = PortCode("ZYX")),
        ).map(a => a.unique)

        val existing = incoming.take(2)

        ArrivalsGraphStage.unmatchedArrivalsPercentage(incoming, existing).toInt === 33
      }
    }

    "Given 0 incoming arrivals, and 0 matching existing arrivals" >> {
      "The percentage unmatched should be 0" >> {
        ArrivalsGraphStage.unmatchedArrivalsPercentage(Seq(), Seq()).toInt === 0
      }
    }
  }
}
