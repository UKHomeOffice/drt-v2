package services.crunch.deskrecs

import services.WorkloadProcessorsProvider
import services.crunch.CalculationControl
import services.crunch.desklimits.flexed.BalancedDeskLimitsSnapshot
import services.workload.{ ContinuedQueue, QueuePassenger, QueueProcessingState, QueueProcessor }
import uk.gov.homeoffice.drt.ports.Queues.{ EGate, Queue }

import scala.annotation.tailrec

case class TimedQueueDemand(required: IndexedSeq[Int], evaluations: Int, adjusted: Boolean)

object BacklogDemandTiming {

  /** Staffing savings must not make service worse than the independent reference from the actual queue state. */
  def preserveReferenceQuality(
      state: QueueProcessingState,
      incoming: IndexedSeq[List[QueuePassenger]],
      processors: WorkloadProcessorsProvider,
      reference: IndexedSeq[Int],
      proposed: IndexedSeq[Int],
      sla: Int,
      control: CalculationControl = CalculationControl.Unlimited
  ): TimedQueueDemand = {
    control.check()
    val length = incoming.size
    require(length > 0 && processors.minutes == length, "Quality profiles must match a non-empty commitment")
    require(reference.size == length && proposed.size == length, "Quality demands must match the commitment")
    val upper = incoming.indices.map { minute =>
      control.check()
      val maximum = processors.forMinute(minute).processors.size
      require(reference(minute) >= 0 && reference(minute) <= maximum, "Invalid reference demand")
      require(proposed(minute) >= 0 && proposed(minute) <= maximum, "Invalid proposed quality demand")
      Math.max(reference(minute), proposed(minute))
    }

    def preview(prefix: IndexedSeq[Int]): ContinuedQueue = {
      val capacities = prefix.indices.map { minute =>
        control.check()
        List(Math.toIntExact(processors.forMinute(minute).capacityForServers(prefix(minute)).toLong * 60L))
      }
      val result = QueueProcessor.processQueueFromState(capacities, incoming, sla, state, control)
      require(
        result.waitTimes.size == length && result.queueSizes.size == length,
        "Incomplete service-quality preview"
      )
      result
    }

    val baseline = preview(reference)
    if (reference == proposed) return TimedQueueDemand(proposed, 1, adjusted = false)
    val baselineScore = QueueProcessor.scoreAtHorizon(baseline.state, sla, control)
    val baselineRemaining = baseline.queueSizes.last
    def noWorse(prefix: IndexedSeq[Int]): Boolean = {
      control.check()
      val candidate = preview(prefix)
      val remaining = candidate.queueSizes.last
      // Identical FIFO arrivals leave the same head passenger when the remaining counts match.
      val progress = remaining < baselineRemaining || (
        remaining == baselineRemaining && (
          remaining == 0 ||
            candidate.state.remainingPassengers.head.load <= baseline.state.remainingPassengers.head.load
        )
      )
      progress &&
      QueueProcessor.scoreAtHorizon(candidate.state, sla, control) <= baselineScore &&
      candidate.waitTimes.iterator.zip(baseline.waitTimes.iterator).forall { case (actual, expected) =>
        control.check()
        actual <= expected
      } &&
      candidate.queueSizes.iterator.zip(baseline.queueSizes.iterator).forall { case (actual, expected) =>
        control.check()
        actual <= expected
      }
    }

    if (noWorse(proposed)) TimedQueueDemand(proposed, 2, adjusted = false)
    else {
      def raised(level: Int): IndexedSeq[Int] = proposed.indices.map { minute =>
        control.check()
        Math.min(upper(minute), Math.max(proposed(minute), level))
      }
      @tailrec
      def find(low: Int, high: Int, evaluations: Int): TimedQueueDemand = {
        control.check()
        if (low >= high) TimedQueueDemand(raised(low), evaluations, adjusted = true)
        else {
          val middle = low + (high - low) / 2
          if (noWorse(raised(middle))) find(low, middle, evaluations + 1)
          else find(middle + 1, high, evaluations + 1)
        }
      }
      find(0, upper.max, 2)
    }
  }

