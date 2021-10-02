package services.crunch

import controllers.ArrivalGenerator
import drt.shared.FlightsApi.Flights
import drt.shared._
import server.feeds.ArrivalsFeedSuccess
import services.{OptimiserWithFlexibleProcessors, SDate}
import uk.gov.homeoffice.drt.ports.PaxTypesAndQueues.{eeaMachineReadableToDesk, eeaMachineReadableToEGate}
import uk.gov.homeoffice.drt.ports.Queues
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.{SplitRatio, SplitRatios, SplitSources}
import uk.gov.homeoffice.drt.ports.Terminals.T1

import scala.collection.immutable.{List, Seq, SortedMap}
import scala.concurrent.duration._


class CrunchEgateBanksSpec extends CrunchTestLike {
  sequential
  isolated

  "Egate banks handling " >> {
    "Given flights with 20 very expensive passengers and splits to eea desk & egates " +
      "When I ask for desk recs " +
      "Then I should see lower egates recs by a factor of 7 (rounded up)" >> {

      val scheduled00 = "2017-01-01T00:00Z"
      val scheduled = "2017-01-01T00:00Z"

      val flights = Flights(List(
        ArrivalGenerator.arrival(schDt = scheduled00, iata = "BA0001", terminal = T1, actPax = Option(20))
      ))

      val fiveMinutes = 600d / 60

      val crunch = runCrunchGraph(TestConfig(
        now = () => SDate(scheduled),
        airportConfig = defaultAirportConfig.copy(
          queuesByTerminal = SortedMap(T1 -> Seq(Queues.EeaDesk, Queues.EGate)),
          terminalPaxSplits = Map(T1 -> SplitRatios(
            SplitSources.TerminalAverage,
            SplitRatio(eeaMachineReadableToDesk, 0.5),
            SplitRatio(eeaMachineReadableToEGate, 0.5)
          )),
          terminalProcessingTimes = Map(T1 -> Map(
            eeaMachineReadableToDesk -> fiveMinutes,
            eeaMachineReadableToEGate -> fiveMinutes
          )),
          minMaxDesksByTerminalQueue24Hrs = Map(T1 -> Map(
            Queues.EeaDesk -> ((List.fill[Int](24)(0), List.fill[Int](24)(20))),
            Queues.EGate -> ((List.fill[Int](24)(0), List.fill[Int](24)(20))))),
          slaByQueue = Map(Queues.EeaDesk -> 25, Queues.EGate -> 25),
          minutesToCrunch = 30
          ),
        cruncher = OptimiserWithFlexibleProcessors.crunch
      ))

      offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(flights))

      val expected = Map(T1 -> Map(
        Queues.EeaDesk -> Seq.fill(15)(7),
        Queues.EGate -> Seq.fill(15)(1)
      ))

      crunch.portStateTestProbe.fishForMessage(1 seconds) {
        case ps: PortState =>
          val resultSummary = deskRecsFromPortState(ps, 15)
          resultSummary == expected
      }

      success
    }
  }

}
