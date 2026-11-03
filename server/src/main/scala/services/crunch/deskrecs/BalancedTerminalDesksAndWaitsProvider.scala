package services.crunch.deskrecs

import services.crunch.CalculationControl
import services.crunch.desklimits.flexed.BalancedDeskLimitsSnapshot
import services.workload.{ QueuePassenger, QueueProcessingState, QueueProcessor }
import services.TryCrunchWholePax
import uk.gov.homeoffice.drt.ports.Queues.{ EGate, Queue }

case class BalancedQueueResult(
    desks: IndexedSeq[Int],
    waits: IndexedSeq[Int],
    queueSizes: IndexedSeq[Int],
    residualPassengers: Int,
    passengerMinutesAboveSla: Long,
    allocationChurn: Long
)

case class BalancedAllocationDiagnostics(
    replanCount: Int = 0,
    demandEvaluations: Long = 0L,
    searchLimitedPlans: Int = 0,
    minimumShortfallQueueMinutes: Long = 0L,
    minimumShortfallPositions: Long = 0L,
    usefulIdleCapacityMinutes: Long = 0L,
    maxPlanningHorizonMinutes: Int = 0,
    timingEvaluations: Long = 0L,
    timingAdjustedPlans: Int = 0,
    referenceDemandQueues: Int = 0,
    qualityEvaluations: Long = 0L,
    qualityAdjustedPlans: Int = 0,
    referenceShortfallQueueMinutes: Long = 0L
) {
  def telemetryFields: Map[String, String] = Map(
    "bank_limit_source" -> "date_effective_schedule",
    "replan_count" -> replanCount.toString,
    "demand_evaluations" -> demandEvaluations.toString,
    "search_limited_plans" -> searchLimitedPlans.toString,
    "minimum_shortfall_queue_minutes" -> minimumShortfallQueueMinutes.toString,
    "minimum_shortfall_positions" -> minimumShortfallPositions.toString,
    "useful_idle_capacity_minutes" -> usefulIdleCapacityMinutes.toString,
    "max_planning_horizon_minutes" -> maxPlanningHorizonMinutes.toString,
    "timing_evaluations" -> timingEvaluations.toString,
    "timing_adjusted_plans" -> timingAdjustedPlans.toString,
    "reference_demand_queues" -> referenceDemandQueues.toString,
    "quality_evaluations" -> qualityEvaluations.toString,
    "quality_adjusted_plans" -> qualityAdjustedPlans.toString,
    "reference_shortfall_queue_minutes" -> referenceShortfallQueueMinutes.toString
  )
}

case class BalancedTerminalResult(
    queues: Map[Queue, BalancedQueueResult],
    processorsUsedByMinute: IndexedSeq[Int],
    terminalDesks: Int,
    availableStaff: Option[IndexedSeq[Int]],
    diagnostics: BalancedAllocationDiagnostics = BalancedAllocationDiagnostics()
) {
  def passengerMinutesAboveSla: Long =
    queues.values.foldLeft(0L)((total, result) => Math.addExact(total, result.passengerMinutesAboveSla))

  def allocationChurn: Long =
    queues.values.foldLeft(0L)((total, result) => Math.addExact(total, result.allocationChurn))
}

object BalancedTerminalDesksAndWaitsProvider {

  /** Bounded callers supply a cruncher with the same control. */
  def calculate(
      snapshot: BalancedDeskLimitsSnapshot,
      passengers: Map[Queue, IndexedSeq[Iterable[Double]]],
      slas: Map[Queue, Int],
      cruncher: TryCrunchWholePax,
      control: CalculationControl = CalculationControl.Unlimited
  ): BalancedTerminalResult =
    calculateWithPlanner(snapshot, passengers, slas, new BacklogAwareQueueDemandPlanner(cruncher), cruncher, control)

