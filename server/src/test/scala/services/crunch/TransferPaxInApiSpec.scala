package services.crunch

import controllers.ArrivalGenerator
import drt.shared.FlightsApi.Flights
import drt.shared.PaxTypes._
import drt.shared.PaxTypesAndQueues.eeaMachineReadableToDesk
import uk.gov.homeoffice.drt.ports.Queues.EeaDesk
import uk.gov.homeoffice.drt.ports.Terminals.T1
import drt.shared._
import passengersplits.parsing.VoyageManifestParser.{ManifestDateOfArrival, ManifestTimeOfArrival, VoyageManifest}
import server.feeds.{ArrivalsFeedSuccess, DqManifests, ManifestsFeedSuccess}
import services.SDate
import services.crunch.VoyageManifestGenerator.{euPassport, inTransitFlag}

import scala.collection.immutable.{Seq, SortedMap}
import scala.concurrent.duration._

class TransferPaxInApiSpec extends CrunchTestLike {
  sequential
  isolated

  val fiveMinutes: Double = 600d / 60

  val lhrAirportConfig: AirportConfig = defaultAirportConfig.copy(
    portCode = PortCode("LHR"),
    terminalProcessingTimes = Map(T1 -> Map(eeaMachineReadableToDesk -> fiveMinutes)),
    queuesByTerminal = SortedMap(T1 -> Seq(EeaDesk)),
    terminalPaxTypeQueueAllocation = Map(
      T1 -> Map(
        EeaMachineReadable -> List(Queues.EeaDesk -> 1.0),
        EeaBelowEGateAge -> List(Queues.EeaDesk -> 1.0),
        EeaNonMachineReadable -> List(Queues.EeaDesk -> 1.0),
        NonVisaNational -> List(Queues.NonEeaDesk -> 1.0),
        VisaNational -> List(Queues.NonEeaDesk -> 1.0),
        B5JPlusNational -> List(Queues.EGate -> 0.6, Queues.EeaDesk -> 0.4),
        B5JPlusNationalBelowEGateAge -> List(Queues.EeaDesk -> 1)
      )
    ),
    hasTransfer = true
  )

  "Given a flight with transfer passengers in the port feed " +
    "Then these passengers should not be included in the total pax when using flight pax nos" >> {

    val scheduled = "2017-01-01T00:00Z"

    val flights = Flights(Seq(
      ArrivalGenerator.arrival(
        schDt = scheduled,
        iata = "BA0001",
        terminal = T1,
        actPax = Option(2),
        tranPax = Option(1))
    ))

    val crunch = runCrunchGraph(TestConfig(
      now = () => SDate(scheduled),
      airportConfig = lhrAirportConfig
    ))

    offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(flights))

    val expected = 1

    crunch.portStateTestProbe.fishForMessage(1 seconds) {
      case ps: PortState =>
        val totalPaxAtPCP = paxLoadsFromPortState(ps, 60, 0)
          .values
          .flatMap((_.values))
          .flatten
          .sum
        totalPaxAtPCP == expected
    }

    success
  }

  "Given a flight that is using API for passenger numbers, and which has Transit passengers in the API data " +
    "Then these passengers should not be included in the total pax when using API pax nos" >> {

    val scheduled = "2017-01-01T00:00Z"

    val flights = Flights(Seq(
      ArrivalGenerator.arrival(
        origin = PortCode("JFK"),
        schDt = scheduled,
        iata = "TST001",
        terminal = T1,
        actPax = None,
        tranPax = None  )
    ))

    val portCode = PortCode("LHR")

    val inputManifests = ManifestsFeedSuccess(
      DqManifests("",
        Set(
          VoyageManifest(EventTypes.CI,
            portCode,
            PortCode("JFK"),
            VoyageNumber(1),
            CarrierCode("TS"),
            ManifestDateOfArrival("2017-01-01"),
            ManifestTimeOfArrival("00:00"),
            List(
              euPassport,
              inTransitFlag
            ))
        ))
    )

    val crunch = runCrunchGraph(TestConfig(
      now = () => SDate(scheduled),
      airportConfig = lhrAirportConfig
    ))

    offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(flights))
    offerAndWait(crunch.manifestsLiveInput, inputManifests)

    val expected = 1

    crunch.portStateTestProbe.fishForMessage(1 seconds) {
      case ps: PortState =>
        val totalPaxAtPCP = paxLoadsFromPortState(ps, 1, 0)
          .values
          .flatMap((_.values))
          .flatten
          .sum

        totalPaxAtPCP == expected
    }

    success
  }
}
