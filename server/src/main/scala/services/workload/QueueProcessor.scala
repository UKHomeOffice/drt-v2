package services.workload

import services.crunch.CalculationControl

import scala.annotation.tailrec

case class QueuePassenger(load: Int, joinTime: Int)

object QueuePassenger {
  def apply(load: Int): QueuePassenger = QueuePassenger(load, 0)
}

/** The next request-relative minute, with original arrival times and only completed passenger scores. */
case class QueueProcessingState(
    minute: Int,
    remainingPassengers: List[QueuePassenger],
    completedPassengerMinutesAboveSla: Long
)

object QueueProcessingState {
  val Empty: QueueProcessingState = QueueProcessingState(0, Nil, 0L)
}

case class QueueMinuteResult(state: QueueProcessingState, waitTime: Int, queueSize: Int)

case class ContinuedQueue(state: QueueProcessingState, waitTimes: List[Int], queueSizes: List[Int])

case class ProcessedQueue(
    remainingPassengers: List[QueuePassenger],
    waitTimes: List[Int],
    queueSizes: List[Int],
    passengerMinutesAboveSla: Long
)

object QueueProcessor {
  @tailrec
  def applyCapacity(
      minute: Int,
      capacitySeconds: Int,
      queue: List[QueuePassenger],
      waitTime: Int = 0
  ): (List[QueuePassenger], Int) = {
    def calcWaitTime(previousWaitTime: Int, joinTime: Int): Int = minute - joinTime match {
      case x if x > previousWaitTime => x
      case _                         => previousWaitTime
    }

    queue match {
      case Nil => (Nil, 0)

      case currentPassenger :: tail =>
        val paxIsFullyProcessed = capacitySeconds >= currentPassenger.load
        val newWaitTime = calcWaitTime(waitTime, currentPassenger.joinTime)

        if (paxIsFullyProcessed) {
          val newCapacity = capacitySeconds - currentPassenger.load
          if (newCapacity > 0 && tail.nonEmpty) {
            applyCapacity(minute, newCapacity, tail, newWaitTime)
          } else {
            (tail, newWaitTime)
          }
        } else {
          val updatedPax = currentPassenger.copy(load = currentPassenger.load - capacitySeconds)
          (updatedPax :: tail, newWaitTime)
        }
    }
  }

  def processQueue(
      capacityMinutes: IndexedSeq[List[Int]],
      paxLoadMinutes: Seq[List[QueuePassenger]],
      control: CalculationControl = CalculationControl.Unlimited
  ): (List[QueuePassenger], List[Int], List[Int]) = {
    control.check()
    val (queue, waitTimes, queueSizes) =
      paxLoadMinutes.zipWithIndex.foldLeft((List.empty[QueuePassenger], List.empty[Int], List.empty[Int])) {
        case ((incomingPax, previousWaits, previousQueueSizes), (incoming, minute)) =>
          control.check()
          val startQueueForMinute = incomingPax ::: incoming.map(_.copy(joinTime = minute))
          val (endQueueForMinute, maxWait) = applyCapacity(minute, capacityMinutes(minute).sum, startQueueForMinute)
          (endQueueForMinute, maxWait :: previousWaits, endQueueForMinute.size :: previousQueueSizes)
      }
    (queue, waitTimes.reverse, queueSizes.reverse)
  }

  def processQueueWithMetrics(
      capacityMinutes: IndexedSeq[List[Int]],
      paxLoadMinutes: Seq[List[QueuePassenger]],
      sla: Int,
      control: CalculationControl = CalculationControl.Unlimited
  ): ProcessedQueue = {
    val result = processQueueFromState(capacityMinutes, paxLoadMinutes, sla, QueueProcessingState.Empty, control)
    ProcessedQueue(
      result.state.remainingPassengers,
      result.waitTimes,
      result.queueSizes,
      scoreAtHorizon(result.state, sla, control)
    )
  }

  def advanceMinute(
      state: QueueProcessingState,
      incoming: List[QueuePassenger],
      capacitySeconds: Int,
      sla: Int,
      control: CalculationControl = CalculationControl.Unlimited
  ): QueueMinuteResult = {
    control.check()
    require(sla >= 0, "SLA must be nonnegative")
    validateState(state)
    require(capacitySeconds >= 0, "Capacity must be nonnegative")
    validatePassengers(incoming)
    advanceValidatedMinute(state, incoming, capacitySeconds, sla, control)
  }

