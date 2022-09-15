package services

import org.specs2.mutable.Specification

class CapacitySpec extends Specification {
  "Capacity should process a batch of work" >> {
    "When there is sufficient capacity the capacity should be reduced by the amount of work" >> {
      val batch = BatchOfWork(List(Work(1, 0), Work(1, 1)))
      val capacity = Capacity(10, 0)
      val expectedBatch = BatchOfWork(List(
        Work(0.0, 0, List(ProcessedLoad(1.0, 0, 0))),
        Work(0.0, 1, List(ProcessedLoad(1.0, 1, 0)))))
      capacity.process(batch) === (expectedBatch, Capacity(8.0, 0))
    }
    "When there is insufficient capacity the capacity should be zero, and some work remains outstanding" >> {
      val batch = BatchOfWork(List(Work(1, 0), Work(1, 1)))
      val capacity = Capacity(1, 0)
      val expectedBatch = BatchOfWork(List(
        Work(0.0, 0, List(ProcessedLoad(1.0, 0, 0))),
        Work(1.0, 1, List.empty)))
      capacity.process(batch) === (expectedBatch, Capacity(0.0, 0))
    }
  }
}
