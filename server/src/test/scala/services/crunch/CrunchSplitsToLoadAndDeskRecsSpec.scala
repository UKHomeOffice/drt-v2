package services.crunch

import controllers.ArrivalGenerator
import drt.shared.CrunchApi.PortState
import drt.shared.FlightsApi.{Flights, FlightsWithSplits}
import drt.shared.PaxTypesAndQueues._
import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios, SplitSources}
import drt.shared._
import passengersplits.parsing.VoyageManifestParser.{PassengerInfoJson, VoyageManifest, VoyageManifests}
import server.feeds.{ArrivalsFeedSuccess, ManifestsFeedSuccess}
import services.SDate
import services.graphstages.Crunch.getLocalLastMidnight
import services.graphstages.DqManifests

import scala.collection.immutable.{List, Seq}
import scala.concurrent.duration._


class CrunchSplitsToLoadAndDeskRecsSpec extends CrunchTestLike {
  isolated
  sequential

  "Crunch split workload flow " >> {
    "Given a flight with 21 passengers and splits to eea desk & egates " +
      "When I ask for queue loads " +
      "Then I should see 4 queue loads, 2 for the first 20 pax to each queue and 2 for the last 1 split to each queue" >> {

      val scheduled = "2017-01-01T00:00Z"
      val edSplit = 0.25
      val egSplit = 0.75

      val flights = Flights(List(
        ArrivalGenerator.apiFlight(flightId = Option(1), schDt = scheduled, iata = "BA0001", terminal = "T1", actPax = Option(21))
      ))

      val crunch = runCrunchGraph(
        now = () => SDate(scheduled),
        airportConfig = airportConfig.copy(
          queues = Map("T1" -> Seq(Queues.EeaDesk, Queues.EGate)),
          terminalNames = Seq("T1"),
          defaultPaxSplits = SplitRatios(
            SplitSources.TerminalAverage,
            SplitRatio(eeaMachineReadableToDesk, edSplit),
            SplitRatio(eeaMachineReadableToEGate, egSplit)
          ),
          defaultProcessingTimes = Map("T1" -> Map(
            eeaMachineReadableToDesk -> 20d / 60,
            eeaMachineReadableToEGate -> 35d / 60))
        ))

      offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(flights))

      val expected = Map("T1" -> Map(
        Queues.EeaDesk -> Seq(20 * edSplit, 1 * edSplit),
        Queues.EGate -> Seq(20 * egSplit, 1 * egSplit)
      ))

      crunch.liveTestProbe.fishForMessage(5 seconds) {
        case ps: PortState =>
          val resultSummary = paxLoadsFromPortState(ps, 2)
          resultSummary == expected
      }

      crunch.liveArrivalsInput.complete()

      success
    }

    "Given 2 flights with one passenger each and one split to eea desk arriving at pcp 1 minute apart" +
      "When I ask for queue loads " +
      "Then I should see two eea desk queue loads containing the 2 passengers and their proc time" >> {
      val scheduled = "2017-01-01T00:00Z"
      val scheduled2 = "2017-01-01T00:01Z"

      val flights = Flights(List(
        ArrivalGenerator.apiFlight(flightId = Option(1), schDt = scheduled, iata = "BA0001", terminal = "T1", actPax = Option(1)),
        ArrivalGenerator.apiFlight(flightId = Option(2), schDt = scheduled2, iata = "SA123", terminal = "T1", actPax = Option(1))
      ))

      val crunch = runCrunchGraph(now = () => SDate(scheduled))

      offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(flights))

      val expected = Map("T1" -> Map(Queues.EeaDesk -> Seq(1.0, 1.0, 0.0, 0.0, 0.0)))

      crunch.liveTestProbe.fishForMessage(5 seconds) {
        case ps: PortState =>
          val resultSummary = paxLoadsFromPortState(ps, 5)
          resultSummary == expected
      }

      crunch.liveArrivalsInput.complete()

