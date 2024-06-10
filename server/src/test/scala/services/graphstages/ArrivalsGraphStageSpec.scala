package services.graphstages

import controllers.ArrivalGenerator
import controllers.ArrivalGenerator.{forecast, live}
import drt.server.feeds.{ArrivalsFeedSuccess, DqManifests, ManifestsFeedResponse, ManifestsFeedSuccess}
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
import uk.gov.homeoffice.drt.time.{MilliDate, SDate, SDateLike}

import scala.collection.immutable.{List, SortedMap}
import scala.concurrent.Future
import scala.concurrent.duration._

class ArrivalsGraphStageSpec extends CrunchTestLike {
  private val date = "2017-01-01"
  private val hour = "00:25"
  val scheduled = s"${date}T${hour}Z"

  val dateNow: SDateLike = SDate(date + "T00:00Z")
  val arrival_v1_with_no_chox_time: LiveArrival = live(iata = "BA0001", schDt = scheduled, totalPax = Option(100), origin = PortCode("JFK"))

  val arrival_v2_with_chox_time: LiveArrival = arrival_v1_with_no_chox_time.copy(stand = Option("Stand1"), estimatedChox = Option(SDate(scheduled).millisSinceEpoch))

  val terminalSplits: Splits = Splits(Set(ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 100.0, None, None)), TerminalAverage, None, Percentage)

  val airportConfig: AirportConfig = defaultAirportConfig.copy(
    queuesByTerminal = defaultAirportConfig.queuesByTerminal.view.filterKeys(_ == T1).to(SortedMap),
    useTimePredictions = true,
  )
  val defaultWalkTime = 300000L
  val pcpCalc: Arrival => MilliDate = pcpFrom(airportConfig.firstPaxOffMillis, _ => defaultWalkTime)

  val setPcpTime: Seq[Arrival] => Future[Seq[Arrival]] =
    arrivals => Future.successful(arrivals.map(arrival => arrival.copy(PcpTime = Option(pcpCalc(arrival).millisSinceEpoch))))

  def withPcpTime(arrival: Arrival): Arrival =
    arrival.copy(PcpTime = Option(pcpCalc(arrival).millisSinceEpoch))

  "An Arrivals Graph Stage configured to use predicted times" should {
    implicit val crunch: CrunchGraphInputsAndProbes = runCrunchGraph(TestConfig(
      airportConfig = airportConfig,
      now = () => dateNow,
      setPcpTimes = setPcpTime
    ))

    "a third arrival with an update to the chox time will change the arrival" >> {
      val arrival_v3_with_an_update_to_chox_time =
        arrival_v2_with_chox_time.copy(actualChox = Option(SDate(scheduled).millisSinceEpoch), stand = Option("I will update"))

      offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Seq(arrival_v2_with_chox_time)))
      expectArrivals(Iterable(withPcpTime(arrival_v2_with_chox_time.copy(totalPax = Option(100)).toArrival(LiveFeedSource))))

      offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Seq(arrival_v3_with_an_update_to_chox_time)))
      expectArrivals(Iterable(withPcpTime(arrival_v3_with_an_update_to_chox_time.copy(totalPax = Option(100)).toArrival(LiveFeedSource))))

      success
    }

    "once an API (advanced passenger information) input arrives for the flight, it will update the arrivals FeedSource so that it has a LiveFeed and an ApiFeed" >> {
      val paxCount = arrival_v2_with_chox_time.totalPax.getOrElse(0)
      val voyageManifests: ManifestsFeedResponse = ManifestsFeedSuccess(DqManifests(0, Set(
        VoyageManifest(EventTypes.DC, PortCode("STN"), PortCode("JFK"), VoyageNumber("0001"), CarrierCode("BA"), ManifestDateOfArrival(date), ManifestTimeOfArrival(hour), xOfPaxType(paxCount, euIdCard))
      )))

      offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Seq(arrival_v2_with_chox_time)))
      expectArrivals(Iterable(withPcpTime(arrival_v2_with_chox_time.toArrival(LiveFeedSource))))

      offerAndWait(crunch.manifestsLiveInput, voyageManifests)
      expectFeedSources(Set(LiveFeedSource, ApiFeedSource))

      success
    }

    "once an acl and a forecast input arrives for the flight, it will update the arrivals FeedSource so that it has ACLFeed and ForecastFeed" >> {
      val forecastScheduled = "2017-01-01T10:25Z"
      val aclFlight = forecast(iata = "BA0002", schDt = forecastScheduled, totalPax = Option(10))
      val forecastArrival = forecast(schDt = forecastScheduled, iata = "BA0002", terminal = T1, totalPax = Option(21))

      offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(Seq(aclFlight)))
      offerAndWait(crunch.forecastArrivalsInput, ArrivalsFeedSuccess(List(forecastArrival)))

      expectFeedSources(Set(ForecastFeedSource, AclFeedSource))

      success
    }

    "Given 2 arrivals, one international and the other domestic " >> {
      "I should only see the international arrival in the port state" >> {
        val scheduled = "2017-01-01T10:25Z"
        val arrivalInt = ArrivalGenerator.forecast(iata = "BA0002", origin = PortCode("JFK"), schDt = scheduled, totalPax = Option(10))
        val arrivalDom = ArrivalGenerator.forecast(iata = "BA0003", origin = PortCode("BHX"), schDt = scheduled, totalPax = Option(10))

        offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(List(arrivalInt, arrivalDom)))
        expectUniqueArrival(arrivalInt.unique)
        expectNoUniqueArrival(arrivalDom.unique)

        success
      }
    }

    "Given 3 arrivals, one international, one domestic and one CTA " >> {
      "I should only see the international and CTA arrivals in the port state" >> {
        val scheduled = "2017-01-01T10:25Z"
        val arrivalInt = ArrivalGenerator.forecast(iata = "BA0002", origin = PortCode("JFK"), schDt = scheduled, totalPax = Option(10))
        val arrivalDom = ArrivalGenerator.forecast(iata = "BA0003", origin = PortCode("BHX"), schDt = scheduled, totalPax = Option(10))
        val arrivalCta = ArrivalGenerator.forecast(iata = "BA0004", origin = PortCode("IOM"), schDt = scheduled, totalPax = Option(10))

        val aclFlight = List(arrivalInt, arrivalDom, arrivalCta)

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
        val arrivalInt = ArrivalGenerator.forecast(iata = "BA0002", origin = PortCode("JFK"), schDt = scheduled, totalPax = Option(15))
        val arrivalDom = ArrivalGenerator.forecast(iata = "BA0003", origin = PortCode("BHX"), schDt = scheduled, totalPax = Option(11))
        val arrivalCta = ArrivalGenerator.forecast(iata = "BA0004", origin = PortCode("IOM"), schDt = scheduled, totalPax = Option(12))

        offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(List(arrivalInt, arrivalDom, arrivalCta)))
        expectPaxNos(15)

        success
      }
    }

    "Given an empty PortState I should only see arrivals without a suffix in the port state" >> {
      val withSuffixP = ArrivalGenerator.forecast(iata = "BA0001P", origin = PortCode("JFK"), schDt = "2017-01-01T10:25Z", totalPax = Option(10))
      val withSuffixF = ArrivalGenerator.forecast(iata = "BA0002F", origin = PortCode("JFK"), schDt = "2017-01-01T11:25Z", totalPax = Option(10))
      val withoutSuffix = ArrivalGenerator.forecast(iata = "BA0003", origin = PortCode("JFK"), schDt = "2017-01-01T12:25Z", totalPax = Option(10))

      "Given 3 international ACL arrivals, one with suffix F, another with P, and another with no suffix" >> {
        val aclFlight = List(withSuffixP, withSuffixF, withoutSuffix)

        offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(aclFlight))
        expectArrivals(Iterable(withPcpTime(withoutSuffix.toArrival(AclFeedSource))))

        success
      }

      "Given 3 international live arrivals, one with suffix F, another with P, and another with no suffix" >> {
        val aclFlight = List(withSuffixP, withSuffixF, withoutSuffix)

        offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(aclFlight))
        expectUniqueArrival(withoutSuffix.unique)

        success
      }
    }

    "Given a live arrival and a cirium arrival" >> {
      "When they have matching number, schedule, terminal and origin" >> {
        "I should see the live arrival with the cirium arrival's status merged" >> {
          val liveArrival = ArrivalGenerator.live("AA0001", schDt = scheduled, terminal = T1, origin = PortCode("AAA"), totalPax = None)
          val ciriumArrival = ArrivalGenerator.live("AA0001", schDt = scheduled, terminal = T1, origin = PortCode("AAA"), estDt = scheduled)

          offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(List(liveArrival)))
          expectUniqueArrival(liveArrival.unique)

          offerAndWait(crunch.ciriumArrivalsInput, ArrivalsFeedSuccess(List(ciriumArrival)))
          val expected = liveArrival.toArrival(LiveFeedSource).copy(
            Estimated = Option(SDate(scheduled).millisSinceEpoch),
            FeedSources = Set(LiveFeedSource, LiveBaseFeedSource),
            PassengerSources = liveArrival.toArrival(LiveFeedSource).PassengerSources ++ ciriumArrival.toArrival(LiveBaseFeedSource).PassengerSources)

          expectArrivals(Iterable(withPcpTime(expected)))

          success
        }
      }

      "When they have matching number, schedule, terminal but different origins" >> {
        "I should see the live arrival without the cirium arrival's status merged" >> {
          val liveArrival = ArrivalGenerator.live("AA0002", schDt = scheduled, terminal = T1, origin = PortCode("AAA"), totalPax = None)
          val ciriumArrival = ArrivalGenerator.live("AA0002", schDt = scheduled, terminal = T1, origin = PortCode("BBB"), totalPax = None, estDt = scheduled)
          val updatedArrival = liveArrival.copy(actualChox = Option(SDate(scheduled).millisSinceEpoch), totalPax = None)

          offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(List(liveArrival)))
          expectUniqueArrival(liveArrival.unique)

          offerAndWait(crunch.ciriumArrivalsInput, ArrivalsFeedSuccess(List(ciriumArrival)))
          expectArrivals(Iterable(withPcpTime(liveArrival.toArrival(LiveFeedSource))))

          offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(List(updatedArrival)))
          expectArrivals(Iterable(withPcpTime(updatedArrival.toArrival(LiveFeedSource))))

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
        val aclArrival = ArrivalGenerator.forecast("AA0001", schDt = aclScheduled, terminal = T1, origin = PortCode("AAA"))
        val ciriumArrival = ArrivalGenerator.live("AA0001", schDt = ciriumScheduled, terminal = T1, origin = PortCode("AAA"), estDt = scheduled)

        val crunch: CrunchGraphInputsAndProbes = runCrunchGraph(TestConfig(now = () => SDate(now)))

        offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(List(aclArrival)))

        crunch.portStateTestProbe.fishForMessage(1.second) {
          case PortState(flights, _, _) => flights.nonEmpty
        }

        offerAndWait(crunch.ciriumArrivalsInput, ArrivalsFeedSuccess(List(ciriumArrival)))

        crunch.portStateTestProbe.fishForMessage(1.second) {
          case PortState(flights, _, _) => flights.values.exists(_.apiFlight.Estimated == Option(SDate(scheduled).millisSinceEpoch))
        }

        success
      }
    }
  }

  "Given an ACL flight into T1, when it changes to T2 we should no longer see it in T1" >> {
    val scheduled = "2021-06-01T12:40"
    val aclArrival = ArrivalGenerator.forecast("AA0001", schDt = scheduled, terminal = T1, origin = PortCode("AAA"))

    val crunch: CrunchGraphInputsAndProbes = runCrunchGraph(TestConfig(now = () => SDate(scheduled)))

    offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(List(aclArrival)))

    crunch.portStateTestProbe.fishForMessage(1.second) {
      case PortState(flights, _, _) => flights.values.map(a => a.apiFlight.Terminal) == Iterable(T1)
    }

    offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(List(aclArrival.copy(terminal = T2))))

    crunch.portStateTestProbe.fishForMessage(1.second) {
      case PortState(flights, _, _) =>
        val terminals = flights.values.map(a => a.apiFlight.Terminal)
        terminals == Iterable(T2)
    }

    success
  }

  "Max pax from ACL" should {
    "Remain after receiving a live feed without max pax" in {
      val aclFlight = ArrivalGenerator.forecast("BA0001", schDt = "2021-05-01T12:50", origin = PortCode("JFK"), totalPax = Option(80), maxPax = Option(100))
      val liveFlight = ArrivalGenerator.live("BA0001", schDt = "2021-05-01T12:50", origin = PortCode("JFK"), totalPax = Option(95), maxPax = None)

      val crunch: CrunchGraphInputsAndProbes = runCrunchGraph(TestConfig(now = () => SDate("2021-05-01T12:50")))

      offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(List(aclFlight)))

      crunch.portStateTestProbe.fishForMessage(1.second) {
        case PortState(flights, _, _) =>
          flights.values.exists(a => a.apiFlight.bestPcpPaxEstimate(List(AclFeedSource)) == Option(80))
      }

      offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(List(liveFlight)))

      crunch.portStateTestProbe.fishForMessage(1.second) {
        case PortState(flights, _, _) =>
          flights.values.exists(a => a.apiFlight.bestPcpPaxEstimate(List(LiveFeedSource)) == Option(95) && a.apiFlight.MaxPax == Option(100))
      }

      success
    }
  }
}
