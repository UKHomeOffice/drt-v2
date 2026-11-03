package services.crunch.deskrecs

import services.crunch.CalculationControl
import services.crunch.CalculationControl.Unlimited
import services.workload.{ QueuePassenger, QueueProcessingState, QueueProcessor }
import services.{ OptimiserConfig, TryCrunchWholePax, WorkloadProcessorsProvider }

import scala.annotation.tailrec

trait QueueDemandPlanner {
  def plan(request: QueueDemandRequest, control: CalculationControl = Unlimited): QueueDemandPlan
}

final case class QueueDemandRequest(
    state: QueueProcessingState,
    passengers: IndexedSeq[Iterable[Double]],
    minimums: IndexedSeq[Int],
    processors: WorkloadProcessorsProvider,
    sla: Int,
    previousAllocation: Option[Int],
    absoluteStartMinute: Long
)

final case class QueueDemandCost(breachPaxMinutes: Long, staffMinutes: Long, churn: Long)

object QueueDemandCost {
  implicit val ordering: Ordering[QueueDemandCost] =
    Ordering.by(cost => (cost.breachPaxMinutes, cost.staffMinutes, cost.churn))
}

final case class QueueDemandPlan(
    required: IndexedSeq[Int],
    cost: QueueDemandCost,
    evaluations: Int,
    searchLimited: Boolean
)

/** Searches only this queue's own resources, evaluating at most 512 FIFO previews. */
final class BacklogAwareQueueDemandPlanner(cruncher: TryCrunchWholePax) extends QueueDemandPlanner {
  private val maximumEvaluations: Int = 512
  private val reductionPasses: Int = 4
  private val blockMinutes: Int = 15

  private case class DecisionBlock(start: Int, end: Int, minimum: Int, maximum: Int)
  private case class Prepared(
      loads: Vector[Vector[Double]],
      passengers: Vector[List[QueuePassenger]],
      minimums: Vector[Int],
      maximums: Vector[Int],
      blocks: Vector[DecisionBlock]
  )
  private case class Candidate(allocations: Vector[Int], cost: QueueDemandCost)
  private case class Search(best: Candidate, costs: Map[Vector[Int], QueueDemandCost]) {
    def evaluations: Int = costs.size
  }

  /** Established independent service demand, not the ordered legacy allocation or a shared-resource remainder. */
  def initialReference(
      request: QueueDemandRequest,
      control: CalculationControl = Unlimited
  ): IndexedSeq[Int] = {
    control.check()
    require(
      request.state == QueueProcessingState.Empty && request.previousAllocation.isEmpty,
      "A service reference requires the initial request state"
    )
    require(
      request.passengers.nonEmpty && request.sla > 0,
      "A service reference requires a non-empty window and positive SLA"
    )
    val prepared = prepare(request, control)
    if (
      !prepared.loads.exists { minute =>
        control.check()
        minute.exists { load => control.check(); load > 0 }
      } || !prepared.maximums.exists { count => control.check(); count > 0 }
    ) prepared.minimums
    else proposalMinutes(request, prepared, control)
  }

