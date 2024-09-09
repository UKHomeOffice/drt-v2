package services.workload

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import services.workload.CapacityFinder.{processQueue, processQueue2}
import services.{OptimiserConfig, TryRenjin, WorkloadProcessorsProvider}
import uk.gov.homeoffice.drt.egates.Desk

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
    "return the max wait time of any of the passengers processed at the particular minute" in {
      val oldestJoinTime = 1
      val minute = 5
      assertQueueAndWait(60, List(QueuePassenger(30, oldestJoinTime), QueuePassenger(40, 2)), minute)(
        expQueue = List(QueuePassenger(10, oldestJoinTime)), expWait = minute - oldestJoinTime)
    }
  }

  private def assertQueueAndWait(capacity: Int, queue: List[QueuePassenger], minute: Int)(expQueue: List[QueuePassenger], expWait: Int): Any = {
    val (newQueue, waitTime) = CapacityFinder.applyCapacity(minute = minute, capacity, queue)

    assert(newQueue == expQueue)
    assert(waitTime == expWait)
  }

  "processQueue" should {
    "return an empty queue and no wait times given no passengers" in {
      processQueue(1, Seq.empty) shouldBe(List.empty, List.empty, List.empty)
    }
    "return an empty queue with a one minute wait time of the passenger with over a 60s load" in {
      processQueue(1, Seq(List(QueuePassenger(65)), List.empty)) shouldBe(List.empty, List(0, 1), List(1, 0))
    }
    "return remaining passengers and wait times for each minute" in {
      processQueue(1, Seq(
        List(QueuePassenger(50), QueuePassenger(50), QueuePassenger(120)),
        List(QueuePassenger(45)),
        List.empty,
        List.empty
      )) shouldBe(List(QueuePassenger(25, 1)), List(0, 1, 2, 3), List(2, 2, 2, 1))
    }
    "return remaining passengers and wait times for each minute 2" in {
      processQueue(1, Seq(
        List(QueuePassenger(65)),
        List.empty,
        List.empty,
        List(QueuePassenger(65)),
        List.empty,
      )) shouldBe(List.empty, List(0, 1, 0, 0, 1), List(1, 0, 0, 1, 0))
    }
  }

  "processQueue" should {
    "give a result in a reasonable amount of time" in {
      val incomingPax = randomPassengersForDay
      val start = System.currentTimeMillis()
      val desks = 15
      val (queue, waitTimes, queueSizes) = processQueue(desks, incomingPax)
      val timeTaken = System.currentTimeMillis() - start
      println(s"Time taken: $timeTaken ms. Queue size: ${queue.size}, max wait time: ${waitTimes.max}")

//      val start2 = System.currentTimeMillis()
//      QueueCapacity(List.fill(1440)(desks)).processPassengers(60, incomingPax.map(_.map(_.load.toDouble)))
//      val timeTaken2 = System.currentTimeMillis() - start2
//      println(s"Time taken: $timeTaken2 ms.")

      val start3 = System.currentTimeMillis()
      val config = OptimiserConfig(60, WorkloadProcessorsProvider(List.fill(1440)(List.fill(desks)(Desk))))
      val result = TryRenjin.runSimulationOfWork(incomingPax.map(_.map(_.load).sum), List.fill(1440)(desks), config)
      val timeTaken3 = System.currentTimeMillis() - start3
      println(s"Time taken: $timeTaken3 ms. Queue size: n/a, max wait time: ${result.max}")

      assert(timeTaken < 1000)
    }
  }

  "processQueue2" should {
    "give a result in a reasonable amount of time" in {
      val incomingPax = randomPassengersForDay
      val start = System.currentTimeMillis()
      val desks = 18
      val desksByMinute = IndexedSeq.fill(1440)(List.fill(desks)(60))
      val (queue, waitTimes, queueSizes) = processQueue2(desksByMinute, incomingPax)
      val timeTaken = System.currentTimeMillis() - start
      println(s"Time taken: $timeTaken ms. Queue size: ${queue.size}, max wait time: ${waitTimes.max}")

//      val start2 = System.currentTimeMillis()
//      QueueCapacity(List.fill(1440)(desksByMinute)).processPassengers(60, incomingPax.map(_.map(_.load.toDouble)))
//      val timeTaken2 = System.currentTimeMillis() - start2
//      println(s"Time taken: $timeTaken2 ms.")

      val start3 = System.currentTimeMillis()
      val config = OptimiserConfig(60, WorkloadProcessorsProvider(List.fill(1440)(List.fill(desks)(Desk))))
      val result = TryRenjin.runSimulationOfWork(incomingPax.map(_.map(_.load).sum.toDouble / 60), List.fill(1440)(desks), config)
      val timeTaken3 = System.currentTimeMillis() - start3
      println(s"Time taken: $timeTaken3 ms. Queue size: n/a, max wait time: ${result.max}")

      assert(timeTaken < 1000)
    }
    "wait times should match TryRenjin" in {
      val incomingPax = Seq(
        List(QueuePassenger(50), QueuePassenger(50), QueuePassenger(30), QueuePassenger(120)),
        List(),
        List(),
        List(QueuePassenger(50), QueuePassenger(50), QueuePassenger(30), QueuePassenger(120)),
        List(),
        List(),
      )
      val desks = 1
      val desksByMinute = IndexedSeq.fill(incomingPax.size)(List.fill(desks)(60))
      val (_, waitTimes, _) = processQueue2(desksByMinute, incomingPax)
      val config = OptimiserConfig(60, WorkloadProcessorsProvider(List.fill(incomingPax.size)(List.fill(desks)(Desk))))
      val result = TryRenjin.runSimulationOfWork(incomingPax.map(_.map(_.load).sum.toDouble / 60), List.fill(incomingPax.size)(desks), config)
      waitTimes should===(result)
    }
  }

  private def randomPassengersForDay: Seq[List[QueuePassenger]] =
    (0 until 1440).map(_ => randomPassengersForMinute)

  private def randomPassengersForMinute: List[QueuePassenger] =
    (0 to (Math.random() * 20).toInt).map(_ => QueuePassenger(randomProcessingSeconds)).toList

  private def randomProcessingSeconds: Int =
    (Math.random() * 100).toInt + 40
}
