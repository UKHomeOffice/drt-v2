package services.crunch.deskrecs

import drt.shared.CrunchApi.DeskRecMinute
import services.crunch.CalculationControl
import services.crunch.desklimits.flexed.BalancedDeskLimitsSnapshot
import services.workload.{ QueuePassenger, QueueProcessor }
import uk.gov.homeoffice.drt.ports.Queues.{ EGate, Queue }
import uk.gov.homeoffice.drt.ports.Terminals.Terminal

import scala.collection.immutable.NumericRange

case class QueueAllocationEvaluation(
    desks: Option[IndexedSeq[Int]],
    passengerCount: Option[Long],
    deskMinutes: Option[Long],
    residualPassengers: Option[Int],
    score: Option[Long],
    allocationChurn: Option[Long],
    violations: List[String],
    slaBreachMinutes: Option[Long] = None,
    queuePassengerMinutes: Option[Long] = None
) {
  def valid: Boolean = violations.isEmpty && score.isDefined
}

case class AllocationEvaluation(
    queues: Map[Queue, QueueAllocationEvaluation],
    violations: List[String],
    missingQueues: Set[Queue],
    processorsUsedByMinute: Option[IndexedSeq[Long]] = None,
    availableStaff: Option[IndexedSeq[Int]] = None
) {
  def valid: Boolean = violations.isEmpty && missingQueues.isEmpty && queues.values.forall(_.valid)
}

/** Evaluates final schedules against the frozen inputs, independently of either optimiser's claimed metrics. */
object QueueAllocationComparison {
  type LegacyResults = Map[Queue, (Iterable[Int], Iterable[Int], Iterable[Double])]

  def evaluateLegacy(
      snapshot: BalancedDeskLimitsSnapshot,
      passengers: Map[Queue, IndexedSeq[Iterable[Double]]],
      slas: Map[Queue, Int],
      result: LegacyResults,
      control: CalculationControl = CalculationControl.Unlimited
  ): AllocationEvaluation = {
    control.check()
    val expected = snapshot.configuredMinimums.keySet
    val missing = expected -- result.keySet
    val unexpected = result.keySet -- expected
    val windowValid = snapshot.minuteMillis.nonEmpty &&
      snapshot.minuteMillis.forall { minute =>
        control.check()
        Math.floorMod(minute, 60000L) == 0
      } &&
      snapshot.minuteMillis.zip(snapshot.minuteMillis.drop(1)).forall { case (previous, next) =>
        control.check()
        next > previous && next - previous == 60000L
      }
    val snapshotViolations = List(
      Option.when(!windowValid)("invalid_window"),
      Option.when(snapshot.terminalDesks < 0)("invalid_terminal_capacity"),
      Option.when(passengers.keySet != expected)("passenger_queue_coverage"),
      Option.when(slas.keySet != expected)("sla_queue_coverage"),
      Option.when(snapshot.minimums.keySet != expected)("minimum_queue_coverage"),
      Option.when(snapshot.processorsByQueue.keySet != expected)(
        "processor_queue_coverage"
      ),
      Option.when(snapshot.availableStaff.exists(staff =>
        staff.size != snapshot.minuteMillis.size || staff.exists { available =>
          control.check()
          available < 0
        }
      ))("invalid_staff_schedule")
    ).flatten
    val queues = (expected ++ passengers.keySet ++ result.keySet).toSeq.sortBy(_.toString).map { queue =>
      control.check()
      queue ->
        evaluateQueue(snapshot, passengers.get(queue), slas.get(queue), result.get(queue), queue, windowValid, control)
    }.toMap
    val coverageViolations =
      missing.toList.sortBy(_.toString).map(queue => s"missing_queue:$queue") ++
        unexpected.toList.sortBy(_.toString).map(queue => s"unexpected_queue:$queue")
    val queueViolations = queues.toList.sortBy(_._1.toString).flatMap { case (queue, evaluation) =>
      evaluation.violations.map(code => s"$queue:$code")
    }
    val completeSchedules = (expected ++ result.keySet).toSeq.map(queue => queues(queue).desks)
    val used =
      if (completeSchedules.forall(_.isDefined)) {
        val schedules = completeSchedules.flatten
        Some(snapshot.minuteMillis.indices.map { minute =>
          control.check()
          schedules.foldLeft(0L)((total, desks) => total + desks(minute).toLong)
        })
      } else None
    val manualUsed = if (completeSchedules.forall(_.isDefined)) {
      Some(snapshot.minuteMillis.indices.map { minute =>
        control.check()
        queues.iterator.filterNot(_._1 == EGate).foldLeft(0L) { case (total, (_, queue)) =>
          total + queue.desks.get(minute).toLong
        }
      })
    } else None
    val resourceViolations = used.toList.flatMap { usage =>
      List(
        Option.when(manualUsed.exists(_.exists(_ > snapshot.terminalDesks.toLong)))("terminal_capacity_exceeded"),
        Option.when(snapshot.availableStaff.exists(staff =>
          staff.size == usage.size && usage.zip(staff).exists { case (total, available) => total > available.toLong }
        ))("available_staff_exceeded")
      ).flatten
    }
    AllocationEvaluation(
      queues,
      snapshotViolations ++ coverageViolations ++ queueViolations ++ resourceViolations,
      missing,
      used,
      snapshot.availableStaff
    )
  }

