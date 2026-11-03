package services.workload

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import services.crunch.{ CalculationAborted, CalculationControl, CalculationControlTestSupport }
import services.workload.QueueProcessor.{
  advanceMinute,
  processQueue,
  processQueueFromState,
  processQueueWithMetrics,
  scoreAtHorizon
}

import scala.concurrent.duration._
import scala.util.{ Failure, Try }

class QueueProcessorTest extends AnyWordSpec with Matchers {
  "Controlled queue processing" should {
    "retain FIFO, partial work, zero-load boundary and scoring behaviour with an active or unlimited control" in {
      val capacity = IndexedSeq(List(0), List(0), List(30), List(30), List(60))
      val passengers = Seq(
        List(QueuePassenger(0), QueuePassenger(0), QueuePassenger(65)),
        List(QueuePassenger(30)),
        List.empty,
        List.empty,
        List.empty
      )
      val original = processQueue(capacity, passengers)
      val originalMetrics = processQueueWithMetrics(capacity, passengers, sla = 1)
      Seq(CalculationControl.Unlimited, CalculationControl.budget(1.minute)).foreach { control =>
        processQueue(capacity, passengers, control) shouldBe original
        processQueueWithMetrics(capacity, passengers, sla = 1, control = control) shouldBe originalMetrics
      }
    }

    "abort simulation even when every minute has no passengers" in {
      val result = Try(processQueue(
        IndexedSeq.fill(100)(List(0)),
        Seq.fill(100)(List.empty),
        CalculationControlTestSupport.expiringIn("processQueue", 10L)
      ))
      result shouldBe Failure(CalculationAborted("expired"))
    }

    "abort metrics simulation between minutes" in {
      val result = Try(processQueueWithMetrics(
        IndexedSeq.fill(100)(List(0)),
        Seq.fill(100)(List.empty),
        sla = 1,
        control = CalculationControlTestSupport.expiringIn("processQueueWithMetrics", 10L)
      ))
      result shouldBe Failure(CalculationAborted("expired"))
    }

    "propagate shutdown instead of returning an empty success" in {
      val control = CalculationControl.budget(1.minute)
      control.cancel("shutdown")
      Try(processQueue(IndexedSeq.empty, Seq.empty, control)) shouldBe Failure(CalculationAborted("shutdown"))
      Try(processQueueWithMetrics(IndexedSeq.empty, Seq.empty, sla = 0, control = control)) shouldBe
        Failure(CalculationAborted("shutdown"))
    }
  }

  "applyCapacity" should {
    "return an empty queue given single passenger with workload lower than the capacity" in {
      assertQueueAndWait(30, List(QueuePassenger(29, 0)), 0)(
        expQueue = List.empty,
        expWait = 0
      )
    }
    "return an empty queue given single passenger with workload equal to the capacity" in {
      assertQueueAndWait(30, List(QueuePassenger(30, 0)), 0)(
        expQueue = List.empty,
        expWait = 0
      )
    }
    "return a queue with the passenger still in it given single passenger with workload higher than the capacity" in {
      assertQueueAndWait(30, List(QueuePassenger(31, 0)), 0)(
        expQueue = List(QueuePassenger(1, 0)),
        expWait = 0
      )
    }
    "return an empty queue given two passengers with total workloads lower than the capacity" in {
      assertQueueAndWait(60, List(QueuePassenger(29, 0), QueuePassenger(29, 0)), 0)(
        expQueue = List.empty,
        expWait = 0
      )
    }

    "return a partially processed queue given two passengers with total workloads equal to the capacity" in {
      assertQueueAndWait(60, List(QueuePassenger(30, 0), QueuePassenger(40, 0)), 0)(
        expQueue = List(QueuePassenger(10, 0)),
        expWait = 0
      )
    }
    "return the max wait time of any of the passengers processed at the particular minute" in {
      val oldestJoinTime = 1
      val minute = 5
      assertQueueAndWait(60, List(QueuePassenger(30, oldestJoinTime), QueuePassenger(40, 2)), minute)(
        expQueue = List(QueuePassenger(10, 2)),
        expWait = minute - oldestJoinTime
      )
    }
  }