      success
    }

    "Given 1 flight with 100 passengers eaa splits to desk and eGates" +
      "When I ask for queue loads " +
      "Then I should see the correct loads for each queue" >> {
      val scheduled = "2017-01-01T00:00Z"

      val flights = Flights(List(
        ArrivalGenerator.apiFlight(flightId = Option(1), schDt = scheduled, iata = "BA0001", terminal = "T1", actPax = Option(100))
      ))

      val crunch = runCrunchGraph(
        now = () => SDate(scheduled),
        airportConfig = airportConfig.copy(
          terminalNames = Seq("T1"),
          queues = Map("T1" -> Seq(Queues.EeaDesk, Queues.NonEeaDesk, Queues.EGate)),
          defaultProcessingTimes = Map("T1" -> Map(
            eeaMachineReadableToDesk -> 0.25,
            eeaMachineReadableToEGate -> 0.3,
            eeaNonMachineReadableToDesk -> 0.4
          )),
          defaultPaxSplits = SplitRatios(
            SplitSources.TerminalAverage,
            List(SplitRatio(eeaMachineReadableToDesk, 0.25),
              SplitRatio(eeaMachineReadableToEGate, 0.25),
              SplitRatio(eeaNonMachineReadableToDesk, 0.5)
            )
          )
        ))
      offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(flights))

      val expected = Map("T1" -> Map(
        "eeaDesk" -> List(5.25, 5.25, 5.25, 5.25, 5.25),
        "eGate" -> List(1.5, 1.5, 1.5, 1.5, 1.5))
      )

      crunch.liveTestProbe.fishForMessage(5 seconds) {
        case ps: PortState =>
          val resultSummary = workLoadsFromPortState(ps, 5)
          resultSummary == expected
      }

      crunch.liveArrivalsInput.complete()

      success
    }

    "CSV split ratios " >> {
      "Given a flight with 20 passengers and one CSV split of 25% to eea desk " +
        "When request a crunch " +
        "Then I should see a pax load of 5 (20 * 0.25)" >> {
        val scheduled = "2017-01-01T00:00Z"

        val flight = ArrivalGenerator.apiFlight(flightId = Option(1), schDt = scheduled, iata = "BA0001", terminal = "T1", actPax = Option(20))
        val flights = Flights(List(
          flight
        ))

        val crunch = runCrunchGraph(
          now = () => SDate(scheduled),
          airportConfig = airportConfig.copy(
            defaultProcessingTimes = Map("T1" -> Map(
              eeaMachineReadableToDesk -> 20d / 60,
              eeaMachineReadableToEGate -> 35d / 60)),
            defaultPaxSplits = SplitRatios(
              SplitSources.TerminalAverage,
              SplitRatio(eeaMachineReadableToDesk, 0.25)
            )
          )
        )

        offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(flights))

        val expected = Map("T1" -> Map(Queues.EeaDesk -> Seq(5.0, 0.0, 0.0, 0.0, 0.0)))

        crunch.liveTestProbe.fishForMessage(5 seconds) {
          case ps: PortState =>
            val resultSummary = paxLoadsFromPortState(ps, 5)
            resultSummary == expected
        }

        crunch.liveArrivalsInput.complete()

        success
      }
    }

    "Split source precedence " >> {
      "Given a flight with both api & csv splits " +
        "When I crunch " +
        "I should see pax loads calculated from the api splits and applied to the arrival's pax " >> {

        val scheduled = "2017-01-01T00:00Z"

        val arrival = ArrivalGenerator.apiFlight(flightId = Option(1), origin = "JFK", schDt = scheduled, iata = "BA0001", terminal = "T1", actPax = Option(10), airportId = "LHR")

        val crunch = runCrunchGraph(
          now = () => SDate(scheduled),
          airportConfig = airportConfig.copy(
            terminalNames = Seq("T1"),
            queues = Map("T1" -> Seq(Queues.EeaDesk, Queues.EGate)),
            defaultProcessingTimes = Map("T1" -> Map(
              eeaMachineReadableToDesk -> 20d / 60,
              eeaMachineReadableToEGate -> 35d / 60))
          ),
          initialPortState = Option(PortState(Map(arrival.uniqueId -> ApiFlightWithSplits(arrival, Set())), Map(), Map()))
        )

        val voyageManifests = ManifestsFeedSuccess(DqManifests("", Set(
          VoyageManifest(DqEventCodes.CheckIn, "STN", "JFK", "0001", "BA", "2017-01-01", "00:00", List(
            PassengerInfoJson(Some("P"), "GBR", "EEA", Some("22"), Some("LHR"), "N", Some("GBR"), Option("GBR"), None)
          ))
        )))

        offerAndWait(crunch.manifestsInput, voyageManifests)

        val expected = Map("T1" -> Map(
          Queues.EeaDesk -> Seq(0.0, 0.0, 0.0, 0.0, 0.0),
          Queues.EGate -> Seq(10.0, 0.0, 0.0, 0.0, 0.0)
        ))

        crunch.liveTestProbe.fishForMessage(5 seconds) {
          case ps: PortState =>
            val resultSummary = paxLoadsFromPortState(ps, 5)
            println(s"resultSummary: $resultSummary")
            resultSummary == expected
        }

        crunch.liveArrivalsInput.complete()

        success
      }
    }
  }
}