  def evaluateBalanced(
      snapshot: BalancedDeskLimitsSnapshot,
      passengers: Map[Queue, IndexedSeq[Iterable[Double]]],
      slas: Map[Queue, Int],
      result: BalancedTerminalResult,
      control: CalculationControl = CalculationControl.Unlimited
  ): AllocationEvaluation = {
    val schedules: LegacyResults = result.queues.map { case (queue, output) =>
      control.check()
      queue ->
        ((
          output.desks,
          output.waits,
          output.queueSizes.map { size =>
            control.check()
            size.toDouble
          }
        ))
    }
    evaluateLegacy(snapshot, passengers, slas, schedules, control)
  }

  def validatedNeutralMinutes(
      terminal: Terminal,
      minuteMillis: NumericRange[Long],
      passengers: Map[Queue, IndexedSeq[Iterable[Double]]],
      work: Map[Queue, Seq[Double]],
      snapshot: BalancedDeskLimitsSnapshot,
      slas: Map[Queue, Int],
      result: BalancedTerminalResult,
      control: CalculationControl
  ): Iterable[DeskRecMinute] = {
    control.check()
    val evaluation = QueueAllocationComparison.evaluateBalanced(snapshot, passengers, slas, result, control)
    require(evaluation.valid, "Neutral output failed resource validation")
    strictMinutes(
      terminal,
      minuteMillis,
      passengers,
      work,
      result.queues.map { case (queue, value) =>
        queue -> (
          value.desks: Iterable[Int],
          value.waits: Iterable[Int],
          value.queueSizes.map(_.toDouble): Iterable[Double]
        )
      },
      control
    )
  }

  def strictMinutes(
      terminal: Terminal,
      minuteMillis: NumericRange[Long],
      passengers: Map[Queue, IndexedSeq[Iterable[Double]]],
      work: Map[Queue, Seq[Double]],
      results: QueueAllocationComparison.LegacyResults,
      control: CalculationControl
  ): Iterable[DeskRecMinute] = {
    require(results.keySet == passengers.keySet && work.keySet == passengers.keySet, "Incomplete authoritative queues")
    results.toSeq.sortBy(_._1.toString).flatMap { case (queue, (rawDesks, rawWaits, rawSizes)) =>
      val desks = rawDesks.toIndexedSeq
      val waits = rawWaits.toIndexedSeq
      val sizes = rawSizes.toIndexedSeq
      require(
        Seq(desks.size, waits.size, sizes.size, passengers(queue).size, work(queue).size)
          .forall(_ == minuteMillis.size),
        "Incomplete authoritative minute schedule"
      )
      minuteMillis.indices.map { minute =>
        control.check()
        DeskRecMinute(
          terminal,
          queue,
          minuteMillis(minute),
          passengers(queue)(minute).size,
          work(queue)(minute),
          desks(minute),
          waits(minute),
          Some(Math.round(sizes(minute)).toInt)
        )
      }
    }
  }

  def validateFallback(
      snapshot: BalancedDeskLimitsSnapshot,
      passengers: Map[Queue, IndexedSeq[Iterable[Double]]],
      slas: Map[Queue, Int],
      result: LegacyResults,
      control: CalculationControl = CalculationControl.Unlimited
  ): Unit = {
    val evaluation = evaluateLegacy(snapshot, passengers, slas, result, control)
    require(evaluation.valid, s"Invalid queue allocation fallback: ${evaluation.violations.mkString(",")}")
  }

