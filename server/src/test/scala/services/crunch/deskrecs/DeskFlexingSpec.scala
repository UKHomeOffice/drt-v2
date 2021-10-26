package services.crunch.deskrecs

import dispatch.Future
import drt.shared.CrunchApi.MillisSinceEpoch
import services.crunch.desklimits.DeskCapacityProvider
import services.crunch.desklimits.flexed.FlexedTerminalDeskLimits
import services.crunch.{CrunchTestLike, deskrecs}
import services.{OptimiserConfig, OptimizerCrunchResult, SDate}
import uk.gov.homeoffice.drt.egates.{EgateBank, EgateBanksUpdate, EgateBanksUpdates, PortEgateBanksUpdates}
import uk.gov.homeoffice.drt.ports.AirportConfig
import uk.gov.homeoffice.drt.ports.Queues._
import uk.gov.homeoffice.drt.ports.Terminals.T1

import scala.collection.immutable.NumericRange
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.util.{Success, Try}

class DeskFlexingSpec extends CrunchTestLike {
  val totalDesks = 20
  val eeaMinDesks = 1
  val roWMinDesks = 2
  val ftMinDesks = 3
  val egateMinDesks = 4
  val egateMaxDesks = 15

  val minutesToCrunch = 30
  val startMillis: MillisSinceEpoch = SDate("2020-01-01T00:00").millisSinceEpoch
  val minuteMillis: NumericRange[MillisSinceEpoch] = startMillis until startMillis + (minutesToCrunch * oneMinuteMillis) by oneMinuteMillis

  val totalDesksByMinute: List[Int] = List.fill(minutesToCrunch)(totalDesks)
  val eeaMinDesks24: List[Int] = List.fill(24)(eeaMinDesks)
  val roWMinDesks24: List[Int] = List.fill(24)(roWMinDesks)
  val ftMinDesks24: List[Int] = List.fill(24)(ftMinDesks)
  val egateMinDesks24: List[Int] = List.fill(24)(egateMinDesks)
  val egateMaxDesks24: List[Int] = List.fill(24)(egateMaxDesks)

  val minDesks: Map[Queue, IndexedSeq[Int]] = Map(
    FastTrack -> ftMinDesks24.toIndexedSeq,
    NonEeaDesk -> roWMinDesks24.toIndexedSeq,
    EeaDesk -> eeaMinDesks24.toIndexedSeq,
    EGate -> egateMinDesks24.toIndexedSeq
  )

  val maxDesks: Map[Queue, IndexedSeq[Int]] = Map(EGate -> egateMaxDesks24.toIndexedSeq)

  val slas: Map[Queue, Int] = List(FastTrack, NonEeaDesk, EeaDesk, EGate).map(q => (q, 20)).toMap

  class MockWithObserver {
    var observedMaxDesks: List[List[Int]] = List()

    val mockDeskRecs: (Seq[Double], Seq[Int], Seq[Int], OptimiserConfig) => Try[OptimizerCrunchResult] =
      (_: Seq[Double], minDesks: Seq[Int], maxDesks: Seq[Int], _: OptimiserConfig) => {
        observedMaxDesks = observedMaxDesks ++ List(maxDesks.toList)
        Success(OptimizerCrunchResult(minDesks.toIndexedSeq, minDesks))
      }
  }

  val queuePriority: List[Queue] = List(EeaDesk, NonEeaDesk, QueueDesk, FastTrack, EGate)

  private val ac: AirportConfig = defaultAirportConfig.copy(
    minMaxDesksByTerminalQueue24Hrs = Map(T1 -> Map(
      EGate -> ((egateMinDesks24, egateMaxDesks24))
    )),
    slaByQueue = slas
  )

  s"Given $totalDesks desks, and a set of minimums for RoW ($roWMinDesks) " >> {
    "When I ask for max desks for EEA " >> {
      s"I should see $totalDesks minus the RoW min desks passed in" >> {
        val eeaMaxDesks = totalDesksByMinute.zip(roWMinDesks24).map { case (a, b) => a - b }
        val expectedEeaMaxDesks = List.fill(24)(totalDesks - roWMinDesks)

        eeaMaxDesks === expectedEeaMaxDesks
      }
    }
  }

