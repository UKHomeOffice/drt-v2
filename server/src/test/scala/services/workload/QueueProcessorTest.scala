package services.workload

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import services.workload.QueueProcessor.processQueue
import services.{OptimiserConfig, OptimiserWithFlexibleProcessors, OptimizerCrunchResult, TryRenjin, WorkloadProcessorsProvider}
import uk.gov.homeoffice.drt.egates.Desk

import scala.util.Try

class QueueProcessorTest extends AnyWordSpec with Matchers {
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
    "return the max wait time of any of the passengers processed at the particular minute" in {
      val oldestJoinTime = 1
      val minute = 5
      assertQueueAndWait(60, List(QueuePassenger(30, oldestJoinTime), QueuePassenger(40, 2)), minute)(
        expQueue = List(QueuePassenger(10, 2)), expWait = minute - oldestJoinTime)
    }
  }

  private def assertQueueAndWait(capacity: Int, queue: List[QueuePassenger], minute: Int)(expQueue: List[QueuePassenger], expWait: Int): Any = {
    val (newQueue, waitTime) = QueueProcessor.applyCapacity(minute = minute, capacity, queue)

    assert(newQueue == expQueue)
    assert(waitTime == expWait)
  }

  "processQueue" should {
    "return an empty queue and no wait times given no passengers" in {
      processQueue(IndexedSeq(List(60)), Seq.empty) shouldBe(List.empty, List.empty, List.empty)
    }
    "return an empty queue with a one minute wait time of the passenger with over a 60s load" in {
      processQueue(IndexedSeq.fill(2)(List(60)), Seq(List(QueuePassenger(65)), List.empty)) shouldBe(List.empty, List(0, 1), List(1, 0))
    }
    "return remaining passengers and wait times for each minute" in {
      processQueue(IndexedSeq.fill(4)(List(60)), Seq(
        List(QueuePassenger(50), QueuePassenger(50), QueuePassenger(120)),
        List(QueuePassenger(45)),
        List.empty,
        List.empty
      )) shouldBe(List(QueuePassenger(25, 1)), List(0, 1, 2, 3), List(2, 2, 2, 1))
    }
    "return remaining passengers and wait times for each minute 2" in {
      processQueue(IndexedSeq.fill(5)(List(60)), Seq(
        List(QueuePassenger(65)),
        List.empty,
        List.empty,
        List(QueuePassenger(65)),
        List.empty,
      )) shouldBe(List.empty, List(0, 1, 0, 0, 1), List(1, 0, 0, 1, 0))
    }
    "give a result in a reasonable amount of time" in {
      val minuteCount = 1440
      val incomingPax = randomPassengersForMinutes(minuteCount)
      val start = System.currentTimeMillis()
      val desks = 0
      val desksByMinute = IndexedSeq.fill(minuteCount)(List.fill(desks)(60))
      processQueue(desksByMinute, incomingPax)

      val timeTaken = System.currentTimeMillis() - start

      assert(timeTaken < 1000)
    }
  }

  private def randomPassengersForMinutes(minutes: Int): Seq[List[QueuePassenger]] =
    (0 until minutes).map(_ => randomPassengersForMinute)

  private def randomPassengersForMinute: List[QueuePassenger] =
    (0 to (Math.random() * 20).toInt).map(_ => QueuePassenger(randomProcessingSeconds)).toList

  private def randomProcessingSeconds: Int =
    (Math.random() * 100).toInt + 40
}