  def calculateWithPlanner(
      snapshot: BalancedDeskLimitsSnapshot,
      passengers: Map[Queue, IndexedSeq[Iterable[Double]]],
      slas: Map[Queue, Int],
      planner: QueueDemandPlanner,
      referenceCruncher: TryCrunchWholePax,
      control: CalculationControl = CalculationControl.Unlimited
  ): BalancedTerminalResult = {
    control.check()
    validateInputs(snapshot, passengers, slas, control)
    val ordered = passengers.keys.toSeq.sortBy { queue =>
      control.check()
      queue.toString
    }
    if (ordered.isEmpty)
      return BalancedTerminalResult(
        Map.empty,
        IndexedSeq.fill(snapshot.minuteMillis.size)(0),
        snapshot.terminalDesks,
        snapshot.availableStaff
      )
    val manualQueues = passengers.keySet - EGate
    val incoming = ordered.map { queue =>
      control.check()
      queue -> passengers(queue).map { loads =>
        checked(loads, control).map(load => QueuePassenger(Math.round(load * 60).toInt)).toList
      }
    }.toMap
    val referencePlanner = new BacklogAwareQueueDemandPlanner(referenceCruncher)
    val referenceDemand = ordered.map { queue =>
      control.check()
      queue -> referencePlanner.initialReference(
        QueueDemandRequest(
          QueueProcessingState.Empty,
          passengers(queue),
          snapshot.minimums(queue),
          snapshot.processorsByQueue(queue),
          slas(queue),
          None,
          snapshot.minuteMillis.head / 60000L
        ),
        control
      )
    }.toMap
    val initial = RollingState(
      ordered.map(queue => queue -> RunningQueue(QueueProcessingState.Empty)).toMap,
      Vector.empty,
      BalancedAllocationDiagnostics(referenceDemandQueues = ordered.size)
    )
    val horizon = Math.max(90L, slas.valuesIterator.map(_.toLong).max + 15L)
    val futureLimits = BacklogDemandTiming.futureProcessorLimits(snapshot, control)
    val boundaries = decisionBoundaries(snapshot, ordered, control)
    val completed = boundaries.zip(boundaries.drop(1)).foldLeft(initial) {
      case (current, (start, end)) =>
        control.check()
        val previewLength = Math.min(horizon, snapshot.minuteMillis.size.toLong - start).toInt
        val plans = ordered.map { queue =>
          control.check()
          val running = current.queues(queue)
          require(running.processing.minute == start, "Queue state must match the common planning boundary")
          val plan = planner.plan(
            QueueDemandRequest(
              running.processing,
              passengers(queue).slice(start, start + previewLength),
              snapshot.minimums(queue).slice(start, start + previewLength),
              snapshot.processorsByQueue(queue).forWindow(start, start + previewLength),
              slas(queue),
              running.previousAllocation,
              snapshot.minuteMillis(start) / 60000L
            ),
            control
          )
          require(plan.required.size == previewLength, "Demand plan must match its lookahead window")
          require(plan.evaluations >= 0, "Demand evaluation count must be non-negative")
          plan.required.indices.foreach { offset =>
            control.check()
            val maximum = snapshot.processorsByQueue(queue).forMinute(start + offset).processors.size
            require(plan.required(offset) >= 0 && plan.required(offset) <= maximum, "Invalid independent demand")
          }
          val quality = BacklogDemandTiming.preserveReferenceQuality(
            running.processing,
            incoming(queue).slice(start, end),
            snapshot.processorsByQueue(queue).forWindow(start, end),
            referenceDemand(queue).slice(start, end),
            (start until end).map { minute =>
              control.check()
              Math.min(
                snapshot.minimums(queue)(minute),
                snapshot.processorsByQueue(queue).forMinute(minute).processors.size
              )
            },
            slas(queue),
            control
          )
          val timed = BacklogDemandTiming.preserveService(
            running.processing,
            incoming(queue).drop(start),
            snapshot.processorsByQueue(queue).forWindow(start, snapshot.minuteMillis.size),
            futureLimits(queue).drop(start),
            quality.required.indices.map { offset =>
              control.check()
              Math.max(quality.required(offset), plan.required(offset))
            },
            slas(queue),
            control
          )
          queue -> CommittedDemand(plan, quality, timed)
        }.toMap
        val diagnostics = current.diagnostics.copy(
          replanCount = Math.addExact(current.diagnostics.replanCount, 1),
          demandEvaluations = checked(plans.values, control).foldLeft(current.diagnostics.demandEvaluations)(
            (total, demand) => Math.addExact(total, demand.plan.evaluations.toLong)
          ),
          searchLimitedPlans = Math.addExact(
            current.diagnostics.searchLimitedPlans,
            checked(plans.values, control).count(_.plan.searchLimited)
          ),
          maxPlanningHorizonMinutes = Math.max(current.diagnostics.maxPlanningHorizonMinutes, previewLength),
          timingEvaluations = checked(plans.values, control).foldLeft(current.diagnostics.timingEvaluations)(
            (total, demand) => Math.addExact(total, demand.timed.evaluations.toLong)
          ),
          timingAdjustedPlans = Math.addExact(
            current.diagnostics.timingAdjustedPlans,
            checked(plans.values, control).count(_.timed.adjusted)
          ),
          qualityEvaluations = checked(plans.values, control).foldLeft(current.diagnostics.qualityEvaluations)(
            (total, demand) => Math.addExact(total, demand.quality.evaluations.toLong)
          ),
          qualityAdjustedPlans = Math.addExact(
            current.diagnostics.qualityAdjustedPlans,
            checked(plans.values, control).count { demand =>
              demand.quality.required.indices.exists { offset =>
                control.check()
                demand.quality.required(offset) > demand.plan.required(offset)
              }
            }
          )
        )
        (start until end).foldLeft(current.copy(diagnostics = diagnostics)) { (running, minute) =>
          advance(snapshot, ordered, manualQueues, incoming, slas, plans, start, minute, running, control)
        }
    }
    val results = ordered.map { queue =>
      control.check()
      val running = completed.queues(queue)
      require(running.processing.minute == snapshot.minuteMillis.size, "Incomplete final queue state")
      queue -> BalancedQueueResult(
        running.desks,
        running.waits,
        running.queueSizes,
        checked(running.processing.remainingPassengers, control).length,
        QueueProcessor.scoreAtHorizon(running.processing, slas(queue), control),
        running.churn
      )
    }.toMap
    control.check()
    BalancedTerminalResult(
      results,
      completed.used,
      snapshot.terminalDesks,
      snapshot.availableStaff,
      completed.diagnostics
    )
  }

