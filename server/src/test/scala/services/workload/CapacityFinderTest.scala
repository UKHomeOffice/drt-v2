package services.workload

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import services.workload.CapacityFinder.{processQueue, processQueue2}
import services.{OptimiserConfig, OptimiserWithFlexibleProcessors, OptimizerCrunchResult, QueueCapacity, TryRenjin, WorkloadProcessorsProvider}
import uk.gov.homeoffice.drt.egates.Desk

import scala.util.Try

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

  private val minutesIn24Hrs = 1440

  "processQueue" should {
    "give a result in a reasonable amount of time" in {
      val incomingPax = randomPassengersForMinutes(minutesIn24Hrs)
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
      val config = OptimiserConfig(60, WorkloadProcessorsProvider(List.fill(minutesIn24Hrs)(List.fill(desks)(Desk))))
      val result = TryRenjin.runSimulationOfWork(incomingPax.map(_.map(_.load).sum), List.fill(minutesIn24Hrs)(desks), config)
      val timeTaken3 = System.currentTimeMillis() - start3
      println(s"Time taken: $timeTaken3 ms. Queue size: n/a, max wait time: ${result.max}")

      assert(timeTaken < 1000)
    }
  }

  "crunchWholePax" should {
    "return a result even when there are no available desks" in {
      val mins = 360
      val config = OptimiserConfig(60, WorkloadProcessorsProvider(List.fill(mins)(List.fill(0)(Desk))))
      val result: Try[OptimizerCrunchResult] = OptimiserWithFlexibleProcessors.crunchWholePax(randomPassengersForMinutes(mins).map(_.map(_.load.toDouble / 60)), List.fill(mins)(0), List.fill(mins)(0), config)

      result.get.recommendedDesks should ===(List.fill(mins)(0))
      result.get.waitTimes should ===(0)
      result.get.paxInQueue should ===(0)
    }
  }

  "processQueue2" should {
    "give a result in a reasonable amount of time" in {
      val minuteCount = 1440
      val incomingPax = randomPassengersForMinutes(minuteCount)
      val start = System.currentTimeMillis()
      val desks = 0
      val desksByMinute = IndexedSeq.fill(minuteCount)(List.fill(desks)(60))
      val (queue, waitTimes, queueSizes) = processQueue2(desksByMinute, incomingPax)
      val timeTaken = System.currentTimeMillis() - start
      println(s"Time taken: $timeTaken ms. Queue size: ${queue.size}, max wait time: ${waitTimes.max}")

//      val start2 = System.currentTimeMillis()
//      val r2 = QueueCapacity(List.fill(minuteCount)(desks)).processPassengers(60, incomingPax.map(_.map(_.load.toDouble / 60)))
//      val timeTaken2 = System.currentTimeMillis() - start2
//      println(s"Time taken: $timeTaken2 ms. Queue size: ${r2.leftover.loads.size}, max wait time: ${r2.waits.max}")
//
//      val start3 = System.currentTimeMillis()
//      val config = OptimiserConfig(60, WorkloadProcessorsProvider(List.fill(minuteCount)(List.fill(desks)(Desk))))
//      val result = TryRenjin.runSimulationOfWork(incomingPax.map(_.map(_.load).sum.toDouble / 60), List.fill(minuteCount)(desks), config)
//      val timeTaken3 = System.currentTimeMillis() - start3
//      println(s"Time taken: $timeTaken3 ms. Queue size: n/a, max wait time: ${result.max}")
//
//      val start4 = System.currentTimeMillis()
////      val config = OptimiserConfig(60, WorkloadProcessorsProvider(List.fill(minuteCount)(List.fill(desks)(Desk))))
//      val result4 = OptimiserWithFlexibleProcessors.legacyTryProcessWork(
//        incomingPax.map(_.map(_.load).sum.toDouble / 60).toIndexedSeq,
//        List.fill(minuteCount)(desks).toIndexedSeq,
//        60,
//        IndexedSeq.empty,
//        config.processors)
//      val timeTaken4 = System.currentTimeMillis() - start4
//      println(s"Time taken: $timeTaken4 ms. Queue size: ${result4.get.residual.sum} wl, max wait time: ${result4.get.waits.max}")

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

  private def randomPassengersForMinutes(minutes: Int): Seq[List[QueuePassenger]] =
    (0 until minutes).map(_ => randomPassengersForMinute)

  private def randomPassengersForMinute: List[QueuePassenger] =
    (0 to (Math.random() * 20).toInt).map(_ => QueuePassenger(randomProcessingSeconds)).toList

  private def randomProcessingSeconds: Int =
    (Math.random() * 100).toInt + 40
}
