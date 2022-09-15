package services

import org.specs2.mutable.Specification

class ProcessedLoadSpec extends Specification {
  "ProcessedLoad should give the excessLoadedWait given an SLA" >> {
    "When the SLA is 5 minutes more than the wait time, and the load is 1, excess = 5 * 1" >> {
      ProcessedLoad(1, 0, 25).excessLoadedWait(20) === 5
    }
    "When the SLA is 5 minutes more than the wait time, and the load is 2, excess = 5 * 2" >> {
      ProcessedLoad(2, 0, 25).excessLoadedWait(20) === 10
    }
    "When the SLA is the same as the wait time, excess = 0" >> {
      ProcessedLoad(1, 0, 20).excessLoadedWait(20) === 0
    }
    "When the SLA is greater than the wait time, excess = 0" >> {
      ProcessedLoad(1, 0, 10).excessLoadedWait(20) === 0
    }
  }
}
