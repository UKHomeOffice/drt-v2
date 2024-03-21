package services.crunch

import controllers.ArrivalGenerator
import drt.server.feeds.{ArrivalsFeedSuccess, DqManifests, ManifestsFeedResponse, ManifestsFeedSuccess}
import drt.shared._
import passengersplits.core.PassengerTypeCalculatorValues.DocumentType
import passengersplits.parsing.VoyageManifestParser._
import uk.gov.homeoffice.drt.Nationality
import uk.gov.homeoffice.drt.arrivals.{CarrierCode, EventTypes, LiveArrival, VoyageNumber}
import uk.gov.homeoffice.drt.ports.PaxTypesAndQueues.gbrNationalChildToDesk
import uk.gov.homeoffice.drt.ports.Terminals.{T1, Terminal}
import uk.gov.homeoffice.drt.ports.{PaxAge, PaxTypeAndQueue, PortCode, Queues}
import uk.gov.homeoffice.drt.time.SDate

import scala.collection.immutable.{List, Seq, SortedMap}
import scala.concurrent.duration._

class ApiPaxNosCrunchSpec extends CrunchTestLike {
  sequential
  isolated

  val tenMinutes: Double = 600d / 60
  val procTimes: Map[Terminal, Map[PaxTypeAndQueue, Double]] = Map(T1 -> Map(gbrNationalChildToDesk -> tenMinutes))

  val scheduled = "2019-11-20T00:00Z"

  val flights: Seq[LiveArrival] = List(ArrivalGenerator.arrival(iata = "BA0001", schDt = scheduled, origin = PortCode("JFK")))

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
          queuesByTerminal = SortedMap(T1 -> Seq(Queues.EeaDesk))
        )))

      offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(flights))
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
          queuesByTerminal = SortedMap(T1 -> Seq(Queues.EeaDesk))
        )))

      offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(flights))
      offerAndWait(crunch.manifestsLiveInput, manifests)

      val expected = Map(T1 -> Map(Queues.EeaDesk -> Seq(20, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)))

      crunch.portStateTestProbe.fishForMessage(2.seconds) {
        case ps: PortState => workLoadsFromPortState(ps, 15) == expected
      }

      success
    }
  }
}
