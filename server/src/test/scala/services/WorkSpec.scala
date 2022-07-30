package services

import org.specs2.mutable.Specification

class WorkSpec extends Specification {
  val sla = 25

  "Given a single minute containing 1 minute wof work with one desk open" >> {
    "When I process the work" >> {
      "I should see zero wait time and no left over work" >> {
        val workByMinute = List(1d)
        val desks = List(1)
        val result = QueueCapacity(desks).processMinutes(sla, workByMinute)

        result.waits === List(0) && result.leftover === BatchOfWork.empty
      }
    }
  }

  "Given a single minute containing 2 minutes of work with one desk open" >> {
    "When I process the work" >> {
      "I should see a 1 minute wait for the uncompleted work, with 1 minute leftover work" >> {
        val workByMinute = List(2d)
        val desks = List(1)
        val result = QueueCapacity(desks).processMinutes(sla, workByMinute)

        val expectedWaits = List(1)
        val expectedLeftover = BatchOfWork(List(Work(1d, 0)))

        result.waits === expectedWaits && result.leftover === expectedLeftover
      }
    }
  }

  "Given a two minutes with 2 & 0 minutes of work with one desk open" >> {
    "When I process the work" >> {
      "I should see wait times of 1 & 0 minutes, and no leftovers" >> {
        val workByMinute = List(2d, 0d)
        val desks = List(1, 1)
        val result = QueueCapacity(desks).processMinutes(sla, workByMinute)

        val expectedWaits = List(1, 0)
        val expectedLeftover = BatchOfWork.empty

        result.waits === expectedWaits && result.leftover === expectedLeftover
      }
    }
  }

  "Given a two minutes with 2 & 0.5 minutes of work with one desk open" >> {
    "When I process the work" >> {
      "I should see wait times of 1 (completed) & 1 (uncompleted) minutes, and 30 seconds leftover from minute 1" >> {
        val workByMinute = List(2d, 0.5)
        val desks = List(1, 1)
        val result = QueueCapacity(desks).processMinutes(sla, workByMinute)

        val expectedWaits = List(1, 1)
        val expectedLeftover = BatchOfWork(List(Work(0.5, 1)))

        result.waits === expectedWaits && result.leftover === expectedLeftover
      }
    }
  }

  "Given a work minutes of 3s, 0m, 0.5m with one desk open" >> {
    "When I process the work" >> {
      "I should see wait times of 2, 1 (completed) & 1 (uncompleted work) minutes, and 30 seconds leftover from minute 2" >> {
        val workByMinute = List(3d, 0d, 0.5d)
        val desks = List(1, 1, 1)
        val result = QueueCapacity(desks).processMinutes(sla, workByMinute)

        val expectedWaits = List(2, 1, 1)
        val expectedLeftover = BatchOfWork(List(Work(0.5, 2)))

        result.waits === expectedWaits && result.leftover === expectedLeftover
      }
    }
  }
}
