package services

import org.specs2.mutable.Specification
import org.specs2.specification.core.Fragment
import uk.gov.homeoffice.drt.egates.EgateBank

class ProcessorsSpec extends Specification {
  "Given a single unit size of 1" >> {
    val processor = WorkloadProcessors(Seq(EgateBank(IndexedSeq(true))))
    "The cumulative capacity should be (0, 1)" >> {
      processor.cumulativeCapacity === Iterable(0, 1)
    }
    expectCapacityForUnits(processor, 0, 0)
    expectCapacityForUnits(processor, 1, 1)
  }

  private def expectCapacityForUnits(processor: WorkloadProcessors, units: Int, expected: Int): Fragment =
    s"The capacity for $units unit should be $expected" >> {
      processor.capacityForServers(units) === expected
    }

  "Given two units of sizes 1, 2" >> {
    val processor = WorkloadProcessors(Seq(EgateBank(IndexedSeq(true)), EgateBank(IndexedSeq(true, true))))
    "The cumulative capacity should be (0, 1, 3)" >> {
      processor.cumulativeCapacity === Iterable(0, 1, 3)
    }
    expectCapacityForUnits(processor, 0, 0)
    expectCapacityForUnits(processor, 1, 1)
    expectCapacityForUnits(processor, 2, 3)
  }

  "Given three units of sizes 1, 2, 3" >> {
    val processor = WorkloadProcessors(Seq(EgateBank(IndexedSeq(true)), EgateBank(IndexedSeq(true, true)), EgateBank(IndexedSeq(true, true, true))))
    "The cumulative capacity should be (0, 1, 3, 6)" >> {
      processor.cumulativeCapacity === Iterable(0, 1, 3, 6)
    }
    expectCapacityForUnits(processor, 0, 0)
    expectCapacityForUnits(processor, 1, 1)
    expectCapacityForUnits(processor, 2, 3)
    expectCapacityForUnits(processor, 3, 6)
  }
}
