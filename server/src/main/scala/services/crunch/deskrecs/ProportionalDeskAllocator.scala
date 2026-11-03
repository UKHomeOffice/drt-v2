package services.crunch.deskrecs

import services.crunch.CalculationControl
import uk.gov.homeoffice.drt.ports.Queues.Queue

final case class DeskDemand(minimum: Int, required: Int)

object ProportionalDeskAllocator {

  /** Protect minimums, then share unmet demand across the staff budget and its nested manual-desk limit. */
  def allocateWithResources(
      absoluteMinute: Long,
      demands: Map[Queue, DeskDemand],
      processorCaps: Map[Queue, Int],
      manualQueues: Set[Queue],
      manualCapacity: Int,
      availableStaff: Option[Int],
      control: CalculationControl = CalculationControl.Unlimited
  ): Map[Queue, Int] = {
    control.check()
    require(manualCapacity >= 0, "Manual desk capacity must be non-negative")
    require(availableStaff.forall(_ >= 0), "Available staff must be non-negative")
    require(processorCaps.keySet == demands.keySet, "Every queue requires its own processor cap")
    require(manualQueues.subsetOf(demands.keySet), "Manual queues must have demands")
    require(checked(processorCaps.valuesIterator, control).forall(_ >= 0), "Processor caps must be non-negative")
    require(
      checked(demands.valuesIterator, control).forall(demand => demand.minimum >= 0 && demand.required >= 0),
      "Queue demands must be non-negative"
    )
    val minimums = checked(demands.iterator, control).map { case (queue, demand) =>
      queue -> math.min(demand.minimum, processorCaps(queue))
    }.toMap
    val targets = checked(demands.iterator, control).map { case (queue, demand) =>
      queue -> math.min(math.max(demand.minimum, demand.required), processorCaps(queue))
    }.toMap
    val budget = availableStaff.fold(checked(targets.valuesIterator, control).map(_.toLong).sum)(_.toLong)
    val protectedMinimums =
      apportionWithResources(absoluteMinute, minimums, budget, manualQueues, manualCapacity.toLong, control)
    val used = checked(protectedMinimums.valuesIterator, control).map(_.toLong).sum
    val manualUsed = checked(manualQueues.iterator, control).map(queue => protectedMinimums(queue).toLong).sum
    val unmet = checked(targets.iterator, control).map { case (queue, target) =>
      queue -> (target - protectedMinimums(queue))
    }.toMap
    val extras = apportionWithResources(
      absoluteMinute,
      unmet,
      budget - used,
      manualQueues,
      manualCapacity.toLong - manualUsed,
      control
    )
    checked(protectedMinimums.iterator, control).map { case (queue, minimum) =>
      queue -> Math.addExact(minimum, extras(queue))
    }.toMap
  }

  def allocate(
      absoluteMinute: Long,
      demands: Map[Queue, DeskDemand],
      capacity: Int,
      control: CalculationControl = CalculationControl.Unlimited
  ): Map[Queue, Int] = {
    control.check()
    require(capacity >= 0, "Capacity must be non-negative")
    require(checked(demands.valuesIterator, control).forall(_.minimum >= 0), "Minimum desk counts must be non-negative")
    require(
      checked(demands.valuesIterator, control).forall(_.required >= 0),
      "Required desk counts must be non-negative"
    )

    val minimums = checked(demands.iterator, control).map { case (queue, demand) => queue -> demand.minimum }.toMap
    val targets = checked(demands.iterator, control).map { case (queue, demand) =>
      queue -> math.max(demand.minimum, demand.required)
    }.toMap
    val totalMinimums = checked(minimums.valuesIterator, control).map(_.toLong).sum
    val totalTargets = checked(targets.valuesIterator, control).map(_.toLong).sum

    if (capacity == 0)
      checked(demands.keysIterator, control).map(_ -> 0).toMap
    else if (totalTargets <= capacity.toLong)
      targets
    else if (totalMinimums <= capacity.toLong) {
      val weights = checked(demands.iterator, control).map { case (queue, demand) =>
        queue -> (targets(queue) - demand.minimum)
      }.toMap
      val extras = apportion(absoluteMinute, weights, capacity.toLong - totalMinimums, control)
      checked(minimums.iterator, control).map { case (queue, minimum) =>
        queue -> Math.toIntExact(minimum.toLong + extras(queue).toLong)
      }.toMap
    } else
      apportion(absoluteMinute, minimums, capacity.toLong, control)
  }

  private def checked[A](values: Iterator[A], control: CalculationControl): Iterator[A] =
    if (control eq CalculationControl.Unlimited) values
    else values.map { value =>
      control.check()
      value
    }

  private case class Share(queue: Queue, weight: Int, desks: Long, remainder: Long)

  private case class ResourceShare(queue: Queue, desks: Long, remainder: BigInt)