  private def assertQueueAndWait(capacity: Int, queue: List[QueuePassenger], minute: Int)(
      expQueue: List[QueuePassenger],
      expWait: Int
  ): Any = {
    val (newQueue, waitTime) = QueueProcessor.applyCapacity(minute = minute, capacity, queue)

    assert(newQueue == expQueue)
    assert(waitTime == expWait)
  }

  "processQueue" should {
    "return an empty queue and no wait times given no passengers" in {
      processQueue(IndexedSeq(List(60)), Seq.empty) shouldBe (List.empty, List.empty, List.empty)
    }
    "return an empty queue with a one minute wait time of the passenger with over a 60s load" in {
      processQueue(IndexedSeq.fill(2)(List(60)), Seq(List(QueuePassenger(65)), List.empty)) shouldBe
        (List.empty, List(0, 1), List(1, 0))
    }
    "return remaining passengers and wait times for each minute" in {
      processQueue(
        IndexedSeq.fill(4)(List(60)),
        Seq(
          List(QueuePassenger(50), QueuePassenger(50), QueuePassenger(120)),
          List(QueuePassenger(45)),
          List.empty,
          List.empty
        )
      ) shouldBe (List(QueuePassenger(25, 1)), List(0, 1, 2, 3), List(2, 2, 2, 1))
    }
    "return remaining passengers and wait times for each minute 2" in {
      processQueue(
        IndexedSeq.fill(5)(List(60)),
        Seq(
          List(QueuePassenger(65)),
          List.empty,
          List.empty,
          List(QueuePassenger(65)),
          List.empty
        )
      ) shouldBe (List.empty, List(0, 1, 0, 0, 1), List(1, 0, 0, 1, 0))
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

  "Queue continuation" should {
    "give identical seeded outputs with an active or unlimited calculation control" in {
      val seed = QueueProcessingState(5, List(QueuePassenger(65, 1)), 4L)
      val capacity = IndexedSeq(List(30), List(60), List(0))
      val passengers = Seq(List(QueuePassenger(10)), List.empty, List(QueuePassenger(5)))
      val expected = processQueueFromState(capacity, passengers, sla = 1, seed)
      val expectedMinute = advanceMinute(seed, passengers.head, capacitySeconds = 30, sla = 1)

      Seq(CalculationControl.Unlimited, CalculationControl.budget(1.minute)).foreach { control =>
        processQueueFromState(capacity, passengers, sla = 1, seed, control) shouldBe expected
        advanceMinute(seed, passengers.head, capacitySeconds = 30, sla = 1, control) shouldBe expectedMinute
        scoreAtHorizon(expected.state, sla = 1, control) shouldBe scoreAtHorizon(expected.state, sla = 1)
      }
    }

    "match an uninterrupted simulation at every chunk boundary with exact ages, work and scores" in {
      val capacity = IndexedSeq(List(0), List(30), List(20), List(40), List(0), List(100), List(0))
      val passengers = Seq(
        List(QueuePassenger(50, -9), QueuePassenger(40, 99)),
        List(QueuePassenger(70)),
        List.empty,
        List(QueuePassenger(5)),
        List.empty,
        List.empty,
        List(QueuePassenger(10))
      )
      val whole = processQueueFromState(capacity, passengers, sla = 1, QueueProcessingState.Empty)

      whole shouldBe ContinuedQueue(
        QueueProcessingState(7, List(QueuePassenger(10, 6)), 7L),
        List(0, 1, 2, 3, 3, 4, 0),
        List(2, 3, 2, 2, 2, 0, 1)
      )
      (whole.state.remainingPassengers, whole.waitTimes, whole.queueSizes) shouldBe processQueue(capacity, passengers)
      scoreAtHorizon(whole.state, sla = 1) shouldBe 7L

      (0 to capacity.length).foreach { boundary =>
        withClue(s"Chunk boundary $boundary: ") {
          val first = processQueueFromState(
            capacity.take(boundary),
            passengers.take(boundary),
            sla = 1,
            QueueProcessingState.Empty
          )
          val second = processQueueFromState(
            capacity.drop(boundary),
            passengers.drop(boundary),
            sla = 1,
            first.state
          )
          second.state shouldBe whole.state
          first.waitTimes ++ second.waitTimes shouldBe whole.waitTimes
          first.queueSizes ++ second.queueSizes shouldBe whole.queueSizes
          scoreAtHorizon(second.state, sla = 1) shouldBe scoreAtHorizon(whole.state, sla = 1)
        }
      }

      val firstTwo = processQueueFromState(capacity.take(2), passengers.take(2), sla = 1, QueueProcessingState.Empty)
      firstTwo.state shouldBe QueueProcessingState(
        2,
        List(QueuePassenger(20, 0), QueuePassenger(40, 0), QueuePassenger(70, 1)),
        0L
      )
      val next = advanceMinute(firstTwo.state, passengers(2), capacitySeconds = 20, sla = 1)
      next shouldBe QueueMinuteResult(
        QueueProcessingState(3, List(QueuePassenger(40, 0), QueuePassenger(70, 1)), 1L),
        2,
        2
      )
    }

    "give the same result when every minute is a separate chunk" in {
      val capacity = IndexedSeq(List(0), List(30), List(30), List(60), List(0))
      val passengers = Seq(
        List(QueuePassenger(65), QueuePassenger(0)),
        List(QueuePassenger(30)),
        List.empty,
        List.empty,
        List(QueuePassenger(10))
      )
      val whole = processQueueFromState(capacity, passengers, sla = 1, QueueProcessingState.Empty)
      val chunked = capacity.indices.foldLeft(ContinuedQueue(QueueProcessingState.Empty, Nil, Nil)) {
        case (previous, minute) =>
          val next = processQueueFromState(
            IndexedSeq(capacity(minute)),
            Seq(passengers(minute)),
            sla = 1,
            previous.state
          )
          ContinuedQueue(next.state, previous.waitTimes ++ next.waitTimes, previous.queueSizes ++ next.queueSizes)
      }

      chunked shouldBe whole
      scoreAtHorizon(chunked.state, sla = 1) shouldBe scoreAtHorizon(whole.state, sla = 1)
    }

    "clear an old FIFO backlog without introducing any new arrivals" in {
      val seed = QueueProcessingState(10, List(QueuePassenger(30, 1), QueuePassenger(50, 4)), 6L)
      val result = processQueueFromState(
        IndexedSeq(List(40), List(40)),
        Seq(List.empty, List.empty),
        sla = 2,
        seed
      )

      result shouldBe ContinuedQueue(QueueProcessingState(12, Nil, 18L), List(9, 7), List(1, 0))
      scoreAtHorizon(result.state, sla = 2) shouldBe 18L
      seed shouldBe QueueProcessingState(10, List(QueuePassenger(30, 1), QueuePassenger(50, 4)), 6L)
    }

    "retain partially processed work and original ages while reassigning only incoming join times" in {
      val seed = QueueProcessingState(5, List(QueuePassenger(70, 1)), 11L)
      val partial = advanceMinute(seed, List(QueuePassenger(10, Int.MinValue)), capacitySeconds = 20, sla = 1)

      partial shouldBe QueueMinuteResult(
        QueueProcessingState(6, List(QueuePassenger(50, 1), QueuePassenger(10, 5)), 11L),
        4,
        2
      )
      advanceMinute(partial.state, Nil, capacitySeconds = 60, sla = 1) shouldBe
        QueueMinuteResult(QueueProcessingState(7, Nil, 15L), 5, 0)
    }

    "not accumulate residual scores or mutate committed state across repeated previews" in {
      val seed = QueueProcessingState(3, List(QueuePassenger(4, 0)), 9L)
      val preview = processQueueFromState(IndexedSeq(List(4)), Seq(List.empty), sla = 1, seed)

      (1 to 3).foreach { _ =>
        scoreAtHorizon(seed, sla = 1) shouldBe 11L
        scoreAtHorizon(preview.state, sla = 1) shouldBe 11L
        processQueueFromState(IndexedSeq(List(4)), Seq(List.empty), sla = 1, seed) shouldBe preview
      }
      preview.state shouldBe QueueProcessingState(4, Nil, 11L)
      seed shouldBe QueueProcessingState(3, List(QueuePassenger(4, 0)), 9L)

      val committed = advanceMinute(seed, Nil, capacitySeconds = 2, sla = 1)
      committed.state shouldBe QueueProcessingState(4, List(QueuePassenger(2, 0)), 9L)
      scoreAtHorizon(committed.state, sla = 1) shouldBe 12L
      val completed = advanceMinute(committed.state, Nil, capacitySeconds = 2, sla = 1)
      completed.state shouldBe QueueProcessingState(5, Nil, 12L)
      scoreAtHorizon(completed.state, sla = 1) shouldBe 12L
    }

    "preserve legacy zero-capacity and zero-workload completion boundaries for a seeded queue" in {
      val seed = QueueProcessingState(
        5,
        List(QueuePassenger(0, 0), QueuePassenger(0, 1), QueuePassenger(5, 2)),
        2L
      )
      val first = advanceMinute(seed, Nil, capacitySeconds = 0, sla = 0)
      first shouldBe QueueMinuteResult(
        QueueProcessingState(6, List(QueuePassenger(0, 1), QueuePassenger(5, 2)), 7L),
        5,
        2
      )
      scoreAtHorizon(first.state, sla = 0) shouldBe 16L

      val second = advanceMinute(first.state, Nil, capacitySeconds = 0, sla = 0)
      second shouldBe QueueMinuteResult(QueueProcessingState(7, List(QueuePassenger(5, 2)), 12L), 5, 1)
      val third = advanceMinute(second.state, Nil, capacitySeconds = 0, sla = 0)
      third shouldBe QueueMinuteResult(QueueProcessingState(8, List(QueuePassenger(5, 2)), 12L), 5, 1)
      scoreAtHorizon(third.state, sla = 0) shouldBe 18L
    }

    "return the unchanged seed and no minute outputs for a zero-length preview" in {
      val seed = QueueProcessingState(8, List(QueuePassenger(5, 1)), 4L)
      processQueueFromState(IndexedSeq.empty, Seq.empty, sla = 2, seed) shouldBe ContinuedQueue(seed, Nil, Nil)
      scoreAtHorizon(seed, sla = 2) shouldBe 9L
      processQueueFromState(IndexedSeq.empty, Seq.empty, sla = 0, QueueProcessingState.Empty) shouldBe
        ContinuedQueue(QueueProcessingState.Empty, Nil, Nil)
    }

    "propagate cancellation from all seeded entry points, including a zero-length preview" in {
      val seed = QueueProcessingState(5, List(QueuePassenger(10, 1)), 3L)
      val control = CalculationControl.budget(1.minute)
      control.cancel("shutdown")

      Try(advanceMinute(seed, Nil, capacitySeconds = 0, sla = 1, control)) shouldBe
        Failure(CalculationAborted("shutdown"))
      Try(processQueueFromState(IndexedSeq.empty, Seq.empty, sla = 1, seed, control)) shouldBe
        Failure(CalculationAborted("shutdown"))
      Try(scoreAtHorizon(seed, sla = 1, control)) shouldBe Failure(CalculationAborted("shutdown"))
      seed shouldBe QueueProcessingState(5, List(QueuePassenger(10, 1)), 3L)
    }

    "check simulation progress through empty minutes with a seeded backlog" in {
      val seed = QueueProcessingState(5, List(QueuePassenger(10, 1)), 3L)
      Try(processQueueFromState(
        IndexedSeq.fill(100)(List(0)),
        Seq.fill(100)(List.empty),
        sla = 1,
        seed,
        CalculationControlTestSupport.expiringIn("advanceValidatedMinute", 20L)
      )) shouldBe Failure(CalculationAborted("expired"))
    }

    "retain Long passenger scores and valid waits at the last representable minute" in {
      val seed = QueueProcessingState(Int.MaxValue - 1, List.fill(2)(QueuePassenger(1, 0)), 0L)
      val result = advanceMinute(seed, Nil, capacitySeconds = 2, sla = 0)
      result shouldBe QueueMinuteResult(
        QueueProcessingState(Int.MaxValue, Nil, 2L * (Int.MaxValue - 1L)),
        Int.MaxValue - 1,
        0
      )
      scoreAtHorizon(result.state, sla = 0) shouldBe 2L * (Int.MaxValue - 1L)
      scoreAtHorizon(seed.copy(minute = Int.MaxValue), sla = 0) shouldBe 2L * Int.MaxValue.toLong
      processQueueFromState(IndexedSeq.empty, Seq.empty, sla = 0, result.state) shouldBe
        ContinuedQueue(result.state, Nil, Nil)
    }

    "reject minute overflow rather than wrap or return a successful partial chunk" in {
      intercept[ArithmeticException] {
        advanceMinute(QueueProcessingState(Int.MaxValue, Nil, 0L), Nil, capacitySeconds = 0, sla = 0)
      }
      intercept[ArithmeticException] {
        processQueueFromState(
          IndexedSeq(List(0), List(0)),
          Seq(List.empty, List.empty),
          sla = 0,
          QueueProcessingState(Int.MaxValue - 1, Nil, 0L)
        )
      }
    }

    "accept an exact maximum Long score and reject completed or residual score overflow" in {
      val seed = QueueProcessingState(2, List(QueuePassenger(1, 0)), Long.MaxValue - 2L)
      val result = advanceMinute(seed, Nil, capacitySeconds = 1, sla = 0)
      result.state shouldBe QueueProcessingState(3, Nil, Long.MaxValue)
      scoreAtHorizon(result.state, sla = 0) shouldBe Long.MaxValue
      scoreAtHorizon(seed, sla = 0) shouldBe Long.MaxValue

      val overflowing = seed.copy(completedPassengerMinutesAboveSla = Long.MaxValue)
      intercept[ArithmeticException] {
        advanceMinute(overflowing, Nil, capacitySeconds = 1, sla = 0)
      }
      intercept[ArithmeticException] {
        scoreAtHorizon(overflowing, sla = 0)
      }
    }

    "reject malformed seeds at every public continuation entry point" in {
      val invalidStates = Seq(
        QueueProcessingState(-1, Nil, 0L),
        QueueProcessingState(0, List(QueuePassenger(1, 0)), 0L),
        QueueProcessingState(2, List(QueuePassenger(1, -1)), 0L),
        QueueProcessingState(2, List(QueuePassenger(1, 2)), 0L),
        QueueProcessingState(2, List(QueuePassenger(1, 3)), 0L),
        QueueProcessingState(2, List(QueuePassenger(-1, 0)), 0L),
        QueueProcessingState(2, Nil, -1L),
        QueueProcessingState(3, List(QueuePassenger(1, 1), QueuePassenger(1, 0)), 0L)
      )
      invalidStates.foreach { seed =>
        intercept[IllegalArgumentException] {
          advanceMinute(seed, Nil, capacitySeconds = 0, sla = 0)
        }
        intercept[IllegalArgumentException] {
          processQueueFromState(IndexedSeq.empty, Seq.empty, sla = 0, seed)
        }
        intercept[IllegalArgumentException] {
          scoreAtHorizon(seed, sla = 0)
        }
      }
    }

    "reject invalid SLA, loads, capacities and unequal minute counts" in {
      val seed = QueueProcessingState.Empty
      intercept[IllegalArgumentException] {
        advanceMinute(seed, Nil, capacitySeconds = 0, sla = -1)
      }
      intercept[IllegalArgumentException] {
        processQueueFromState(IndexedSeq.empty, Seq.empty, sla = -1, seed)
      }
      intercept[IllegalArgumentException] {
        scoreAtHorizon(seed, sla = -1)
      }
      intercept[IllegalArgumentException] {
        advanceMinute(seed, Nil, capacitySeconds = -1, sla = 0)
      }
      intercept[IllegalArgumentException] {
        advanceMinute(seed, List(QueuePassenger(-1)), capacitySeconds = 0, sla = 0)
      }
      intercept[IllegalArgumentException] {
        processQueueFromState(IndexedSeq(List(0)), Seq(List(QueuePassenger(-1))), sla = 0, seed)
      }
      Seq(List(-1), List(10, -1), List(Int.MaxValue, 1)).foreach { capacity =>
        intercept[IllegalArgumentException] {
          processQueueFromState(IndexedSeq(capacity), Seq(List.empty), sla = 0, seed)
        }
      }
      Seq(
        (IndexedSeq.empty[List[Int]], Seq(List(QueuePassenger(1)))),
        (IndexedSeq(List(1)), Seq.empty[List[QueuePassenger]]),
        (IndexedSeq(List(1)), Seq(List(QueuePassenger(1)), List.empty))
      ).foreach { case (capacity, passengers) =>
        intercept[IllegalArgumentException] {
          processQueueFromState(capacity, passengers, sla = 0, seed)
        }
      }
    }
  }

  "processQueueWithMetrics" should {
    "preserve legacy FIFO, wait times and queue sizes" in {
      val scenarios: Seq[(IndexedSeq[List[Int]], Seq[List[QueuePassenger]])] = Seq(
        (IndexedSeq.empty, Seq.empty),
        (IndexedSeq(List(60)), Seq(List.empty)),
        (IndexedSeq.fill(2)(List(60)), Seq(List(QueuePassenger(65)), List.empty)),
        (
          IndexedSeq.fill(4)(List(60)),
          Seq(
            List(QueuePassenger(50), QueuePassenger(50), QueuePassenger(120)),
            List(QueuePassenger(45)),
            List.empty,
            List.empty
          )
        ),
        (
          IndexedSeq.fill(5)(List(60)),
          Seq(List(QueuePassenger(65)), List.empty, List.empty, List(QueuePassenger(65)), List.empty)
        ),
        (
          IndexedSeq.fill(3)(List(0)),
          Seq(List(QueuePassenger(65)), List(QueuePassenger(30)), List.empty)
        ),
        (
          IndexedSeq(List.empty[Int], List(0), List(30, 30)),
          Seq(List(QueuePassenger(65)), List.empty, List.empty)
        ),
        (
          IndexedSeq(List(0), List(0), List(0), List(5)),
          Seq(List(QueuePassenger(0), QueuePassenger(0), QueuePassenger(5)), List.empty, List.empty, List.empty)
        )
      )

      scenarios.zipWithIndex.foreach { case ((capacity, passengers), index) =>
        withClue(s"Scenario $index: ") {
          val result = processQueueWithMetrics(capacity, passengers, sla = 1)
          (result.remainingPassengers, result.waitTimes, result.queueSizes) shouldBe processQueue(capacity, passengers)
        }
      }
    }

    "return empty metrics for an empty simulation" in {
      processQueueWithMetrics(IndexedSeq.empty, Seq.empty, sla = 0) shouldBe
        ProcessedQueue(List.empty, List.empty, List.empty, 0L)
    }

    "score ten passengers completed five minutes above SLA as fifty passenger minutes regardless of workload" in {
      val capacity = IndexedSeq.fill(7)(List(0)) :+ List(100)
      val passengers = Seq(List.tabulate(10)(index => QueuePassenger(index + 1))) ++ Seq.fill(7)(List.empty)

      val result = processQueueWithMetrics(capacity, passengers, sla = 2)

      result.remainingPassengers shouldBe List.empty
      result.passengerMinutesAboveSla shouldBe 50L
    }

    "not score passengers completed below or exactly at SLA" in {
      (0 to 2).foreach { completionMinute =>
        val capacity = IndexedSeq.fill(completionMinute)(List(0)) :+ List(1)
        val passengers = Seq(List(QueuePassenger(1))) ++ Seq.fill(completionMinute)(List.empty)

        processQueueWithMetrics(capacity, passengers, sla = 2).passengerMinutesAboveSla shouldBe 0L
      }
    }

    "score a remaining passenger at the exclusive end of the simulation" in {
      val result = processQueueWithMetrics(
        IndexedSeq.fill(5)(List(0)),
        Seq(List(QueuePassenger(10))) ++ Seq.fill(4)(List.empty),
        sla = 2
      )

      result.remainingPassengers shouldBe List(QueuePassenger(10, 0))
      result.passengerMinutesAboveSla shouldBe 3L
    }

    "not score a late remaining passenger whose wait is exactly at SLA" in {
      val result = processQueueWithMetrics(
        IndexedSeq.fill(5)(List(0)),
        Seq(List.empty, List.empty, List.empty, List(QueuePassenger(10)), List.empty),
        sla = 2
      )

      result.remainingPassengers shouldBe List(QueuePassenger(10, 3))
      result.passengerMinutesAboveSla shouldBe 0L
    }

    "score a partially processed passenger only once on completion" in {
      val result = processQueueWithMetrics(
        IndexedSeq.fill(5)(List(1)),
        Seq(List(QueuePassenger(5))) ++ Seq.fill(4)(List.empty),
        sla = 1
      )

      result.remainingPassengers shouldBe List.empty
      result.passengerMinutesAboveSla shouldBe 3L
    }

    "score a partially processed passenger only once at the exclusive end" in {
      val result = processQueueWithMetrics(
        IndexedSeq.fill(5)(List(1)),
        Seq(List(QueuePassenger(10))) ++ Seq.fill(4)(List.empty),
        sla = 2
      )

      result.remainingPassengers shouldBe List(QueuePassenger(5, 0))
      result.passengerMinutesAboveSla shouldBe 3L
    }

    "preserve and score the backlog when there are no resources" in {
      val result = processQueueWithMetrics(
        IndexedSeq.fill(5)(List.empty[Int]),
        Seq(List(QueuePassenger(1), QueuePassenger(100))) ++ Seq.fill(4)(List.empty),
        sla = 2
      )

      result shouldBe ProcessedQueue(
        List(QueuePassenger(1, 0), QueuePassenger(100, 0)),
        List(0, 1, 2, 3, 4),
        List.fill(5)(2),
        6L
      )
    }

    "process the FIFO backlog when capacity resumes" in {
      val result = processQueueWithMetrics(
        IndexedSeq(List(0), List(0), List(0), List(0), List(2), List(3)),
        Seq(List(QueuePassenger(2), QueuePassenger(3))) ++ Seq.fill(5)(List.empty),
        sla = 1
      )

      result shouldBe ProcessedQueue(List.empty, List(0, 1, 2, 3, 4, 5), List(2, 2, 2, 2, 1, 0), 7L)
    }

    "combine completed and remaining scores using each passenger's arrival minute" in {
      val result = processQueueWithMetrics(
        IndexedSeq(List(0), List(0), List(2), List(1), List(0)),
        Seq(List(QueuePassenger(2)), List(QueuePassenger(1)), List(QueuePassenger(3)), List.empty, List.empty),
        sla = 1
      )

      result shouldBe ProcessedQueue(List(QueuePassenger(3, 2)), List(0, 1, 2, 2, 2), List(1, 2, 2, 1, 1), 4L)
    }

    "use legacy zero-workload semantics even when capacity is zero" in {
      val result = processQueueWithMetrics(
        IndexedSeq(List(0), List(0), List(0), List(5)),
        Seq(List(QueuePassenger(0), QueuePassenger(0), QueuePassenger(5)), List.empty, List.empty, List.empty),
        sla = 0
      )

      result shouldBe ProcessedQueue(List.empty, List(0, 1, 2, 3), List(2, 1, 1, 0), 4L)
    }

    "score a zero-workload passenger left behind by zero capacity at the exclusive end" in {
      processQueueWithMetrics(
        IndexedSeq(List.empty[Int]),
        Seq(List(QueuePassenger(0), QueuePassenger(0))),
        sla = 0
      ) shouldBe ProcessedQueue(List(QueuePassenger(0, 0)), List(0), List(1), 1L)
    }

    "process all zero-workload passengers when positive capacity is available" in {
      processQueueWithMetrics(
        IndexedSeq(List(1)),
        Seq(List.fill(3)(QueuePassenger(0))),
        sla = 0
      ) shouldBe ProcessedQueue(List.empty, List(0), List(0), 0L)
    }

    "reset supplied join times to the relative arrival minute" in {
      val result = processQueueWithMetrics(
        IndexedSeq.fill(4)(List(0)),
        Seq(List.empty, List(QueuePassenger(1, -100)), List(QueuePassenger(1, 100)), List.empty),
        sla = 1
      )

      result.remainingPassengers shouldBe List(QueuePassenger(1, 1), QueuePassenger(1, 2))
      result.passengerMinutesAboveSla shouldBe 3L
    }

    "accept a capacity sum equal to the maximum Int" in {
      processQueueWithMetrics(
        IndexedSeq(List(Int.MaxValue - 1, 1)),
        Seq(List(QueuePassenger(Int.MaxValue))),
        sla = 0
      ) shouldBe ProcessedQueue(List.empty, List(0), List(0), 0L)
    }

    "reject unequal capacity and passenger minute counts" in {
      val scenarios: Seq[(IndexedSeq[List[Int]], Seq[List[QueuePassenger]])] = Seq(
        (IndexedSeq.empty, Seq(List(QueuePassenger(1)))),
        (IndexedSeq(List(1)), Seq.empty),
        (IndexedSeq(List(1)), Seq(List(QueuePassenger(1)), List.empty))
      )

      scenarios.foreach { case (capacity, passengers) =>
        intercept[IllegalArgumentException] {
          processQueueWithMetrics(capacity, passengers, sla = 0)
        }
      }
    }

    "reject a negative SLA even for an empty simulation" in {
      intercept[IllegalArgumentException] {
        processQueueWithMetrics(IndexedSeq.empty, Seq.empty, sla = -1)
      }
    }

    "reject any negative capacity even when the total is nonnegative" in {
      Seq(List(-1), List(10, -1)).foreach { capacity =>
        intercept[IllegalArgumentException] {
          processQueueWithMetrics(IndexedSeq(capacity), Seq(List.empty), sla = 0)
        }
      }
    }

    "reject negative passenger workloads" in {
      intercept[IllegalArgumentException] {
        processQueueWithMetrics(IndexedSeq(List(1)), Seq(List(QueuePassenger(-1))), sla = 0)
      }
    }

    "reject capacity sums above the maximum Int" in {
      Seq(List(Int.MaxValue, 1), List(Int.MaxValue, Int.MaxValue)).foreach { capacity =>
        intercept[IllegalArgumentException] {
          processQueueWithMetrics(IndexedSeq(capacity), Seq(List.empty), sla = 0)
        }
      }
    }
  }

  private def randomPassengersForMinutes(minutes: Int): Seq[List[QueuePassenger]] = (0 until minutes).map(_ =>
    randomPassengersForMinute
  )

  private def randomPassengersForMinute: List[QueuePassenger] = (0 to (Math.random() * 20).toInt).map(_ =>
    QueuePassenger(randomProcessingSeconds)
  ).toList

  private def randomProcessingSeconds: Int = (Math.random() * 100).toInt + 40
}
