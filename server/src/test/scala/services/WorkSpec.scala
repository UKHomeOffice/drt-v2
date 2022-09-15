package services

import org.specs2.mutable.Specification

class WorkSpec extends Specification {
  "Work should get processed given a capacity and return the remaining work and capacity" >> {
    "When there is more capacity than work" >> {
      val capacity = Capacity(10, 0)
      val work = Work(5, 0, List.empty)
      work.process(capacity) === (Work(0, 0, processed = List(ProcessedLoad(5, 0, 0))), Capacity(5, 0))
    }
    "When the work is zero - work and capacity remain the same" >> {
      val capacity = Capacity(10, 0)
      val work = Work(0, 0, List.empty)
      work.process(capacity) === (work, capacity)
    }
    "When the work is more than the capacity" >> {
      val capacity = Capacity(10, 0)
      val work = Work(20, 0, List.empty)
      work.process(capacity) === (Work(10, 0, processed = List(ProcessedLoad(10, 0, 0))), Capacity(0, 0))
    }
  }
  "Work should be completed when there is no load" >> {
    Work(0, 0).completed === true
  }
  "Work should not be completed when there is a non zero load" >> {
    Work(1, 0).completed === false
  }
}