  override def plan(request: QueueDemandRequest, control: CalculationControl = Unlimited): QueueDemandPlan = {
    control.check()
    val prepared = prepare(request, control)
    val minimum = prepared.blocks.map { block =>
      control.check()
      block.minimum
    }
    val maximum = prepared.blocks.map { block =>
      control.check()
      block.maximum
    }
    val noPassengers = request.state.remainingPassengers.isEmpty && prepared.passengers.forall { passengers =>
      control.check()
      passengers.isEmpty
    }

    def score(allocations: Vector[Int]): QueueDemandCost = {
      control.check()
      val schedule = expand(allocations, prepared.blocks, control)
      val capacities = schedule.indices.map { minute =>
        control.check()
        List(Math.toIntExact(request.processors.forMinute(minute).capacityForServers(schedule(minute)).toLong * 60L))
      }
      val preview = QueueProcessor.processQueueFromState(
        capacities,
        prepared.passengers,
        request.sla,
        request.state,
        control
      )
      val (staff, churn, _) = schedule.foldLeft((0L, 0L, request.previousAllocation)) {
        case ((staffMinutes, changes, previous), count) =>
          control.check()
          (
            Math.addExact(staffMinutes, count.toLong),
            Math.addExact(changes, previous.fold(0L)(last => math.abs(count.toLong - last.toLong))),
            Some(count)
          )
      }
      QueueDemandCost(QueueProcessor.scoreAtHorizon(preview.state, request.sla, control), staff, churn)
    }

    if (noPassengers || sameProfile(minimum, maximum, control)) {
      val cost = score(minimum)
      control.check()
      QueueDemandPlan(expand(minimum, prepared.blocks, control), cost, evaluations = 1, searchLimited = false)
    } else {
      // More FIFO capacity never delays a completion, so the all-maximum cost is a lower bound.
      val maximumCost = score(maximum)
      val initial = Search(Candidate(maximum, maximumCost), Map(maximum -> maximumCost))

      def evaluate(allocations: Vector[Int], search: Search): Search = {
        control.check()
        if (search.costs.contains(allocations) || search.evaluations >= maximumEvaluations) search
        else {
          val cost = score(allocations)
          val candidate = Candidate(allocations, cost)
          val best = if (prefer(candidate, search.best, control)) candidate else search.best
          Search(best, search.costs.updated(allocations, cost))
        }
      }

      val warmStart = proposal(request, prepared, control)
      val seeded = evaluate(warmStart, evaluate(minimum, initial))
      val combinations = prepared.blocks.foldLeft(1L) { (total, block) =>
        control.check()
        math.min(maximumEvaluations.toLong + 1L, total * (block.maximum.toLong - block.minimum.toLong + 1L))
      }
      val exhaustive = combinations <= maximumEvaluations
      val result =
        if (exhaustive) {
          (0 until combinations.toInt).foldLeft(seeded) { (search, ordinal) =>
            control.check()
            val (_, allocations) = prepared.blocks.foldLeft((ordinal.toLong, Vector.empty[Int])) {
              case ((remaining, counts), block) =>
                control.check()
                val width = block.maximum.toLong - block.minimum.toLong + 1L
                (remaining / width, counts :+ (block.minimum.toLong + remaining % width).toInt)
            }
            evaluate(allocations, search)
          }
        } else {
          def reduce(blockIndex: Int, search: Search): Search = {
            control.check()
            val anchor = search.best.allocations

            @tailrec
            def bisect(low: Int, high: Int, current: Search): Search = {
              control.check()
              if (low >= high || current.evaluations >= maximumEvaluations) current
              else {
                val middle = low + (high - low) / 2
                val candidate = anchor.updated(blockIndex, middle)
                val evaluated = evaluate(candidate, current)
                if (evaluated.costs(candidate).breachPaxMinutes == maximumCost.breachPaxMinutes)
                  bisect(low, middle, evaluated)
                else bisect(middle + 1, high, evaluated)
              }
            }

            bisect(prepared.blocks(blockIndex).minimum, anchor(blockIndex), search)
          }

          (0 until reductionPasses).foldLeft(seeded) { (search, pass) =>
            control.check()
            val indices =
              if (pass % 2 == 0) prepared.blocks.indices
              else prepared.blocks.indices.reverse
            indices.foldLeft(search) { (current, blockIndex) =>
              control.check()
              reduce(blockIndex, current)
            }
          }
        }
      control.check()
      QueueDemandPlan(
        expand(result.best.allocations, prepared.blocks, control),
        result.best.cost,
        result.evaluations,
        searchLimited = !exhaustive
      )
    }
  }

