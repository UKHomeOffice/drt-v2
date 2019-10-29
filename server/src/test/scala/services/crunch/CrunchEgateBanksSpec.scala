package services.crunch

import controllers.ArrivalGenerator
import drt.shared.FlightsApi.Flights
import drt.shared.PaxTypesAndQueues._
import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios, SplitSources}
import drt.shared._
import server.feeds.ArrivalsFeedSuccess
import services.{SDate, TryRenjin}

import scala.collection.immutable.{List, Seq}
import scala.concurrent.duration._


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
        ArrivalGenerator.arrival(schDt = scheduled00, iata = "BA0001", terminal = "T1", actPax = Option(20))
      ))

      val fiveMinutes = 600d / 60

      val crunch = runCrunchGraph(
        now = () => SDate(scheduled),
        airportConfig = airportConfig.copy(
          terminalNames = Seq("T1"),
          queues = Map("T1" -> Seq(Queues.EeaDesk, Queues.EGate)),
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
        cruncher = TryRenjin.crunch
      )

      offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(flights))

      val expected = Map("T1" -> Map(
        Queues.EeaDesk -> Seq.fill(15)(7),
        Queues.EGate -> Seq.fill(15)(1)
      ))

      crunch.liveTestProbe.fishForMessage(10 seconds) {
        case ps: PortState =>
          val resultSummary = deskRecsFromPortState(ps, 15)
          resultSummary == expected
      }

      crunch.liveArrivalsInput.complete()

      success
    }
  }

}
