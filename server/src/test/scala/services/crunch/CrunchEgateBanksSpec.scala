package services.crunch

import controllers.ArrivalGenerator
import drt.shared.FlightsApi.Flights
import drt.shared._
import server.feeds.ArrivalsFeedSuccess
import services.{OptimiserWithFlexibleProcessors, SDate}
import uk.gov.homeoffice.drt.egates.{EgateBank, EgateBanksUpdate, EgateBanksUpdates, PortEgateBanksUpdates}
import uk.gov.homeoffice.drt.ports.PaxTypesAndQueues.{eeaMachineReadableToDesk, eeaMachineReadableToEGate}
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.{SplitRatio, SplitRatios, SplitSources}
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.ports.{AirportConfig, Queues}

import scala.collection.immutable.{List, Seq, SortedMap}
import scala.concurrent.Future
import scala.concurrent.duration._


class CrunchEgateBanksSpec extends CrunchTestLike {
  sequential
  isolated

  val fiveMinutes: Double = 600d / 60

  val airportConfig: AirportConfig = defaultAirportConfig.copy(
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
  )
  val scheduled = "2017-01-01T00:00Z"
  val flights: Flights = Flights(List(
    ArrivalGenerator.arrival(schDt = scheduled, iata = "BA0001", terminal = T1, actPax = Option(20))
  ))

  "Egate banks handling " >> {
    "Given a flight with 20 very expensive passengers and splits to eea desk & egates " +
      "When I ask for desk recs " +
      "Then I should see lower egates recs by a factor of 7 (rounded up)" >> {

      val crunch = runCrunchGraph(TestConfig(
        now = () => SDate(scheduled),
        airportConfig = airportConfig,
        cruncher = OptimiserWithFlexibleProcessors.crunchWholePax
      ))

      offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(flights))

      val expected = Map(T1 -> Map(
        Queues.EeaDesk -> Seq.fill(15)(7),
        Queues.EGate -> Seq.fill(15)(1)
      ))

      crunch.portStateTestProbe.fishForMessage(1.seconds) {
        case ps: PortState =>
          val resultSummary = deskRecsFromPortState(ps, 15)
          resultSummary == expected
      }

      success
    }

    "Given a flight with 20 very expensive passengers and splits to eea desk & egates " +
      "Something..." >> {

      val reducedBanks = EgateBanksUpdate(SDate(scheduled).millisSinceEpoch, IndexedSeq(EgateBank(IndexedSeq(true))))
      val increasedBanks = EgateBanksUpdate(SDate(scheduled).addMinutes(2).millisSinceEpoch, IndexedSeq(EgateBank(IndexedSeq.fill(10)(true)), EgateBank(IndexedSeq.fill(10)(true))))

      val egatesProvider = () => Future.successful(PortEgateBanksUpdates(Map(T1 -> EgateBanksUpdates(List(reducedBanks, increasedBanks)))))

      val crunch = runCrunchGraph(TestConfig(
        now = () => SDate(scheduled),
        airportConfig = airportConfig,
        cruncher = OptimiserWithFlexibleProcessors.crunchWholePax,
        maybeEgatesProvider = Option(egatesProvider),
      ))

      offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(flights))

      val expected = Map(T1 -> Map(
        Queues.EeaDesk -> Seq.fill(15)(7),
        Queues.EGate -> Seq.fill(15)(1)
      ))

      crunch.portStateTestProbe.fishForMessage(1.seconds) {
        case ps: PortState =>
          deskRecsFromPortState(ps, 15) == expected
      }

      success
    }
  }

}
