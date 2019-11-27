package services.crunch

import controllers.ArrivalGenerator
import drt.shared.FlightsApi.Flights
import drt.shared.PaxTypesAndQueues._
import drt.shared.Terminals.{T1, Terminal}
import drt.shared._
import passengersplits.parsing.VoyageManifestParser.{PassengerInfoJson, VoyageManifest}
import server.feeds.{ArrivalsFeedSuccess, ManifestsFeedSuccess}
import services.SDate
import services.graphstages.DqManifests

import scala.collection.immutable.{List, Seq}
import scala.concurrent.duration._

class ApiPaxNosCrunchSpecSpec extends CrunchTestLike {
  sequential
  isolated

  val tenMinutes: Double = 600d / 60
  val procTimes: Map[Terminal, Map[PaxTypeAndQueue, Double]] = Map(T1 -> Map(eeaChildToDesk -> tenMinutes))

  val scheduled = "2019-11-20T00:00Z"

  val flights = Flights(List(
    ArrivalGenerator.arrival(iata = "BA0001", schDt = scheduled, actPax = Option(0), origin = PortCode("JFK"))
  ))

  val manifests =
    ManifestsFeedSuccess(DqManifests("", Set(
      VoyageManifest(EventTypes.DC, airportConfig.portCode, PortCode("JFK"), "0001", "BA", "2019-11-20", "00:00",
        List(
          PassengerInfoJson(Some("P"), "GBR", "EEA", Some("11"), Some("LHR"), "N", Some("GBR"), Option("GBR"), None),
          PassengerInfoJson(Some("P"), "GBR", "EEA", Some("11"), Some("LHR"), "N", Some("GBR"), Option("GBR"), None)
        ))
    )))

  "Given a flight with 0 pax numbers and a Manifest of 2 passengers " +
    "Then we should get 2 passengers in PCP Pax" >> {

    val crunch = runCrunchGraph(
      now = () => SDate(scheduled),
      airportConfig = airportConfig.copy(
        terminalProcessingTimes = procTimes,
        queues = Map(T1 -> Seq(Queues.EeaDesk)),
        terminals = Seq(T1)
      ))

    offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(flights))
    offerAndWait(crunch.manifestsLiveInput, manifests)

    val expected = Map(T1 -> Map(Queues.EeaDesk -> Seq(2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)))

    crunch.portStateTestProbe.fishForMessage(2 seconds) {
      case ps: PortState =>
        val resultSummary = paxLoadsFromPortState(ps, 15)

        resultSummary == expected
    }

    crunch.liveArrivalsInput.complete()

    success
  }

  "Given a flight with 0 pax numbers and a Manifest of 2 passengers " +
    "Then we should get workload for the 2 passengers Port State" >> {

    val crunch = runCrunchGraph(
      now = () => SDate(scheduled),
      airportConfig = airportConfig.copy(
        terminalProcessingTimes = procTimes,
        queues = Map(T1 -> Seq(Queues.EeaDesk)),
        terminals = Seq(T1)
      ))

    offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(flights))
    offerAndWait(crunch.manifestsLiveInput, manifests)

    val expected = Map(T1 -> Map(Queues.EeaDesk -> Seq(20, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)))

    crunch.portStateTestProbe.fishForMessage(2 seconds) {
      case ps: PortState =>
        val resultSummary = workLoadsFromPortState(ps, 15)

        resultSummary == expected
    }

    crunch.liveArrivalsInput.complete()

    success
  }
}
