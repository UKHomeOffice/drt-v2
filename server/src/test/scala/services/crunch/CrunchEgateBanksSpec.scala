package services.crunch

import controllers.ArrivalGenerator
import drt.shared.CrunchApi.PortState
import drt.shared.FlightsApi.Flights
import drt.shared.PaxTypesAndQueues._
import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios, SplitSources}
import drt.shared._
import services.SDate
import services.graphstages.Crunch._

import scala.collection.immutable.{List, Seq}
import scala.concurrent.duration._
import scala.languageFeature.postfixOps


class CrunchEgateBanksSpec extends CrunchTestLike {
  sequential
  isolated

  "Egate banks handling " >> {
    "Given flights with 20 very expensive passengers and splits to eea desk & egates " +
      "When I ask for desk recs " +
      "Then I should see lower egates recs by a factor of 5 (rounded up)" >> {

      val scheduled00 = "2017-01-01T00:00Z"
      val scheduled = "2017-01-01T00:00Z"

      val flights = Flights(List(
        ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled00, iata = "BA0001", terminal = "T1", actPax = 20)
      ))

      val fiveMinutes = 600d / 60

      val crunch = runCrunchGraph(
        now = () => SDate(scheduled),
        airportConfig = airportConfig.copy(
          defaultPaxSplits = SplitRatios(
            SplitSources.TerminalAverage,
            SplitRatio(eeaMachineReadableToDesk, 0.5),
            SplitRatio(eeaMachineReadableToEGate, 0.5)
          ),
          defaultProcessingTimes = Map("T1" -> Map(
            eeaMachineReadableToDesk -> fiveMinutes,
            eeaMachineReadableToEGate -> fiveMinutes
          )),
          minMaxDesksByTerminalQueue = Map("T1" -> Map(
            Queues.EeaDesk -> ((List.fill[Int](24)(0), List.fill[Int](24)(20))),
            Queues.EGate -> ((List.fill[Int](24)(0), List.fill[Int](24)(20))))),
          slaByQueue = Map(Queues.EeaDesk -> 25, Queues.EGate -> 25)
        ),
        crunchStartDateProvider = (_) => getLocalLastMidnight(SDate(scheduled)),
        crunchEndDateProvider = (_) => getLocalLastMidnight(SDate(scheduled)).addMinutes(30)
      )

      crunch.liveArrivalsInput.offer(flights)

      val result = crunch.liveTestProbe.expectMsgAnyClassOf(10 seconds, classOf[PortState])
      val resultSummary = deskRecsFromPortState(result, 15)

      val expected = Map("T1" -> Map(
        Queues.EeaDesk -> Seq(7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7),
        Queues.EGate -> Seq(2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2)
      ))

      resultSummary === expected
    }
  }

}