  private def apportionWithResources(
      absoluteMinute: Long,
      weights: Map[Queue, Int],
      available: Long,
      manualQueues: Set[Queue],
      manualCapacity: Long,
      control: CalculationControl
  ): Map[Queue, Int] = {
    control.check()
    val totalWeight = checked(weights.valuesIterator, control).map(_.toLong).sum
    val manualWeight = checked(manualQueues.iterator, control).map(queue => weights(queue).toLong).sum
    val bankWeight = totalWeight - manualWeight
    val budget = math.min(available, math.min(manualCapacity, manualWeight) + bankWeight)
    if (budget == 0 || totalWeight == 0)
      checked(weights.keysIterator, control).map(_ -> 0).toMap
    else {
      // Saturate the manual group only if its original proportional share exceeds its physical cap.
      val manualCapped = BigInt(budget) * manualWeight > BigInt(manualCapacity) * totalWeight
      val denominator = if (manualCapped) BigInt(manualWeight) * bankWeight else BigInt(totalWeight)
      val shares = checked(weights.iterator, control).map { case (queue, weight) =>
        val numerator =
          if (!manualCapped) BigInt(budget) * weight
          else if (manualQueues.contains(queue)) BigInt(manualCapacity) * weight * bankWeight
          else BigInt(budget - manualCapacity) * weight * manualWeight
        ResourceShare(queue, (numerator / denominator).toLong, numerator % denominator)
      }.toSeq
      roundResourceShares(absoluteMinute, shares, budget, manualQueues, manualCapacity, control)
    }
  }

  private def roundResourceShares(
      absoluteMinute: Long,
      shares: Seq[ResourceShare],
      budget: Long,
      manualQueues: Set[Queue],
      manualCapacity: Long,
      control: CalculationControl
  ): Map[Queue, Int] = {
    val floors = checked(shares.iterator, control).map(share => share.queue -> Math.toIntExact(share.desks)).toMap
    val remaining = budget - checked(floors.valuesIterator, control).map(_.toLong).sum
    val manualUsed = checked(manualQueues.iterator, control).map(queue => floors(queue).toLong).sum
    val remainderOrder = checked(shares.iterator, control).filter(_.remainder > 0).toSeq
      .groupBy(_.remainder).toSeq.sortBy { case (remainder, _) =>
        control.check()
        -remainder
      }.flatMap { case (_, tiedShares) =>
        control.check()
        val canonical = tiedShares.sortBy { share =>
          control.check()
          share.queue.toString
        }
        val offset = Math.floorMod(absoluteMinute, canonical.size.toLong).toInt
        canonical.drop(offset) ++ canonical.take(offset)
      }
    val (allocations, unallocated, _) =
      checked(remainderOrder.iterator, control).foldLeft((floors, remaining, manualUsed)) {
        case ((current, left, used), share) =>
          val manual = manualQueues.contains(share.queue)
          if (left > 0 && (!manual || used < manualCapacity))
            (current.updated(share.queue, current(share.queue) + 1), left - 1L, used + (if (manual) 1L else 0L))
          else (current, left, used)
      }
    require(unallocated == 0, "Resource apportionment must exhaust its feasible budget")
    allocations
  }

  private def apportion(
      absoluteMinute: Long,
      weights: Map[Queue, Int],
      budget: Long,
      control: CalculationControl
  ): Map[Queue, Int] = {
    control.check()
    val totalWeight = checked(weights.valuesIterator, control).map(_.toLong).sum

    if (budget == 0 || totalWeight == 0)
      checked(weights.keysIterator, control).map(_ -> 0).toMap
    else {
      val shares = checked(weights.iterator, control).map { case (queue, weight) =>
        val product = budget * weight.toLong
        Share(queue, weight, product / totalWeight, product % totalWeight)
      }.toSeq
      val remaining = budget - checked(shares.iterator, control).map(_.desks).sum
      val remainderOrder = checked(shares.iterator, control)
        .filter(share => share.weight > 0 && share.desks < share.weight.toLong && share.remainder > 0)
        .toSeq
        .groupBy(_.remainder)
        .toSeq
        .sortBy { case (remainder, _) =>
          control.check()
          -remainder
        }
        .flatMap { case (_, tiedShares) =>
          control.check()
          val canonical = tiedShares.sortBy { share =>
            control.check()
            share.queue.toString
          }
          val offset = Math.floorMod(absoluteMinute, canonical.size.toLong).toInt
          canonical.drop(offset) ++ canonical.take(offset)
        }
      val remainderRecipients =
        checked(remainderOrder.iterator, control).take(Math.toIntExact(remaining)).map(_.queue).toSet

      checked(shares.iterator, control).map { share =>
        val extra = if (remainderRecipients.contains(share.queue)) 1L else 0L
        share.queue -> Math.toIntExact(share.desks + extra)
      }.toMap
    }
  }
}
