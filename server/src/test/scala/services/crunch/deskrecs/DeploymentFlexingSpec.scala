package services.crunch.deskrecs

import drt.shared.AirportConfig
import drt.shared.Queues._
import drt.shared.Terminals.T1
import services.crunch.CrunchTestLike
import services.{OptimizerConfig, OptimizerCrunchResult}

import scala.util.{Success, Try}

class DeploymentFlexingSpec extends CrunchTestLike {
  //  val totalDesks = 20
  //  val totalStaff = 18
  //  val eeaMinDesks = 1
  //  val roWMinDesks = 2
  //  val ftMinDesks = 3
  //  val egateMinDesks = 4
  //  val egateMaxDesks = 15
  //
  //  val minsToCrunch = 30
  //
  //  val totalDesks24: List[Int] = List.fill(minsToCrunch)(totalDesks)
  //  val eeaMinDesks24: List[Int] = List.fill(minsToCrunch)(eeaMinDesks)
  //  val roWMinDesks24: List[Int] = List.fill(minsToCrunch)(roWMinDesks)
  //  val ftMinDesks24: List[Int] = List.fill(minsToCrunch)(ftMinDesks)
  //  val egateMinDesks24: List[Int] = List.fill(minsToCrunch)(egateMinDesks)
  //  val egateMaxDesks24: List[Int] = List.fill(minsToCrunch)(egateMaxDesks)
  //
  //  val minDesks: Map[Queue, List[Int]] = Map(
  //    FastTrack -> ftMinDesks24,
  //    NonEeaDesk -> roWMinDesks24,
  //    EeaDesk -> eeaMinDesks24,
  //    EGate -> egateMinDesks24
  //    )
  //
  //  val maxDesks: Map[Queue, List[Int]] = Map(EGate -> egateMaxDesks24)
  //
  //  val slas: Map[Queue, Int] = List(FastTrack, NonEeaDesk, EeaDesk, EGate).map(q => (q, 20)).toMap
  //
  //  class MockWithObserver {
  //    var observedMaxDesks: List[List[Int]] = List()
  //
  //    val mockDeskRecs: (Seq[Double], Seq[Int], Seq[Int], OptimizerConfig) => Try[OptimizerCrunchResult] =
  //      (_: Seq[Double], minDesks: Seq[Int], maxDesks: Seq[Int], _: OptimizerConfig) => {
  //        observedMaxDesks = observedMaxDesks ++ List(maxDesks.toList)
  //        Success(OptimizerCrunchResult(minDesks.toIndexedSeq, minDesks))
  //      }
  //  }

  val queuePriority: List[Queue] = List(EeaDesk, NonEeaDesk, QueueDesk, EGate, FastTrack)

  "When I ask for the max staff available for a queue" >> {
    val totalDesks = 20
    val totalStaff = 18
    s"Given a single queue with $totalDesks total desks, and $totalStaff available staff" >> {
      s"I should get the minimum of the 2 numbers, ie $totalStaff" >> {
        val maxStaffAvailable = List(totalStaff, totalDesks).min

        maxStaffAvailable === totalStaff
      }
    }

    val minDesks = 2
    s"Given a single queue with $minDesks min desks $totalDesks total desks, and $totalStaff available staff" >> {
      s"I should get the minimum of the 2 numbers, ie $totalStaff, minus $minDesks min desks = ${totalStaff - minDesks}" >> {
        val maxStaffAvailable = List(totalStaff, totalDesks).min - minDesks

        maxStaffAvailable === (totalStaff - minDesks)
      }
    }

    val alreadyDeployed = 3
    s"Given a single queue with $minDesks min desks $totalDesks total desks, $totalStaff available staff and $alreadyDeployed staff already deployed" >> {
      s"I should get the minimum of the 2 numbers, ie $totalStaff, minus $minDesks min desks = ${totalStaff - minDesks - alreadyDeployed}" >> {
        val maxStaffAvailable = List(totalStaff, totalDesks).min - minDesks - alreadyDeployed

        maxStaffAvailable === (totalStaff - minDesks) - alreadyDeployed
      }
    }

    val flexedDesksInPriorityOrder = List(EeaDesk, NonEeaDesk, QueueDesk)

    val list1 = List(1, 2, 3)
    val list2 = List(5, 5, 5)
    s"Given 2 Lists of Ints - $list1 & $list2" >> {
      "When I ask for them to be reduced with a + operation " >> {
        val expected = List(6, 7, 8)
        s"I should get $expected" >> {
          val result = List(list1, list2).reduce(StaffProviders.listOp[Int](_ + _))
          result === expected
        }
      }

      "When I ask for them to be reduced with a - operation " >> {
        val expected = List(-4, -3, -2)
        s"I should get $expected" >> {
          val result = List(list1, list2).reduce(StaffProviders.listOp[Int](_ - _))
          result === expected
        }
      }
    }

    val list3 = List(1, 2, 3)
    s"Given 3 Lists of Ints - $list1 & $list2 & $list3" >> {
      "When I ask for them to be reduced with a - operation " >> {
        val expected = List(-5, -5, -5)
        s"I should get $expected" >> {
          val result = List(list1, list2, list3).reduce(StaffProviders.listOp[Int](_ - _))
          result === expected
        }
      }
    }
  }