  private case class RunningQueue(
      processing: QueueProcessingState,
      desks: Vector[Int] = Vector.empty,
      waits: Vector[Int] = Vector.empty,
      queueSizes: Vector[Int] = Vector.empty,
      previousAllocation: Option[Int] = None,
      churn: Long = 0L
  )

  private case class RollingState(
      queues: Map[Queue, RunningQueue],
      used: Vector[Int],
      diagnostics: BalancedAllocationDiagnostics
  )

  private case class CommittedDemand(plan: QueueDemandPlan, quality: TimedQueueDemand, timed: TimedQueueDemand)

  private def decisionBoundaries(
      snapshot: BalancedDeskLimitsSnapshot,
      queues: Seq[Queue],
      control: CalculationControl
  ): IndexedSeq[Int] =
    (Vector(0) ++ snapshot.minuteMillis.indices.drop(1).filter { minute =>
      control.check()
      Math.floorMod(snapshot.minuteMillis(minute) / 60000L, 15L) == 0L ||
      snapshot.availableStaff.exists(staff => staff(minute) != staff(minute - 1)) ||
      checked(queues, control).exists { queue =>
        snapshot.minimums(queue)(minute) != snapshot.minimums(queue)(minute - 1) ||
        snapshot.configuredMinimums(queue)(minute) != snapshot.configuredMinimums(queue)(minute - 1) ||
        snapshot.processorsByQueue(queue).forMinute(minute).cumulativeCapacity !=
          snapshot.processorsByQueue(queue).forMinute(minute - 1).cumulativeCapacity
      }
    }) :+ snapshot.minuteMillis.size

  private def advance(
      snapshot: BalancedDeskLimitsSnapshot,
      ordered: Seq[Queue],
      manualQueues: Set[Queue],
      incoming: Map[Queue, IndexedSeq[List[QueuePassenger]]],
      slas: Map[Queue, Int],
      plans: Map[Queue, CommittedDemand],
      planStart: Int,
      minute: Int,
      current: RollingState,
      control: CalculationControl
  ): RollingState = {
    control.check()
    val references = ordered.map { queue =>
      queue -> DeskDemand(snapshot.minimums(queue)(minute), plans(queue).quality.required(minute - planStart))
    }.toMap
    val caps = ordered.map(queue =>
      queue -> snapshot.processorsByQueue(queue).forMinute(minute).processors.size
    ).toMap
    val service = ProportionalDeskAllocator.allocateWithResources(
      snapshot.minuteMillis(minute) / 60000L,
      references,
      caps,
      manualQueues,
      snapshot.terminalDesks,
      snapshot.availableStaff.map(_(minute)),
      control
    )
    val improvements = ordered.map { queue =>
      queue -> DeskDemand(service(queue), plans(queue).timed.required(minute - planStart))
    }.toMap
    val allocations = ProportionalDeskAllocator.allocateWithResources(
      snapshot.minuteMillis(minute) / 60000L,
      improvements,
      caps,
      manualQueues,
      snapshot.terminalDesks,
      snapshot.availableStaff.map(_(minute)),
      control
    )
    val manualUsed = checked(manualQueues, control).map(queue => allocations(queue).toLong).sum
    val used = checked(allocations.values, control).map(_.toLong).sum
    require(manualUsed <= snapshot.terminalDesks, "Balanced allocations exceed manual terminal capacity")
    require(snapshot.availableStaff.forall(staff => used <= staff(minute)), "Balanced allocations exceed staff")
    val queues = ordered.map { queue =>
      control.check()
      val previous = current.queues(queue)
      val count = allocations(queue)
      val seconds = snapshot.processorsByQueue(queue).forMinute(minute).capacityForServers(count).toLong * 60L
      require(seconds >= 0 && seconds <= Int.MaxValue, "Processor capacity must be representable in seconds")
      val result = QueueProcessor.advanceMinute(
        previous.processing,
        incoming(queue)(minute),
        seconds.toInt,
        slas(queue),
        control
      )
      val change = previous.previousAllocation.fold(0L)(old => Math.abs(count.toLong - old))
      queue -> RunningQueue(
        result.state,
        previous.desks :+ count,
        previous.waits :+ result.waitTime,
        previous.queueSizes :+ result.queueSize,
        Some(count),
        Math.addExact(previous.churn, change)
      )
    }.toMap
    val shortfalls = ordered.map(queue =>
      Math.max(0L, snapshot.minimums(queue)(minute).toLong - allocations(queue))
    )
    val hasUsefulIdle = checked(ordered, control).exists { queue =>
      val pending = checked(queues(queue).processing.remainingPassengers, control).exists(_.load > 0)
      val profile = snapshot.processorsByQueue(queue).forMinute(minute)
      val currentCount = allocations(queue)
      val staffRoom = snapshot.availableStaff.fold(caps(queue).toLong)(staff => staff(minute).toLong - used)
      val manualRoom =
        if (manualQueues.contains(queue)) snapshot.terminalDesks.toLong - manualUsed else caps(queue).toLong
      val extra = Math.min(caps(queue).toLong - currentCount, Math.min(staffRoom, manualRoom)).toInt
      pending && extra > 0 && checked(1 to extra, control).exists { addition =>
        profile.capacityForServers(currentCount + addition) > profile.capacityForServers(currentCount)
      }
    }
    val diagnostics = current.diagnostics.copy(
      minimumShortfallQueueMinutes = Math.addExact(
        current.diagnostics.minimumShortfallQueueMinutes,
        shortfalls.count(_ > 0).toLong
      ),
      minimumShortfallPositions = checked(shortfalls, control).foldLeft(current.diagnostics.minimumShortfallPositions)(
        (total, shortfall) => Math.addExact(total, shortfall)
      ),
      referenceShortfallQueueMinutes = Math.addExact(
        current.diagnostics.referenceShortfallQueueMinutes,
        checked(ordered, control).count(queue =>
          allocations(queue) < plans(queue).quality.required(minute - planStart)
        ).toLong
      ),
      usefulIdleCapacityMinutes = Math.addExact(
        current.diagnostics.usefulIdleCapacityMinutes,
        if (hasUsefulIdle) 1L else 0L
      )
    )
    RollingState(queues, current.used :+ Math.toIntExact(used), diagnostics)
  }