  /** Future upper bounds reserve only feasible minimums, never another queue's tentative extras. */
  def futureProcessorLimits(
      snapshot: BalancedDeskLimitsSnapshot,
      control: CalculationControl = CalculationControl.Unlimited
  ): Map[Queue, IndexedSeq[Int]] = {
    control.check()
    val queues = snapshot.minimums.keys.toVector.sortBy(_.toString)
    val length = snapshot.minuteMillis.size
    require(length > 0 && snapshot.terminalDesks >= 0, "Future limits require a valid resource window")
    require(snapshot.processorsByQueue.keySet == queues.toSet, "Future limits require every queue's processors")
    queues.foreach { queue =>
      control.check()
      require(
        snapshot.minimums(queue).size == length && snapshot.processorsByQueue(queue).minutes == length,
        "Future resource schedules must match the request window"
      )
    }
    require(snapshot.availableStaff.forall(_.size == length), "Future staffing must match the request window")
    val manual = queues.toSet - EGate
    val perMinute = snapshot.minuteMillis.indices.map { minute =>
      control.check()
      val caps = queues.map { queue =>
        control.check()
        queue -> snapshot.processorsByQueue(queue).forMinute(minute).processors.size
      }.toMap
      val minimums = queues.map { queue =>
        control.check()
        val minimum = snapshot.minimums(queue)(minute)
        queue -> DeskDemand(minimum, minimum)
      }.toMap
      val protectedMinimums = ProportionalDeskAllocator.allocateWithResources(
        snapshot.minuteMillis(minute) / 60000L,
        minimums,
        caps,
        manual,
        snapshot.terminalDesks,
        snapshot.availableStaff.map(_(minute)),
        control
      )
      val staffReserved = protectedMinimums.valuesIterator.map(_.toLong).sum
      val manualReserved = manual.iterator.map(queue => protectedMinimums(queue).toLong).sum
      queues.map { queue =>
        control.check()
        val ownMinimum = protectedMinimums(queue).toLong
        val staffRoom = snapshot.availableStaff.fold(caps(queue).toLong) { staff =>
          staff(minute).toLong - (staffReserved - ownMinimum)
        }
        val deskRoom = if (manual.contains(queue))
          snapshot.terminalDesks.toLong - (manualReserved - ownMinimum)
        else caps(queue).toLong
        queue -> Math.max(0L, Math.min(caps(queue).toLong, Math.min(staffRoom, deskRoom))).toInt
      }.toMap
    }
    queues.map { queue =>
      control.check()
      queue -> perMinute.map(_(queue))
    }.toMap
  }

  /** Current demand keeps its full processor bounds; only the forecast tail is limited to feasible minimums. */
  def preserveService(
      state: QueueProcessingState,
      incoming: IndexedSeq[List[QueuePassenger]],
      processors: WorkloadProcessorsProvider,
      futureLimits: IndexedSeq[Int],
      proposed: IndexedSeq[Int],
      sla: Int,
      control: CalculationControl = CalculationControl.Unlimited
  ): TimedQueueDemand = {
    control.check()
    val commitment = proposed.size
    require(commitment > 0 && commitment <= incoming.size, "A timing decision requires a valid committed interval")
    require(
      processors.minutes == incoming.size && futureLimits.size == incoming.size,
      "Timing profiles must match the remaining forecast"
    )
    require(
      state.minute >= 0 && state.minute.toLong + incoming.size <= Int.MaxValue,
      "Timing forecast must fit in the request-relative minute range"
    )
    require(state.completedPassengerMinutesAboveSla >= 0 && sla >= 0, "Invalid timing score state")
    val maxima = incoming.indices.map { minute =>
      control.check()
      val maximum = processors.forMinute(minute).processors.size
      require(futureLimits(minute) >= 0 && futureLimits(minute) <= maximum, "Invalid future processor limit")
      if (minute < commitment)
        require(proposed(minute) >= 0 && proposed(minute) <= maximum, "Invalid proposed commitment")
      maximum
    }
    val hasPassengers = state.remainingPassengers.nonEmpty || incoming.exists { arrivals =>
      control.check()
      arrivals.nonEmpty
    }
    if (!hasPassengers) return TimedQueueDemand(proposed, 0, adjusted = false)

    def score(prefix: IndexedSeq[Int]): Long = {
      control.check()
      val capacities = incoming.indices.map { minute =>
        control.check()
        val count = if (minute < commitment) prefix(minute) else futureLimits(minute)
        val seconds = processors.forMinute(minute).capacityForServers(count).toLong * 60L
        require(seconds >= 0 && seconds <= Int.MaxValue, "Timing capacity must be representable in seconds")
        List(seconds.toInt)
      }
      val preview = QueueProcessor.processQueueFromState(capacities, incoming, sla, state, control)
      QueueProcessor.scoreAtHorizon(preview.state, sla, control)
    }

    val maximumPrefix = maxima.take(commitment)
    val attainable = score(maximumPrefix)
    if (score(proposed) <= attainable) TimedQueueDemand(proposed, 2, adjusted = false)
    else {
      val maximum = maximumPrefix.max
      def raised(level: Int): IndexedSeq[Int] = proposed.indices.map { minute =>
        control.check()
        Math.min(maximumPrefix(minute), Math.max(proposed(minute), level))
      }
      @tailrec
      def find(low: Int, high: Int, evaluations: Int): TimedQueueDemand = {
        control.check()
        if (low >= high) TimedQueueDemand(raised(low), evaluations, adjusted = true)
        else {
          val middle = low + (high - low) / 2
          if (score(raised(middle)) <= attainable) find(low, middle, evaluations + 1)
          else find(middle + 1, high, evaluations + 1)
        }
      }
      find(0, maximum, 2)
    }
  }
}