  /** Continues at the seed's next minute; the supplied arrivals belong only to this chunk. */
  def processQueueFromState(
      capacityMinutes: IndexedSeq[List[Int]],
      paxLoadMinutes: Seq[List[QueuePassenger]],
      sla: Int,
      initialState: QueueProcessingState,
      control: CalculationControl = CalculationControl.Unlimited
  ): ContinuedQueue = {
    control.check()
    val minuteCount = paxLoadMinutes.length
    require(capacityMinutes.length == minuteCount, "Capacity and passenger minutes must have equal lengths")
    require(sla >= 0, "SLA must be nonnegative")
    validateState(initialState)
    val _ = Math.addExact(initialState.minute, minuteCount)

    val capacityTotals = capacityMinutes.map { capacities =>
      control.check()
      capacities.foldLeft(0) { (total, capacity) =>
        require(capacity >= 0, "Capacity must be nonnegative")
        require(capacity <= Int.MaxValue - total, "Total capacity for a minute must fit in an Int")
        total + capacity
      }
    }
    paxLoadMinutes.foreach { passengers =>
      control.check()
      validatePassengers(passengers)
    }

    val (state, waitTimes, queueSizes) =
      paxLoadMinutes.iterator.zip(capacityTotals.iterator).foldLeft(
        (initialState, List.empty[Int], List.empty[Int])
      ) { case ((previousState, previousWaits, previousQueueSizes), (incoming, capacity)) =>
        control.check()
        val result = advanceValidatedMinute(previousState, incoming, capacity, sla, control)
        (result.state, result.waitTime :: previousWaits, result.queueSize :: previousQueueSizes)
      }

    ContinuedQueue(state, waitTimes.reverse, queueSizes.reverse)
  }

  def scoreAtHorizon(
      state: QueueProcessingState,
      sla: Int,
      control: CalculationControl = CalculationControl.Unlimited
  ): Long = {
    control.check()
    require(sla >= 0, "SLA must be nonnegative")
    validateState(state)
    scorePassengers(
      state.remainingPassengers.iterator,
      state.minute,
      state.completedPassengerMinutesAboveSla,
      sla
    )
  }

  private def advanceValidatedMinute(
      state: QueueProcessingState,
      incoming: List[QueuePassenger],
      capacitySeconds: Int,
      sla: Int,
      control: CalculationControl
  ): QueueMinuteResult = {
    control.check()
    val nextMinute = Math.addExact(state.minute, 1)
    val startQueue = state.remainingPassengers ::: incoming.map(_.copy(joinTime = state.minute))
    val (endQueue, maxWait) = applyCapacity(state.minute, capacitySeconds, startQueue)
    val queueSize = endQueue.size
    val completedCount = startQueue.size - queueSize
    val score = scorePassengers(
      startQueue.iterator.take(completedCount),
      state.minute,
      state.completedPassengerMinutesAboveSla,
      sla
    )
    QueueMinuteResult(QueueProcessingState(nextMinute, endQueue, score), maxWait, queueSize)
  }

  private def validatePassengers(passengers: List[QueuePassenger]): Unit =
    require(passengers.forall(_.load >= 0), "Passenger loads must be nonnegative")

  private def validateState(state: QueueProcessingState): Unit = {
    require(state.minute >= 0, "State minute must be nonnegative")
    require(state.completedPassengerMinutesAboveSla >= 0L, "Completed passenger score must be nonnegative")
    val _ = state.remainingPassengers.foldLeft(0) { (previousJoinTime, passenger) =>
      require(passenger.load >= 0, "Passenger loads must be nonnegative")
      require(
        passenger.joinTime >= 0 && passenger.joinTime < state.minute,
        "Pending passenger join times must precede the next minute and be nonnegative"
      )
      require(passenger.joinTime >= previousJoinTime, "Pending passengers must be in FIFO arrival order")
      passenger.joinTime
    }
    ()
  }

  private def scorePassengers(
      passengers: Iterator[QueuePassenger],
      minute: Int,
      previousScore: Long,
      sla: Int
  ): Long =
    passengers.foldLeft(previousScore) { (score, passenger) =>
      val minutesAboveSla = math.max(0L, minute.toLong - passenger.joinTime.toLong - sla.toLong)
      Math.addExact(score, minutesAboveSla)
    }
}