  private def prepare(request: QueueDemandRequest, control: CalculationControl): Prepared = {
    control.check()
    val length = request.passengers.size
    require(request.minimums.size == length, "Minimums must match the passenger horizon")
    require(request.processors.minutes == length, "Processors must match the passenger horizon")
    require(request.sla >= 0, "SLA must be nonnegative")
    require(request.state.minute >= 0, "The queue minute must be nonnegative")
    require(request.state.minute.toLong + length.toLong <= Int.MaxValue, "Queue horizon must fit in an Int")
    require(request.state.completedPassengerMinutesAboveSla >= 0, "Completed breach must be nonnegative")
    require(request.previousAllocation.forall(_ >= 0), "Previous allocation must be nonnegative")
    request.state.remainingPassengers.foldLeft(0) { (previousJoinTime, passenger) =>
      control.check()
      require(passenger.load >= 0, "Remaining passenger work must be nonnegative")
      require(
        passenger.joinTime >= previousJoinTime && passenger.joinTime < request.state.minute,
        "Backlog must retain chronological join times preceding the next request-relative minute"
      )
      passenger.joinTime
    }
    val profiles = request.processors.processorsByMinute.map { minute =>
      control.check()
      val profile = minute.processors.iterator.map { processor =>
        control.check()
        require(processor.openCount >= 0, "Processor capacity must be nonnegative")
        processor.openCount
      }.toVector
      val total = profile.foldLeft(0L) { (sum, capacity) =>
        control.check()
        Math.addExact(sum, capacity.toLong)
      }
      require(total <= Int.MaxValue.toLong / 60L, "Minute capacity must be representable in seconds")
      profile
    }
    val maximums = profiles.map { profile =>
      control.check()
      profile.size
    }.toVector
    val minimums = request.minimums.indices.map { minute =>
      control.check()
      require(request.minimums(minute) >= 0, "Minimums must be nonnegative")
      math.min(request.minimums(minute), maximums(minute))
    }.toVector
    val loads = request.passengers.map { minute =>
      control.check()
      minute.iterator.map { load =>
        control.check()
        require(
          load.isFinite && load >= 0 && Math.round(load * 60L) <= Int.MaxValue.toLong,
          "Passenger loads must be finite, nonnegative minutes representable in seconds"
        )
        load
      }.toVector
    }.toVector
    val passengers = loads.map { minute =>
      control.check()
      minute.iterator.map { load =>
        control.check()
        QueuePassenger(Math.round(load * 60L).toInt)
      }.toList
    }
    val phase = Math.floorMod(request.absoluteStartMinute, blockMinutes.toLong)
    val starts = (0 until length).filter { minute =>
      control.check()
      minute == 0 ||
      (phase + minute.toLong) % blockMinutes == 0 ||
      request.minimums(minute) != request.minimums(minute - 1) ||
      !sameProfile(profiles(minute), profiles(minute - 1), control)
    }.toVector
    val blocks = starts.indices.map { index =>
      control.check()
      val start = starts(index)
      val end = if (index + 1 < starts.size) starts(index + 1) else length
      DecisionBlock(start, end, minimums(start), maximums(start))
    }.toVector
    Prepared(loads, passengers, minimums, maximums, blocks)
  }

  private def sameProfile(left: Vector[Int], right: Vector[Int], control: CalculationControl): Boolean =
    left.size == right.size && left.iterator.zip(right.iterator).forall { case (a, b) =>
      control.check()
      a == b
    }

  private def proposalMinutes(
      request: QueueDemandRequest,
      prepared: Prepared,
      control: CalculationControl
  ): Vector[Int] = {
    control.check()
    val padding = (blockMinutes - prepared.loads.size % blockMinutes) % blockMinutes
    val passengers = prepared.loads ++ Vector.fill(padding)(Vector.empty[Double])
    val minimums = prepared.minimums ++ Vector.fill(padding)(prepared.minimums.last)
    val maximums = prepared.maximums ++ Vector.fill(padding)(prepared.maximums.last)
    val processors = WorkloadProcessorsProvider(
      request.processors.processorsByMinute ++ Vector.fill(padding)(request.processors.processorsByMinute.last)
    )
    val result = cruncher(passengers, minimums, maximums, OptimiserConfig(request.sla, processors))
    control.check()
    val proposed = result.get.recommendedDesks
    require(proposed.size == passengers.size, "Independent optimiser demand must match its padded horizon")
    proposed.foreach { count =>
      control.check()
      require(count >= 0, "Independent optimiser demand must be nonnegative")
    }
    prepared.loads.indices.map { minute =>
      control.check()
      Math.min(prepared.maximums(minute), Math.max(prepared.minimums(minute), proposed(minute)))
    }.toVector
  }

  private def proposal(
      request: QueueDemandRequest,
      prepared: Prepared,
      control: CalculationControl
  ): Vector[Int] = {
    val proposed = proposalMinutes(request, prepared, control)
    prepared.blocks.map { block =>
      control.check()
      val count = (block.start until block.end).foldLeft(block.minimum) { (largest, minute) =>
        control.check()
        math.max(largest, proposed(minute))
      }
      math.min(count, block.maximum)
    }
  }

  private def expand(
      allocations: Vector[Int],
      blocks: Vector[DecisionBlock],
      control: CalculationControl
  ): Vector[Int] =
    blocks.iterator.zip(allocations.iterator).flatMap { case (block, count) =>
      control.check()
      (block.start until block.end).iterator.map { _ =>
        control.check()
        count
      }
    }.toVector

  private def prefer(candidate: Candidate, incumbent: Candidate, control: CalculationControl): Boolean = {
    control.check()
    val compared = QueueDemandCost.ordering.compare(candidate.cost, incumbent.cost)
    if (compared != 0) compared < 0
    else {
      // Equal-cost plans should not defer useful capacity into an uncommitted future interval.
      candidate.allocations.iterator.zip(incumbent.allocations.iterator).find { case (a, b) =>
        control.check()
        a != b
      }.exists { case (a, b) => a > b }
    }
  }
}