  private def validateInputs(
      snapshot: BalancedDeskLimitsSnapshot,
      passengers: Map[Queue, IndexedSeq[Iterable[Double]]],
      slas: Map[Queue, Int],
      control: CalculationControl
  ): Unit = {
    control.check()
    require(
      slas.keySet == passengers.keySet && checked(slas.values, control).forall(_ > 0),
      "Every queue requires a positive SLA"
    )
    require(snapshot.minuteMillis.nonEmpty, "Balanced calculation requires a non-empty minute range")
    require(
      checked(snapshot.minuteMillis, control).forall(m => Math.floorMod(m, 60000L) == 0) &&
        checked(snapshot.minuteMillis, control).zip(snapshot.minuteMillis.iterator.drop(1)).forall {
          case (previous, next) => next > previous && next - previous == 60000L
        },
      "Balanced calculation requires aligned, consecutive minutes"
    )
    require(snapshot.terminalDesks >= 0, "Terminal desk capacity must be non-negative")
    require(
      snapshot.minimums.keySet == passengers.keySet && snapshot.configuredMinimums.keySet == passengers.keySet &&
        snapshot.processorsByQueue.keySet == passengers.keySet,
      "Snapshot queues must match the supplied passengers"
    )
    val length = snapshot.minuteMillis.size
    require(
      checked(snapshot.minimums.values ++ snapshot.configuredMinimums.values ++ snapshot.availableStaff.toSeq, control)
        .forall(values => values.size == length && checked(values, control).forall(_ >= 0)),
      "Snapshot resource schedules must contain one non-negative value per minute"
    )
    require(
      checked(snapshot.processorsByQueue.values, control).forall(provider =>
        provider.minutes == length && checked(provider.processorsByMinute, control)
          .forall(processors => checked(processors.processors, control).forall(_.openCount >= 0))
      ),
      "Snapshot processor schedules must match the window and have non-negative capacity"
    )
    require(
      checked(passengers.values, control).forall(minutes =>
        minutes.size == length && checked(minutes, control).forall(loads =>
          checked(loads, control).forall(load =>
            load.isFinite && load >= 0 && Math.round(load * 60) <= Int.MaxValue.toLong
          )
        )
      ),
      "Passenger schedules must match the window and have finite, non-negative, representable loads"
    )
  }

  private def checked[A](values: Iterable[A], control: CalculationControl): Iterator[A] =
    if (control eq CalculationControl.Unlimited) values.iterator
    else values.iterator.map { value =>
      control.check()
      value
    }

}
