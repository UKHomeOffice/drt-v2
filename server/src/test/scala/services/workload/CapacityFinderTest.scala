package services.workload

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import services.workload.CapacityFinder.processQueue

class CapacityFinderTest extends AnyWordSpec with Matchers {
  "applyCapacity" should {
    "return an empty queue given single passenger with workload lower than the capacity" in {
      assertQueueAndWait(30, List(QueuePassenger(29, 0)), 0)(
        expQueue = List.empty, expWait = 0)
    }
    "return an empty queue given single passenger with workload equal to the capacity" in {
      assertQueueAndWait(30, List(QueuePassenger(30, 0)), 0)(
        expQueue = List.empty, expWait = 0)
    }
    "return a queue with the passenger still in it given single passenger with workload higher than the capacity" in {
      assertQueueAndWait(30, List(QueuePassenger(31, 0)), 0)(
        expQueue = List(QueuePassenger(1, 0)), expWait = 0)
    }
    "return an empty queue given two passengers with total workloads lower than the capacity" in {
      assertQueueAndWait(60, List(QueuePassenger(29, 0), QueuePassenger(29, 0)), 0)(
        expQueue = List.empty, expWait = 0)
    }

    "return a partially processed queue given two passengers with total workloads equal to the capacity" in {
      assertQueueAndWait(60, List(QueuePassenger(30, 0), QueuePassenger(40, 0)), 0)(
        expQueue = List(QueuePassenger(10, 0)), expWait = 0)
    }
    "return a wait time of the current minute minus the join time of the processed passenger" in {
      val joinTime = 1
      val minute = 5
      assertQueueAndWait(60, List(QueuePassenger(30, 0), QueuePassenger(40, joinTime)), 5)(
        expQueue = List(QueuePassenger(10, joinTime)), expWait = minute - joinTime)
    }
  }

  private def assertQueueAndWait(capacity: Int, queue: List[QueuePassenger], minute: Int)(expQueue: List[QueuePassenger], expWait: Int): Any = {
    val (newQueue, waitTime) = CapacityFinder.applyCapacity(minute = minute, capacity, queue)

    assert(newQueue == expQueue)
    assert(waitTime == expWait)
  }

  "processQueue" should {
    "return an empty queue and no wait times given no passengers" in {
      processQueue(1, Seq.empty) shouldBe(List.empty, List.empty)
    }
    "return an empty queue with a one minute wait time of the passenger with over a 60s load" in {
      processQueue(1, Seq(List(QueuePassenger(65)), List.empty)) shouldBe(List.empty, List(0, 1))
    }
    "return remaining passengers and wait times for each minute" in {
      processQueue(1, Seq(
        List(QueuePassenger(50), QueuePassenger(50), QueuePassenger(120)),
        List(QueuePassenger(45)),
        List.empty,
        List.empty
      )) shouldBe(List(QueuePassenger(10), QueuePassenger(120)) ::: List(QueuePassenger(25, 1)), List(0, 1, 1, 3))
      //      50 + 50 + 120
      //      0 +  0 +  100 + 45
      //      0 +  0 +  40  + 45
      //      0 +  0 +  0   + 25
    }
    "return remaining passengers and wait times for each minute 2" in {
      processQueue(1, Seq(
        List(QueuePassenger(65)),
        List.empty,
        List.empty,
        List(QueuePassenger(65)),
        List.empty,
      )) shouldBe(List.empty, List(0, 1, 0, 0, 1))
      //      50 + 50 + 120
      //      0 +  0 +  100 + 45
      //      0 +  0 +  40  + 45
      //      0 +  0 +  0   + 25
    }
  }
}
