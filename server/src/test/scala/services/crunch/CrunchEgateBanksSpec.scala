package services.crunch

import akka.NotUsed
import akka.pattern.AskableActorRef
import akka.stream.scaladsl.Source
import akka.testkit.TestProbe
import controllers.ArrivalGenerator
import drt.shared.FlightsApi.Flights
import drt.shared.PaxTypesAndQueues._
import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios, SplitSources}
import drt.shared._
import passengersplits.parsing.VoyageManifestParser.{VoyageManifest, VoyageManifests}
import services.graphstages.Crunch._
import services.SDate

import scala.collection.immutable.{List, Seq}


class CrunchEgateBanksSpec extends CrunchTestLike {
  "Egate banks handling " >> {
    "Given flights with 20 very expensive passengers and splits to eea desk & egates " +
      "When I ask for desk recs " +
      "Then I should see lower egates recs by a factor of 5 (rounded up)" >> {

      val scheduled00 = "2017-01-01T00:00Z"
      val scheduled15 = "2017-01-01T00:15Z"

      val scheduled = "2017-01-01T00:00Z"

      val flights = List(Flights(List(
        ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled00, iata = "BA0001", terminal = "T1", actPax = 20)
      )))

      val fiveMinutes = 600d / 60
      val procTimes: Map[PaxTypeAndQueue, Double] = Map(
        eeaMachineReadableToDesk -> fiveMinutes,
        eeaMachineReadableToEGate -> fiveMinutes
      )
      val minMaxDesks = Map("T1" -> Map(
        Queues.EeaDesk -> ((List.fill[Int](24)(0), List.fill[Int](24)(20))),
        Queues.EGate -> ((List.fill[Int](24)(0), List.fill[Int](24)(20)))))
      val slaByQueue = Map(Queues.EeaDesk -> 25, Queues.EGate -> 25)

      val testProbe = TestProbe()
      val runnableGraphDispatcher =
        runCrunchGraph[NotUsed](
          procTimes = procTimes,
          slaByQueue = slaByQueue,
          minMaxDesks = minMaxDesks,
          testProbe = testProbe,
          crunchStartDateProvider = () => getLocalLastMidnight(SDate(scheduled)).millisSinceEpoch,
          portSplits = SplitRatios(
            SplitSources.TerminalAverage,
            SplitRatio(eeaMachineReadableToDesk, 0.5),
            SplitRatio(eeaMachineReadableToEGate, 0.5)
          )) _

      runnableGraphDispatcher(Source(flights), Source(List()))

      val result = testProbe.expectMsgAnyClassOf(classOf[CrunchState])
      val resultSummary = deskRecsFromCrunchState(result, 15)

      val expected = Map("T1" -> Map(
        Queues.EeaDesk -> Seq(7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7),
        Queues.EGate -> Seq(2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2)
      ))

      resultSummary === expected
    }
  }

}
