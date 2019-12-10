package services.crunch

import controllers.ArrivalGenerator
import drt.shared.FlightsApi.Flights
import drt.shared.PaxTypes.{B5JPlusNational, B5JPlusNationalBelowEGateAge, EeaBelowEGateAge, EeaMachineReadable, EeaNonMachineReadable, NonVisaNational, Transit, VisaNational}
import drt.shared.PaxTypesAndQueues.eeaMachineReadableToDesk
import drt.shared.Queues.{EeaDesk, NonEeaDesk}
import drt.shared.Terminals.{T1, T2}
import drt.shared._
import passengersplits.parsing.VoyageManifestParser.{ManifestDateOfArrival, ManifestTimeOfArrival, VoyageManifest}
import server.feeds.{ArrivalsFeedSuccess, DqManifests, ManifestsFeedSuccess}
import services.SDate
import services.crunch.VoyageManifestGenerator.{euPassport, inTransitFlag}

import scala.collection.immutable.Seq
import scala.concurrent.duration._

class TransferPaxInApiSpec extends CrunchTestLike {
  sequential
  isolated

  val fiveMinutes = 600d / 60

  val lhrAirportConfig = airportConfig.copy(
    portCode = PortCode("LHR"),
    terminalProcessingTimes = Map(T1 -> Map(eeaMachineReadableToDesk -> fiveMinutes)),
    queues = Map(T1 -> Seq(EeaDesk)),
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
    terminals = Seq(T1),
    hasTransfer = true
  )

  "Given a flight with transfer passengers " +
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

    val crunch = runCrunchGraph(
      now = () => SDate(scheduled),
      airportConfig = lhrAirportConfig
    )

    offerAndWait(crunch.liveArrivalsInput,
      ArrivalsFeedSuccess(flights))

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

    crunch.shutdown

    success
  }

  "Given a flight with transfer passengers " +
    "Then these passengers should not be included in the total pax when using API pax nos" >> {

    val scheduled = "2017-01-01T00:00Z"

    val flights = Flights(Seq(
      ArrivalGenerator.arrival(
        origin = PortCode("JFK"),
        schDt = scheduled,
        iata = "TST001",
        terminal = T1,
        actPax = Option(2),
        tranPax = Option(0))
    ))

    val portCode = PortCode("LHR")

    val inputManifests = ManifestsFeedSuccess(DqManifests("",
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
      )))

    val fiveMinutes = 600d / 60

    val crunch = runCrunchGraph(
      now = () => SDate(scheduled),
      airportConfig = lhrAirportConfig
    )

    offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(flights))
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

    crunch.shutdown

    success
  }
}
