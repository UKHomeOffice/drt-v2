package services.crunch

import controllers.ArrivalGenerator
import drt.server.feeds.{ArrivalsFeedSuccess, DqManifests, ManifestsFeedResponse, ManifestsFeedSuccess}
import drt.shared._
import manifests.passengers.{BestAvailableManifest, ManifestPaxCount}
import uk.gov.homeoffice.drt.Nationality
import uk.gov.homeoffice.drt.arrivals.{CarrierCode, EventTypes, ForecastArrival, VoyageNumber}
import uk.gov.homeoffice.drt.models.DocumentType.Passport
import uk.gov.homeoffice.drt.models._
import uk.gov.homeoffice.drt.ports.PaxTypesAndQueues.gbrNationalChildToDesk
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources.Historical
import uk.gov.homeoffice.drt.ports.Terminals.{T1, Terminal}
import uk.gov.homeoffice.drt.ports._
import uk.gov.homeoffice.drt.time.{LocalDate, SDate}

import scala.collection.immutable.{List, Seq, SortedMap}
import scala.concurrent.duration._

class ApiPaxNosCrunchSpec extends CrunchTestLike {
  sequential
  isolated

  val oneMinute: Double = 60d / 60
  val procTimes: Map[Terminal, Map[PaxTypeAndQueue, Double]] = Map(T1 -> Map(gbrNationalChildToDesk -> oneMinute))

  val scheduled = "2019-11-20T00:00Z"

  private val forecastArrival: ForecastArrival = ArrivalGenerator.forecast(iata = "BA0001", schDt = scheduled, origin = PortCode("JFK"))
  private val flights = List(forecastArrival)

  val manifests: ManifestsFeedResponse =
    ManifestsFeedSuccess(DqManifests(0, Set(
      VoyageManifest(EventTypes.DC, defaultAirportConfig.portCode, PortCode("JFK"), VoyageNumber("0001"),
        CarrierCode("BA"), ManifestDateOfArrival("2019-11-20"), ManifestTimeOfArrival("00:00"),
        List(
          PassengerInfoJson(Option(DocumentType("P")), Nationality("GBR"), EeaFlag("EEA"), Option(PaxAge(9)),
            Option(PortCode("LHR")), InTransit("N"), Option(Nationality("GBR")), Option(Nationality("GBR")), None),
          PassengerInfoJson(Option(DocumentType("P")), Nationality("GBR"), EeaFlag("EEA"), Option(PaxAge(9)),
            Option(PortCode("LHR")), InTransit("N"), Option(Nationality("GBR")), Option(Nationality("GBR")), None)
        ))
    )))

  "Given a flight with no pax numbers and a Manifest of 2 passengers " >> {
    "Then we should get 2 passengers in PCP Pax" >> {
      val crunch = runCrunchGraph(TestConfig(
        now = () => SDate(scheduled),
        airportConfig = defaultAirportConfig.copy(
          terminalProcessingTimes = procTimes,
          queuesByTerminal = SortedMap(LocalDate(2014, 1, 1) -> SortedMap(T1 -> Seq(Queues.EeaDesk)))
        )))

      offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(flights))
      waitForFlightsInPortState(crunch.portStateTestProbe)
      offerAndWait(crunch.manifestsLiveInput, manifests)

      val expected = Map(T1 -> Map(Queues.EeaDesk -> Seq(2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)))

      crunch.portStateTestProbe.fishForMessage(2.seconds) {
        case ps: PortState => paxLoadsFromPortState(ps, 15) == expected
      }

      success
    }
  }

  "Given a flight with no pax numbers and a Manifest of 2 passengers " >> {
    "Then we should get workload for the 2 passengers Port State" >> {
      val crunch = runCrunchGraph(TestConfig(
        now = () => SDate(scheduled),
        airportConfig = defaultAirportConfig.copy(
          terminalProcessingTimes = procTimes,
          queuesByTerminal = SortedMap(LocalDate(2014, 1, 1) -> SortedMap(T1 -> Seq(Queues.EeaDesk)))
        )))

      offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(flights))
      waitForFlightsInPortState(crunch.portStateTestProbe)
      offerAndWait(crunch.manifestsLiveInput, manifests)

      val expected = Map(T1 -> Map(Queues.EeaDesk -> Seq(2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)))

      crunch.portStateTestProbe.fishForMessage(2.seconds) {
        case ps: PortState => workLoadsFromPortState(ps, 15) == expected
      }

      success
    }
  }

  def manifest(arrival: ForecastArrival): BestAvailableManifest = BestAvailableManifest(
    source = Historical,
    PortCode("LHR"),
    PortCode("JFK"),
    VoyageNumber(arrival.voyageNumber),
    CarrierCode("BA"),
    SDate(arrival.scheduled),
    Seq(
      ManifestPassengerProfile(Nationality("GBR"), Option(Passport), Option(PaxAge(9)), inTransit = false, Option("a")),
      ManifestPassengerProfile(Nationality("GBR"), Option(Passport), Option(PaxAge(23)), inTransit = false, Option("b")),
    ),
    Option(EventTypes.DC),
  )

  "Given an historic manifests provider and a flight with no historic splits" >> {
    "Then the flight should have historic splits added to it" >> {
      val arrival = forecastArrival
      val crunch = runCrunchGraph(TestConfig(
        now = () => SDate(scheduled),
        historicManifestLookup = Option(MockManifestLookupService(maybeBestManifest = Option(manifest(arrival))))
      ))

      offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(flights))

      crunch.portStateTestProbe.fishForMessage(2.seconds) {
        case PortState(flights, _, _) =>
          val maybeSplits = flights.values.headOption.map(_.splits)
          maybeSplits.exists(_.exists(_.source == Historical))
      }

      success
    }
  }

  "Given an historic pax provider and a flight with no forecast pax nos" >> {
    "Then the flight should have historic pax added to it" >> {
      val arrival = forecastArrival
      val crunch = runCrunchGraph(TestConfig(
        now = () => SDate(scheduled),
        historicManifestLookup = Option(MockManifestLookupService(maybeManifestPaxCount = Option(ManifestPaxCount(manifest(arrival), Historical))))
      ))

      offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(flights))

      crunch.portStateTestProbe.fishForMessage(2.seconds) {
        case PortState(flights, _, _) =>
          val paxSources = flights.values.headOption.map(_.apiFlight.PassengerSources)
          paxSources.exists(_.exists(_._1 == HistoricApiFeedSource))
      }

      success
    }
  }
}
