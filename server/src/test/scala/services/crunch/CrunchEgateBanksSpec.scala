package services.crunch

import controllers.ArrivalGenerator
import drt.server.feeds.ArrivalsFeedSuccess
import drt.shared._
import services.OptimiserWithFlexibleProcessors
import uk.gov.homeoffice.drt.arrivals.LiveArrival
import uk.gov.homeoffice.drt.ports.PaxTypesAndQueues.{eeaMachineReadableToDesk, eeaMachineReadableToEGate}
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.{SplitRatio, SplitRatios, SplitSources}
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.ports.{AirportConfig, Queues}
import uk.gov.homeoffice.drt.time.{LocalDate, SDate}

import scala.collection.immutable.{List, Seq, SortedMap}
import scala.concurrent.duration._


class CrunchEgateBanksSpec extends CrunchTestLike {
  sequential
  isolated

  val threeMinutes: Double = 179d / 60

  val airportConfig: AirportConfig = defaultAirportConfig.copy(
    queuesByTerminal = SortedMap(LocalDate(2014, 1, 1) -> SortedMap(T1 -> Seq(Queues.EeaDesk, Queues.EGate))),
    terminalPaxSplits = Map(T1 -> SplitRatios(
      SplitSources.TerminalAverage,
      SplitRatio(eeaMachineReadableToDesk, 0.5),
      SplitRatio(eeaMachineReadableToEGate, 0.5)
    )),
    terminalProcessingTimes = Map(T1 -> Map(
      eeaMachineReadableToDesk -> threeMinutes,
      eeaMachineReadableToEGate -> threeMinutes
    )),
    minMaxDesksByTerminalQueue24Hrs = Map(T1 -> Map(
      Queues.EeaDesk -> ((List.fill[Int](24)(0), List.fill[Int](24)(20))),
      Queues.EGate -> ((List.fill[Int](24)(0), List.fill[Int](24)(20))))),
    slaByQueue = Map(Queues.EeaDesk -> 25, Queues.EGate -> 25),
    minutesToCrunch = 30
  )
  val scheduled = "2017-01-01T00:00Z"
  val flights: List[LiveArrival] = List(
    ArrivalGenerator.live(schDt = scheduled, iata = "BA0001", terminal = T1, totalPax = Option(20))
  )

  "Egate banks handling " >> {
    "Given a flight with 20 very expensive passengers and splits to eea desk & egates " +
      "When I ask for desk recs " +
      "Then I should see lower egates recs by a factor of 2" >> {

      val crunch = runCrunchGraph(TestConfig(
        now = () => SDate(scheduled),
        airportConfig = airportConfig,
        cruncher = OptimiserWithFlexibleProcessors.crunchWholePax(useFairXmax = true)
      ))

      offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(flights))

      val expected = Map(T1 -> Map(
        Queues.EeaDesk -> Seq.fill(15)(2),
        Queues.EGate -> Seq.fill(15)(1)
      ))

      crunch.portStateTestProbe.fishForMessage(2.seconds) {
        case ps: PortState =>
          val resultSummary = deskRecsFromPortState(ps, 15)
          resultSummary == expected
      }

      success
    }
  }
}