  private def evaluateQueue(
      snapshot: BalancedDeskLimitsSnapshot,
      passengers: Option[IndexedSeq[Iterable[Double]]],
      sla: Option[Int],
      result: Option[(Iterable[Int], Iterable[Int], Iterable[Double])],
      queue: Queue,
      windowValid: Boolean,
      control: CalculationControl
  ): QueueAllocationEvaluation = {
    val length = snapshot.minuteMillis.size
    val loads = passengers.map(_.map(_.iterator.map { load =>
      control.check()
      load
    }.toIndexedSeq))
    val loadsValid = loads.exists(minutes =>
      minutes.size == length && minutes.forall(_.forall { load =>
        control.check()
        load.isFinite && load >= 0 && Math.round(load * 60) <= Int.MaxValue.toLong
      })
    )
    val output = result.map { case (desks, waits, queueSizes) =>
      (bounded(desks, length, control), bounded(waits, length, control), bounded(queueSizes, length, control))
    }
    val desks = output.map(_._1).filter(values => values.size == length && values.forall(_ >= 0))
    val original = snapshot.processorsByQueue.get(queue)
    val processorsValid = original.exists(provider =>
      provider.minutes == length && provider.processorsByMinute.forall { minute =>
        control.check()
        minute.processors.forall(_.openCount >= 0)
      }
    )
    val countsValid = desks.exists(values =>
      processorsValid && values.indices.forall { minute =>
        control.check()
        val maximum = original.get.processorsByMinute(minute).processors.size
        values(minute) <= maximum
      }
    )
    val capacity = desks.filter(_ => countsValid).map(_.indices.map { minute =>
      control.check()
      val count = desks.get(minute)
      val minutes =
        original.get.processorsByMinute(minute).processors.iterator.take(count).foldLeft(0L) { (total, processor) =>
          control.check()
          total + processor.openCount.toLong
        }
      minutes * 60L
    })
    val capacityValid = capacity.exists(_.forall(seconds => seconds >= 0 && seconds <= Int.MaxValue.toLong))
    val violations = List(
      Option.when(!windowValid)("invalid_window"),
      Option.when(passengers.isEmpty)("missing_passengers"),
      Option.when(passengers.isDefined && !loadsValid)("invalid_passenger_schedule"),
      Option.when(sla.isEmpty)("missing_sla"),
      Option.when(sla.exists(_ < 0))("invalid_sla"),
      Option.when(result.isEmpty)("missing_output"),
      Option.when(output.exists(_._1.size != length))("desks_length"),
      Option.when(output.exists(_._2.size != length))("waits_length"),
      Option.when(output.exists(_._3.size != length))("queue_sizes_length"),
      Option.when(output.exists(_._1.exists(_ < 0)))("negative_desks"),
      Option.when(output.exists(_._2.exists(_ < 0)))("negative_waits"),
      Option.when(output.exists(_._3.exists(size =>
        !size.isFinite || size < 0 || Math.round(size) > Int.MaxValue.toLong
      )))("invalid_queue_sizes"),
      Option.when(!processorsValid)("invalid_processor_schedule"),
      Option.when(desks.isDefined && processorsValid && !countsValid)("processor_count_out_of_range"),
      Option.when(capacity.isDefined && !capacityValid)("capacity_seconds_out_of_range")
    ).flatten
    val simulation = Option.when(violations.isEmpty) {
      val seconds = capacity.get.indices.map { minute =>
        control.check()
        val capacityMinutes = original.get.forMinute(minute).capacityForServers(desks.get(minute))
        List((capacityMinutes.toLong * 60L).toInt)
      }
      val incoming = loads.get.map(_.map { load =>
        control.check()
        QueuePassenger(Math.round(load * 60).toInt)
      }.toList)
      QueueProcessor.processQueueWithMetrics(seconds, incoming, sla.get, control)
    }
    QueueAllocationEvaluation(
      desks,
      loads.filter(_ => loadsValid).map(_.foldLeft(0L)((total, minute) => total + minute.size.toLong)),
      desks.map(_.foldLeft(0L)(_ + _.toLong)),
      simulation.map(_.remainingPassengers.size),
      simulation.map(_.passengerMinutesAboveSla),
      desks.map(values =>
        values.zip(values.drop(1)).foldLeft(0L) { case (total, (previous, next)) =>
          control.check()
          total + Math.abs(next.toLong - previous.toLong)
        }
      ),
      violations,
      simulation.map(_.waitTimes.foldLeft(0L) { (total, wait) =>
        control.check()
        if (wait >= sla.get) Math.addExact(total, 1L) else total
      }),
      simulation.map(_.queueSizes.foldLeft(0L) { (total, size) =>
        control.check()
        Math.addExact(total, size.toLong)
      })
    )
  }

  private def bounded[A](values: Iterable[A], length: Int, control: CalculationControl): IndexedSeq[A] =
    values.iterator.take(length + 1).map { value =>
      control.check()
      value
    }.toIndexedSeq

}