  s"Given workload for EEA & RoW, their minimum desks ($eeaMinDesks & $roWMinDesks), SLAs, and $totalDesks terminal's total desks " >> {
    "When I ask for desk recommendations using a mock optimiser " >> {
      s"I should observe the max desks as EEA: ${totalDesks - roWMinDesks}, RoW: ${totalDesks - eeaMinDesks}" >> {

        val observer = new MockWithObserver

        val queues = List(EeaDesk, NonEeaDesk)

        val maxDeskProvider = FlexedTerminalDeskLimits(totalDesksByMinute, Set(EeaDesk, NonEeaDesk), minDesks, maxDesks.mapValues(d => DeskCapacityProvider(d)))

        val eventualDesksAndWaits = deskrecs
          .TerminalDesksAndWaitsProvider(ac.slaByQueue, queuePriority, observer.mockDeskRecs)
          .desksAndWaits(minuteMillis, mockLoads(queues), maxDeskProvider)

        Await.result(eventualDesksAndWaits, 1.second)

        val expectedMaxEea = totalDesksByMinute.map(_ - roWMinDesks)
        val expectedMaxRoW = totalDesksByMinute.map(_ - eeaMinDesks)

        val expectedObservedMaxDesks = List(expectedMaxEea, expectedMaxRoW)

        observer.observedMaxDesks === expectedObservedMaxDesks
      }
    }
  }

  s"Given workload for EEA & RoW, FastTrack & EGates " >> {
    "When I ask for desk recommendations using a mock optimiser " >> {
      val eeaMaxDesks = totalDesks - roWMinDesks - ftMinDesks
      val roWMaxDesks = totalDesks - ftMinDesks - eeaMinDesks
      val ftMaxDesks = totalDesks - eeaMinDesks - roWMinDesks
      s"I should observe the max desks as EEA: $eeaMaxDesks, RoW: $roWMaxDesks, FT: $ftMaxDesks" >> {
        val observer = new MockWithObserver

        val queues = List(FastTrack, NonEeaDesk, EeaDesk)

        val maxDeskProvider = FlexedTerminalDeskLimits(totalDesksByMinute, Set(EeaDesk, NonEeaDesk, FastTrack), minDesks, maxDesks.mapValues(d => DeskCapacityProvider(d)))

        val eventualDesksAndWaits = deskrecs
          .TerminalDesksAndWaitsProvider(ac.slaByQueue, queuePriority, observer.mockDeskRecs)
          .desksAndWaits(minuteMillis, mockLoads(queues), maxDeskProvider)

        Await.result(eventualDesksAndWaits, 1.second)

        val expectedMaxEea = List.fill(30)(eeaMaxDesks)
        val expectedMaxRoW = List.fill(30)(roWMaxDesks)
        val expectedMaxFt = List.fill(30)(ftMaxDesks)
        val expectedObservedMaxDesks = List(expectedMaxEea, expectedMaxRoW, expectedMaxFt)

        observer.observedMaxDesks === expectedObservedMaxDesks
      }
    }
  }

  s"Given workload for EGates, EEA & RoW, FastTrack & EGates " >> {

    val egateMaxDesks = 15
    val egateMaxDesksByMinute = List.fill(minutesToCrunch)(egateMaxDesks)
    val eeaMaxDesks = totalDesks - roWMinDesks - ftMinDesks
    val roWMaxDesks = totalDesks - ftMinDesks - eeaMinDesks
    val ftMaxDesks = totalDesks - eeaMinDesks - roWMinDesks

    "When I ask for desk recommendations using a mock optimiser " >> {
      s"I should observe the max desks as EEA: $eeaMaxDesks, RoW: $roWMaxDesks, FT: $ftMaxDesks, EGate: $egateMaxDesks" >> {
        val observer = new MockWithObserver

        val queues = List(EGate, FastTrack, NonEeaDesk, EeaDesk)

        val maxDeskProvider = FlexedTerminalDeskLimits(totalDesksByMinute, Set(EeaDesk, NonEeaDesk, FastTrack), minDesks, maxDesks.mapValues(d => DeskCapacityProvider(d)))

        val eventualDesksAndWaits = deskrecs
          .TerminalDesksAndWaitsProvider(ac.slaByQueue, queuePriority, observer.mockDeskRecs)
          .desksAndWaits(minuteMillis, mockLoads(queues), maxDeskProvider)

        Await.result(eventualDesksAndWaits, 1.second)

        val expectedMaxEea = List.fill(30)(eeaMaxDesks)
        val expectedMaxRoW = List.fill(30)(roWMaxDesks)
        val expectedMaxFt = List.fill(30)(ftMaxDesks)
        val expectedMaxEGate = egateMaxDesksByMinute

        val expectedObservedMaxDesks = List(expectedMaxEea, expectedMaxRoW, expectedMaxFt, expectedMaxEGate)

        observer.observedMaxDesks === expectedObservedMaxDesks
      }
    }
  }

  private def mockLoads(queues: List[Queue]): Map[Queue, Seq[Double]] = queues.map(q => (q, List.fill(24)(10d))).toMap
}