  //  s"Given $totalDesks desks, and a set of minimums for RoW ($roWMinDesks) " >> {
  //    "When I ask for max desks for EEA " >> {
  //      s"I should see $totalDesks minus the RoW min desks passed in" >> {
  //        val eeaMaxDesks = totalDesks24.zip(roWMinDesks24).map { case (a, b) => a - b }
  //        val expectedEeaMaxDesks = List.fill(minsToCrunch)(totalDesks - roWMinDesks)
  //
  //        eeaMaxDesks === expectedEeaMaxDesks
  //      }
  //    }
  //  }
  //
  //  private val ac: AirportConfig = defaultAirportConfig.copy(
  //    minMaxDesksByTerminalQueue = Map(T1 -> Map(
  //      EGate -> ((egateMinDesks24, egateMaxDesks24))
  //    )),
  //    slaByQueue = slas
  //  )
  //
  //  s"Given workload for EEA & RoW, their minimum desks ($eeaMinDesks & $roWMinDesks), SLAs, and $totalDesks terminal's total desks " >> {
  //    "When I ask for desk recommendations using a mock optimiser " >> {
  //      s"I should observe the max desks as EEA: ${totalDesks - roWMinDesks}, RoW: ${totalDesks - eeaMinDesks}" >> {
  //
  //        val observer = new MockWithObserver
  //
  //        FlexedTerminalDeskRecsProvider(ac.queuesByTerminal, ac.minMaxDesksByTerminalQueue, ac.slaByQueue, totalDesks, flexedQueuesPriority, observer.mockDeskRecs, 10)
  //          .desksAndWaits(mockLoads(List(EeaDesk, NonEeaDesk)), minDesks, maxDesks)
  //
  //        val expectedMaxEea = totalDesks24.map(_ - roWMinDesks)
  //        val expectedMaxRoW = totalDesks24.map(_ - eeaMinDesks)
  //
  //        val expectedObservedMaxDesks = List(expectedMaxEea, expectedMaxRoW)
  //
  //        observer.observedMaxDesks === expectedObservedMaxDesks
  //      }
  //    }
  //  }
  //
  //  s"Given workload for EEA & RoW, FastTrack & EGates " >> {
  //    "When I ask for desk recommendations using a mock optimiser " >> {
  //      val observer = new MockWithObserver
  //
  //      val queues = List(FastTrack, NonEeaDesk, EeaDesk)
  //      val eeaMaxDesks = totalDesks - roWMinDesks - ftMinDesks
  //      val roWMaxDesks = totalDesks - ftMinDesks - eeaMinDesks
  //      val ftMaxDesks = totalDesks - eeaMinDesks - roWMinDesks
  //
  //      FlexedTerminalDeskRecsProvider(ac.queuesByTerminal, ac.minMaxDesksByTerminalQueue, ac.slaByQueue, totalDesks, flexedQueuesPriority, observer.mockDeskRecs, 10)
  //        .desksAndWaits(mockLoads(queues), minDesks, maxDesks)
  //
  //      s"I should observe the max desks as EEA: $eeaMaxDesks, RoW: $roWMaxDesks, FT: $ftMaxDesks" >> {
  //        val expectedMaxEea = List.fill(minsToCrunch)(eeaMaxDesks)
  //        val expectedMaxRoW = List.fill(minsToCrunch)(roWMaxDesks)
  //        val expectedMaxFt = List.fill(minsToCrunch)(ftMaxDesks)
  //        val expectedObservedMaxDesks = List(expectedMaxEea, expectedMaxRoW, expectedMaxFt)
  //
  //        observer.observedMaxDesks === expectedObservedMaxDesks
  //      }
  //    }
  //  }
  //
  //  s"Given workload for EGates, EEA & RoW, FastTrack & EGates " >> {
  //    "When I ask for desk recommendations using a mock optimiser " >> {
  //      val observer = new MockWithObserver
  //
  //      val queues = List(EGate, FastTrack, NonEeaDesk, EeaDesk)
  //
  //      val egateMaxDesks = 15
  //      val egateMaxDesks24 = List.fill(minsToCrunch)(egateMaxDesks)
  //      val eeaMaxDesks = totalDesks - roWMinDesks - ftMinDesks
  //      val roWMaxDesks = totalDesks - ftMinDesks - eeaMinDesks
  //      val ftMaxDesks = totalDesks - eeaMinDesks - roWMinDesks
  //
  //      FlexedTerminalDeskRecsProvider(ac.queuesByTerminal, ac.minMaxDesksByTerminalQueue, ac.slaByQueue, totalDesks, flexedQueuesPriority, observer.mockDeskRecs, 10)
  //        .desksAndWaits(mockLoads(queues), minDesks, maxDesks)
  //
  //      s"I should observe the max desks as EEA: $eeaMaxDesks, RoW: $roWMaxDesks, FT: $ftMaxDesks, EGate: $egateMaxDesks" >> {
  //        val expectedMaxEea = List.fill(minsToCrunch)(eeaMaxDesks)
  //        val expectedMaxRoW = List.fill(minsToCrunch)(roWMaxDesks)
  //        val expectedMaxFt = List.fill(minsToCrunch)(ftMaxDesks)
  //        val expectedMaxEGate = egateMaxDesks24
  //
  //        val expectedObservedMaxDesks = List(expectedMaxEea, expectedMaxRoW, expectedMaxFt, expectedMaxEGate)
  //
  //        observer.observedMaxDesks === expectedObservedMaxDesks
  //      }
  //    }
  //  }
  //
  //  private def mockLoads(queues: List[Queue]): Map[Queue, Seq[Double]] = queues.map(q => (q, List.fill(minsToCrunch)(10d))).toMap

}
