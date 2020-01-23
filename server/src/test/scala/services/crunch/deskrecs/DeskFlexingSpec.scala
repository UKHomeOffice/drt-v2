package services.crunch.deskrecs

import drt.shared.Queues._
import org.specs2.mutable.Specification
import services.crunch.CrunchTestLike
import services.{OptimizerConfig, OptimizerCrunchResult}

import scala.util.{Success, Try}

class DeskFlexingSpec extends CrunchTestLike {
  val totalDesks = 20
  val eeaMinDesks = 1
  val roWMinDesks = 2
  val ftMinDesks = 3
  val egateMinDesks = 4
  val egateMaxDesks = 15

  val minsToCrunch = 30

  val totalDesks24: List[Int] = List.fill(minsToCrunch)(totalDesks)
  val eeaMinDesks24: List[Int] = List.fill(minsToCrunch)(eeaMinDesks)
  val roWMinDesks24: List[Int] = List.fill(minsToCrunch)(roWMinDesks)
  val ftMinDesks24: List[Int] = List.fill(minsToCrunch)(ftMinDesks)
  val egateMinDesks24: List[Int] = List.fill(minsToCrunch)(egateMinDesks)
  val egateMaxDesks24: List[Int] = List.fill(minsToCrunch)(egateMaxDesks)

  val minDesks: Map[Queue, List[Int]] = Map(
    FastTrack -> ftMinDesks24,
    NonEeaDesk -> roWMinDesks24,
    EeaDesk -> eeaMinDesks24,
    EGate -> egateMinDesks24
  )

  val maxDesks: Map[Queue, List[Int]] = Map(EGate -> egateMaxDesks24)

  val slas: Map[Queue, Int] = List(FastTrack, NonEeaDesk, EeaDesk, EGate).map(q => (q, 20)).toMap

  class MockWithObserver {
    var observedMaxDesks: List[List[Int]] = List()

    val mockDeskRecs: (Seq[Double], Seq[Int], Seq[Int], OptimizerConfig) => Try[OptimizerCrunchResult] =
      (_: Seq[Double], minDesks: Seq[Int], maxDesks: Seq[Int], _: OptimizerConfig) => {
        observedMaxDesks = observedMaxDesks ++ List(maxDesks.toList)
        Success(OptimizerCrunchResult(minDesks.toIndexedSeq, minDesks))
      }
  }

  val flexedQueuesPriority: List[Queue] = List(EeaDesk, NonEeaDesk, FastTrack)

  s"Given $totalDesks desks, and a set of minimums for RoW ($roWMinDesks) " >> {
    "When I ask for max desks for EEA " >> {
      s"I should see $totalDesks minus the RoW min desks passed in" >> {
        val eeaMaxDesks = totalDesks24.zip(roWMinDesks24).map { case (a, b) => a - b }
        val expectedEeaMaxDesks = List.fill(minsToCrunch)(totalDesks - roWMinDesks)

        eeaMaxDesks === expectedEeaMaxDesks
      }
    }
  }

  s"Given workload for EEA & RoW, their minimum desks ($eeaMinDesks & $roWMinDesks), SLAs, and $totalDesks terminal's total desks " >> {
    "When I ask for desk recommendations using a mock optimiser " >> {
      s"I should observe the max desks as EEA: ${totalDesks - roWMinDesks}, RoW: ${totalDesks - eeaMinDesks}" >> {

        val observer = new MockWithObserver

        FlexedTerminal(defaultAirportConfig, totalDesks, flexedQueuesPriority, observer.mockDeskRecs, 10).desksAndWaits(mockLoads(List(EeaDesk, NonEeaDesk)), minDesks, maxDesks, slas)

        val expectedMaxEea = totalDesks24.map(_ - roWMinDesks)
        val expectedMaxRoW = totalDesks24.map(_ - eeaMinDesks)

        val expectedObservedMaxDesks = List(expectedMaxEea, expectedMaxRoW)

        observer.observedMaxDesks === expectedObservedMaxDesks
      }
    }
  }

  s"Given workload for EEA & RoW, FastTrack & EGates " >> {
    "When I ask for desk recommendations using a mock optimiser " >> {
      val observer = new MockWithObserver

      val queues = List(FastTrack, NonEeaDesk, EeaDesk)
      val eeaMaxDesks = totalDesks - roWMinDesks - ftMinDesks
      val roWMaxDesks = totalDesks - ftMinDesks - eeaMinDesks
      val ftMaxDesks = totalDesks - eeaMinDesks - roWMinDesks

      FlexedTerminal(defaultAirportConfig, totalDesks, flexedQueuesPriority, observer.mockDeskRecs, 10).desksAndWaits(mockLoads(queues), minDesks, maxDesks, slas)

      s"I should observe the max desks as EEA: $eeaMaxDesks, RoW: $roWMaxDesks, FT: $ftMaxDesks" >> {
        val expectedMaxEea = List.fill(minsToCrunch)(eeaMaxDesks)
        val expectedMaxRoW = List.fill(minsToCrunch)(roWMaxDesks)
        val expectedMaxFt = List.fill(minsToCrunch)(ftMaxDesks)
        val expectedObservedMaxDesks = List(expectedMaxEea, expectedMaxRoW, expectedMaxFt)

        observer.observedMaxDesks === expectedObservedMaxDesks
      }
    }
  }

  s"Given workload for EGates, EEA & RoW, FastTrack & EGates " >> {
    "When I ask for desk recommendations using a mock optimiser " >> {
      val observer = new MockWithObserver

      val queues = List(EGate, FastTrack, NonEeaDesk, EeaDesk)

      val egateMaxDesks = 15
      val egateMaxDesks24 = List.fill(minsToCrunch)(egateMaxDesks)
      val eeaMaxDesks = totalDesks - roWMinDesks - ftMinDesks
      val roWMaxDesks = totalDesks - ftMinDesks - eeaMinDesks
      val ftMaxDesks = totalDesks - eeaMinDesks - roWMinDesks

      FlexedTerminal(defaultAirportConfig, totalDesks, flexedQueuesPriority, observer.mockDeskRecs, 10).desksAndWaits(mockLoads(queues), minDesks, maxDesks, slas)

      s"I should observe the max desks as EEA: $eeaMaxDesks, RoW: $roWMaxDesks, FT: $ftMaxDesks, EGate: $egateMaxDesks" >> {
        val expectedMaxEea = List.fill(minsToCrunch)(eeaMaxDesks)
        val expectedMaxRoW = List.fill(minsToCrunch)(roWMaxDesks)
        val expectedMaxFt = List.fill(minsToCrunch)(ftMaxDesks)
        val expectedMaxEGate = egateMaxDesks24

        val expectedObservedMaxDesks = List(expectedMaxEea, expectedMaxRoW, expectedMaxFt, expectedMaxEGate)

        observer.observedMaxDesks === expectedObservedMaxDesks
      }
    }
  }

  private def mockLoads(queues: List[Queue]): Map[Queue, Seq[Double]] = queues.map(q => (q, List.fill(minsToCrunch)(10d))).toMap

}
