package services.crunch.deskrecs

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import services.crunch.{ CalculationAborted, CalculationControl, CalculationControlTestSupport }
import uk.gov.homeoffice.drt.ports.Queues.{ EGate, EeaDesk, NonEeaDesk, Queue }

import scala.collection.immutable.ListMap
import scala.concurrent.duration._

class ProportionalDeskAllocatorSpec extends AnyWordSpec with Matchers {
  private val queues: Vector[Queue] = Vector(EGate, EeaDesk, NonEeaDesk).sortBy(_.toString)

  private def withResources(
      demands: Map[Queue, DeskDemand],
      manual: Int,
      staff: Option[Int],
      minute: Long = 0L,
      caps: Map[Queue, Int] = Map.empty
  ): Map[Queue, Int] =
    ProportionalDeskAllocator.allocateWithResources(
      minute,
      demands,
      if (caps.isEmpty) demands.map { case (queue, demand) => queue -> math.max(demand.minimum, demand.required) }
      else caps,
      demands.keySet - EGate,
      manual,
      staff
    )

  "allocateWithResources" should {
    "keep original demand weights when a manual group cap and shared staff both constrain allocation" in {
      val demands = Map[Queue, DeskDemand](EeaDesk -> DeskDemand(0, 10), EGate -> DeskDemand(0, 2))
      withResources(demands, 5, Some(6)) shouldBe Map(EeaDesk -> 5, EGate -> 1)
      withResources(demands, 5, Some(7)) shouldBe Map(EeaDesk -> 5, EGate -> 2)
      withResources(demands, 5, None) shouldBe Map(EeaDesk -> 5, EGate -> 2)
    }

    "allow nine manual desks and two banks when eleven staff are available" in {
      val demands = Map[Queue, DeskDemand](EeaDesk -> DeskDemand(0, 9), EGate -> DeskDemand(0, 2))
      withResources(demands, 9, Some(11)) shouldBe Map(EeaDesk -> 9, EGate -> 2)
      withResources(demands, 9, Some(8)) shouldBe Map(EeaDesk -> 7, EGate -> 1)
      withResources(demands, 9, None) shouldBe Map(EeaDesk -> 9, EGate -> 2)
    }

    "water fill manual queues using their original weights and release capped capacity to banks" in {
      val demands = Map[Queue, DeskDemand](
        EeaDesk -> DeskDemand(0, 8),
        NonEeaDesk -> DeskDemand(0, 4),
        EGate -> DeskDemand(0, 4)
      )
      withResources(demands, 5, Some(7)) shouldBe Map(EeaDesk -> 3, NonEeaDesk -> 2, EGate -> 2)
    }

    "protect feasible minimums before distributing proportional unmet demand across every queue" in {
      val demands = Map[Queue, DeskDemand](
        EeaDesk -> DeskDemand(2, 10),
        NonEeaDesk -> DeskDemand(1, 5),
        EGate -> DeskDemand(1, 4)
      )
      withResources(demands, 5, Some(7)) shouldBe Map(EeaDesk -> 3, NonEeaDesk -> 2, EGate -> 2)
    }

    "reduce infeasible minimums with the same nested group and global constraints" in {
      val demands = Map[Queue, DeskDemand](
        EeaDesk -> DeskDemand(8, 20),
        NonEeaDesk -> DeskDemand(4, 20),
        EGate -> DeskDemand(4, 20)
      )
      withResources(demands, 5, Some(7)) shouldBe Map(EeaDesk -> 3, NonEeaDesk -> 2, EGate -> 2)
    }

    "allow bank extras after reducing infeasible manual minimums instead of stranding available staff" in {
      val demands = Map[Queue, DeskDemand](EeaDesk -> DeskDemand(10, 10), EGate -> DeskDemand(0, 6))
      withResources(demands, 5, Some(8)) shouldBe Map(EeaDesk -> 5, EGate -> 3)
      withResources(demands, 0, Some(8)) shouldBe Map(EeaDesk -> 0, EGate -> 6)
    }

    "respect every queue's processor cap including minimums and bank counts" in {
      val demands = Map[Queue, DeskDemand](
        EeaDesk -> DeskDemand(5, 50),
        NonEeaDesk -> DeskDemand(0, 9),
        EGate -> DeskDemand(0, 4)
      )
      val caps = Map[Queue, Int](EeaDesk -> 2, NonEeaDesk -> 4, EGate -> 1)
      withResources(demands, 9, None, caps = caps) shouldBe Map(EeaDesk -> 2, NonEeaDesk -> 4, EGate -> 1)
    }

    "rotate exact tied manual remainders while keeping the capped group and bank shares intact" in {
      val manualQueues = Vector[Queue](EeaDesk, NonEeaDesk).sortBy(_.toString)
      val demands = queues.map(_ -> DeskDemand(0, 1)).toMap
      Seq(Long.MinValue, -1L, 0L, 1L, Long.MaxValue).foreach { minute =>
        val winner = manualQueues(Math.floorMod(minute, 2L).toInt)
        withResources(demands, 1, Some(2), minute) shouldBe
          Map(
            EeaDesk -> (if (winner == EeaDesk) 1 else 0),
            NonEeaDesk -> (if (winner == NonEeaDesk) 1 else 0),
            EGate -> 1
          )
      }
    }

    "remain invariant under every queue permutation with minimum shortages and resource remainders" in {
      val demandSets = Seq(
        Seq(DeskDemand(0, 4), DeskDemand(0, 8), DeskDemand(0, 4)),
        Seq(DeskDemand(4, 8), DeskDemand(8, 12), DeskDemand(4, 8)),
        Seq(DeskDemand(1, 4), DeskDemand(2, 10), DeskDemand(1, 5))
      )
      for {
        demandSet <- demandSets
        manual <- Seq(0, 1, 3, 5, 10)
        staff <- Seq(None, Some(0), Some(1), Some(6), Some(7))
        minute <- Seq(-1L, 0L, 1L)
      } {
        val entries = Vector[Queue](EGate, EeaDesk, NonEeaDesk).zip(demandSet)
        val expected = withResources(ListMap.from(entries), manual, staff, minute)
        entries.permutations.foreach { permutation =>
          withResources(ListMap.from(permutation), manual, staff, minute) shouldBe expected
        }
      }
    }

    "handle maximum integer capacities with exact arithmetic and work bounded by the queue count" in {
      val demands = queues.map(_ -> DeskDemand(0, Int.MaxValue)).toMap
      withResources(demands, Int.MaxValue, Some(Int.MaxValue)) shouldBe
        Map(queues(0) -> 715827883, queues(1) -> 715827882, queues(2) -> 715827882)
      val allocation = withResources(demands, Int.MaxValue, None)
      allocation(EGate) shouldBe Int.MaxValue
      allocation(EeaDesk).toLong + allocation(NonEeaDesk) shouldBe Int.MaxValue.toLong
      allocation.valuesIterator.map(_.toLong).sum shouldBe 2L * Int.MaxValue
    }

    "preserve the existing allocator's behavior when every queue uses the same shared resource" in {
      val demandSets = Seq(
        Seq(DeskDemand(0, 4), DeskDemand(0, 8), DeskDemand(0, 4)),
        Seq(DeskDemand(4, 8), DeskDemand(8, 12), DeskDemand(4, 8)),
        Seq(DeskDemand(1, 4), DeskDemand(2, 10), DeskDemand(1, 5))
      )
      for {
        demandSet <- demandSets
        capacity <- Seq(0, 1, 3, 5, 12, 24)
        minute <- Seq(Long.MinValue, -1L, 0L, 1L, Long.MaxValue)
      } {
        val demands = queues.zip(demandSet).toMap
        ProportionalDeskAllocator.allocateWithResources(
          minute,
          demands,
          demands.map { case (queue, demand) => queue -> math.max(demand.minimum, demand.required) },
          demands.keySet,
          capacity,
          Some(capacity)
        ) shouldBe ProportionalDeskAllocator.allocate(minute, demands, capacity)
      }
    }

    "satisfy resource and protected-minimum invariants for bounded exhaustive inputs" in {
      val possibleDemands = for {
        minimum <- 0 to 1
        required <- 0 to 2
      } yield DeskDemand(minimum, required)
      for {
        first <- possibleDemands
        second <- possibleDemands
        bank <- possibleDemands
        manual <- 0 to 3
        staff <- 0 to 6
      } {
        val demands = Map[Queue, DeskDemand](EeaDesk -> first, NonEeaDesk -> second, EGate -> bank)
        val targets = demands.map { case (queue, demand) => queue -> math.max(demand.minimum, demand.required) }
        val allocation = withResources(demands, manual, Some(staff))
        val maximum = math.min(staff, math.min(manual, targets(EeaDesk) + targets(NonEeaDesk)) + targets(EGate))
        allocation.values.sum shouldBe maximum
        allocation(EeaDesk) + allocation(NonEeaDesk) should be <= manual
        demands.foreach { case (queue, demand) =>
          allocation(queue) should be >= 0
          allocation(queue) should be <= targets(queue)
          if (demands.values.map(_.minimum).sum <= staff && first.minimum + second.minimum <= manual)
            allocation(queue) should be >= demand.minimum
        }
      }
    }

    "cooperatively cancel during resource apportionment" in {
      val demands = queues.map(_ -> DeskDemand(0, 5)).toMap
      val control = CalculationControlTestSupport.expiringIn("apportionWithResources", 5L)
      intercept[CalculationAborted] {
        ProportionalDeskAllocator.allocateWithResources(
          0L,
          demands,
          queues.map(_ -> 5).toMap,
          demands.keySet - EGate,
          4,
          Some(5),
          control
        )
      }.reason shouldBe "expired"
    }

    "return complete zero allocations and support an empty queue set" in {
      val demands = queues.map(_ -> DeskDemand(1, 5)).toMap
      withResources(demands, 0, Some(0)) shouldBe queues.map(_ -> 0).toMap
      withResources(Map.empty, Int.MaxValue, None) shouldBe Map.empty
      val control = CalculationControl.budget(1.minute)
      control.cancel("cancelled")
      intercept[CalculationAborted] {
        ProportionalDeskAllocator.allocateWithResources(0L, Map.empty, Map.empty, Set.empty, 0, None, control)
      }.reason shouldBe "cancelled"
    }

    "reject invalid resource limits, caps and demand sets" in {
      val demands = Map[Queue, DeskDemand](EeaDesk -> DeskDemand(0, 3))
      Seq(
        () => withResources(demands, -1, None),
        () => withResources(demands, 3, Some(-1)),
        () => withResources(demands, 3, None, caps = Map(EeaDesk -> -1)),
        () => withResources(demands, 3, None, caps = Map(EGate -> 3)),
        () => withResources(Map(EeaDesk -> DeskDemand(-1, 3)), 3, None)
      ).foreach(invalid => intercept[IllegalArgumentException](invalid()))
    }
  }

  "allocate" should {
    "preserve allocations with unlimited and active execution controls" in {
      val demands = queues.zipWithIndex.map { case (queue, index) => queue -> DeskDemand(index + 1, 8) }.toMap
      for {
        capacity <- Seq(0, 3, 6, 12, 24)
        minute <- Seq(Long.MinValue, -1L, 0L, 1L, Long.MaxValue)
        control <- Seq(CalculationControl.Unlimited, CalculationControl.budget(1.minute))
      } {
        ProportionalDeskAllocator.allocate(minute, demands, capacity, control) shouldBe
          ProportionalDeskAllocator.allocate(minute, demands, capacity)
      }
    }

    "abort inside proportional apportionment rather than just checking allocation entry" in {
      val demands = queues.map(_ -> DeskDemand(0, 5)).toMap
      val control = CalculationControlTestSupport.expiringIn("apportion", 5L)
      intercept[CalculationAborted](ProportionalDeskAllocator.allocate(0L, demands, 4, control)).reason shouldBe
        "expired"
    }

    "propagate cancellation even for an empty allocation" in {
      val control = CalculationControl.budget(1.minute)
      control.cancel("cancelled")
      intercept[CalculationAborted](ProportionalDeskAllocator.allocate(0L, Map.empty, 0, control)).reason shouldBe
        "cancelled"
    }

    "allocate proportional extras above protected minimums" in {
      val demands = Map[Queue, DeskDemand](EeaDesk -> DeskDemand(1, 5), NonEeaDesk -> DeskDemand(1, 3))

      ProportionalDeskAllocator.allocate(0L, demands, 5) shouldBe Map(EeaDesk -> 3, NonEeaDesk -> 2)
    }

    "scale infeasible minimums proportionally without granting extras" in {
      val demands = Map[Queue, DeskDemand](EeaDesk -> DeskDemand(4, 100), NonEeaDesk -> DeskDemand(2, 2))

      ProportionalDeskAllocator.allocate(0L, demands, 3) shouldBe Map(EeaDesk -> 2, NonEeaDesk -> 1)
    }

    "retain all targets when they fit exactly or leave surplus capacity" in {
      val demands = Map[Queue, DeskDemand](EeaDesk -> DeskDemand(1, 5), NonEeaDesk -> DeskDemand(1, 3))

      Seq(8, 9, Int.MaxValue).foreach { capacity =>
        ProportionalDeskAllocator.allocate(0L, demands, capacity) shouldBe Map(EeaDesk -> 5, NonEeaDesk -> 3)
      }
    }

    "retain an applicable minimum when there is no independent demand" in {
      val demands = Map[Queue, DeskDemand](EeaDesk -> DeskDemand(2, 0), NonEeaDesk -> DeskDemand(0, 0))

      ProportionalDeskAllocator.allocate(0L, demands, 5) shouldBe Map(EeaDesk -> 2, NonEeaDesk -> 0)
    }

    "protect a no-demand minimum before allocating another queue's extras" in {
      val demands = Map[Queue, DeskDemand](EeaDesk -> DeskDemand(2, 0), NonEeaDesk -> DeskDemand(0, 8))

      ProportionalDeskAllocator.allocate(0L, demands, 5) shouldBe Map(EeaDesk -> 2, NonEeaDesk -> 3)
    }

    "allocate only minimums when they consume all capacity" in {
      val demands = Map[Queue, DeskDemand](EeaDesk -> DeskDemand(2, 8), NonEeaDesk -> DeskDemand(1, 6))

      ProportionalDeskAllocator.allocate(0L, demands, 3) shouldBe Map(EeaDesk -> 2, NonEeaDesk -> 1)
    }

    "return every queue with zero allocation when capacity is zero" in {
      val demands = Map[Queue, DeskDemand](EeaDesk -> DeskDemand(2, 5), NonEeaDesk -> DeskDemand(0, 3))

      ProportionalDeskAllocator.allocate(0L, demands, 0) shouldBe Map(EeaDesk -> 0, NonEeaDesk -> 0)
    }

    "return no allocations for an empty queue set" in {
      Seq(0, 1, Int.MaxValue).foreach { capacity =>
        ProportionalDeskAllocator.allocate(0L, Map.empty, capacity) shouldBe Map.empty
      }
    }

    "leave capacity unused when all weights and targets are zero" in {
      val demands = queues.map(_ -> DeskDemand(0, 0)).toMap

      ProportionalDeskAllocator.allocate(0L, demands, 5) shouldBe queues.map(_ -> 0).toMap
    }

    "allocate a single queue up to its target and capacity" in {
      Seq(0, 1, 2, 4, 5, 8).foreach { capacity =>
        val demands = Map[Queue, DeskDemand](EeaDesk -> DeskDemand(2, 5))

        ProportionalDeskAllocator.allocate(0L, demands, capacity) shouldBe Map(EeaDesk -> math.min(capacity, 5))
      }
    }

    "award the largest exact remainder rather than the largest demand" in {
      val demands = Map[Queue, DeskDemand](
        queues(0) -> DeskDemand(0, 1),
        queues(1) -> DeskDemand(0, 2),
        queues(2) -> DeskDemand(0, 4)
      )
      val expected = Map(queues(0) -> 1, queues(1) -> 1, queues(2) -> 2)

      (0L to 6L).foreach { minute =>
        ProportionalDeskAllocator.allocate(minute, demands, 4) shouldBe expected
      }
    }

    "rotate equal remainders between two queues using the absolute minute" in {
      val tiedQueues = queues.take(2)
      val demands = tiedQueues.map(_ -> DeskDemand(0, 1)).toMap

      ProportionalDeskAllocator.allocate(0L, demands, 1) shouldBe Map(tiedQueues(0) -> 1, tiedQueues(1) -> 0)
      ProportionalDeskAllocator.allocate(1L, demands, 1) shouldBe Map(tiedQueues(0) -> 0, tiedQueues(1) -> 1)
      ProportionalDeskAllocator.allocate(2L, demands, 1) shouldBe Map(tiedQueues(0) -> 1, tiedQueues(1) -> 0)
    }

    "rotate multiple remainder units among three equal queues" in {
      val demands = queues.map(_ -> DeskDemand(0, 1)).toMap
      val allocations = (0L until 3L).map { minute =>
        val recipients = (queues.drop(minute.toInt) ++ queues.take(minute.toInt)).take(2).toSet
        val allocation = ProportionalDeskAllocator.allocate(minute, demands, 2)

        allocation shouldBe queues.map(queue => queue -> (if (recipients.contains(queue)) 1 else 0)).toMap
        allocation
      }

      queues.foreach(queue => allocations.map(_(queue)).sum shouldBe 2)
    }

    "rotate within each equal-remainder group rather than across all queues" in {
      val demands = Map[Queue, DeskDemand](
        queues(0) -> DeskDemand(0, 1),
        queues(1) -> DeskDemand(0, 1),
        queues(2) -> DeskDemand(0, 3)
      )

      (0L until 4L).foreach { minute =>
        val winner = queues(Math.floorMod(minute, 2L).toInt)
        val expected = queues.map(queue => queue -> (if (queue == winner || queue == queues(2)) 1 else 0)).toMap

        ProportionalDeskAllocator.allocate(minute, demands, 2) shouldBe expected
      }
    }

    "rotate tied minimum shortages as well as tied extras" in {
      val demands = queues.map(_ -> DeskDemand(1, 10)).toMap

      (0L until 3L).foreach { minute =>
        val winner = queues(minute.toInt)

        ProportionalDeskAllocator.allocate(minute, demands, 1) shouldBe
          queues.map(queue => queue -> (if (queue == winner) 1 else 0)).toMap
      }
    }

    "use floorMod for negative and extreme absolute minutes" in {
      val demands = queues.map(_ -> DeskDemand(0, 1)).toMap
      val minutes = Seq(Long.MinValue, Long.MinValue + 1L, -4L, -1L, 0L, 1L, Long.MaxValue)

      minutes.foreach { minute =>
        val winner = queues(Math.floorMod(minute, queues.size.toLong).toInt)

        ProportionalDeskAllocator.allocate(minute, demands, 1) shouldBe
          queues.map(queue => queue -> (if (queue == winner) 1 else 0)).toMap
      }
    }

    "produce the same allocations at overlapping absolute minutes in different windows" in {
      val demands = queues.map(_ -> DeskDemand(0, 1)).toMap
      val firstWindow = (1000L until 1006L).map { minute =>
        minute -> ProportionalDeskAllocator.allocate(minute, demands, 1)
      }.toMap
      val secondWindow = (1003L until 1009L).map { minute =>
        minute -> ProportionalDeskAllocator.allocate(minute, demands, 1)
      }.toMap

      (1003L until 1006L).foreach { minute =>
        firstWindow(minute) shouldBe secondWindow(minute)
      }
    }

    "remain invariant under all permutations of three queue inputs in every allocation branch" in {
      val demandSets = Seq(
        Seq(DeskDemand(0, 1), DeskDemand(0, 1), DeskDemand(0, 1)),
        Seq(DeskDemand(1, 5), DeskDemand(1, 3), DeskDemand(0, 2)),
        Seq(DeskDemand(4, 8), DeskDemand(2, 3), DeskDemand(2, 2)),
        Seq(DeskDemand(2, 0), DeskDemand(0, 0), DeskDemand(0, 4))
      )

      for {
        demandSet <- demandSets
        capacity <- Seq(0, 1, 3, 5, 8, 20)
        minute <- Seq(-1L, 0L, 1L, 2L, Long.MaxValue)
      } {
        val entries = queues.zip(demandSet)
        val expected = ProportionalDeskAllocator.allocate(minute, ListMap.from(entries), capacity)

        entries.permutations.foreach { permutation =>
          ProportionalDeskAllocator.allocate(minute, ListMap.from(permutation), capacity) shouldBe expected
        }
      }
    }

    "never award remainder units to zero-weight queues or above targets" in {
      val demands = Map[Queue, DeskDemand](
        queues(0) -> DeskDemand(0, 0),
        queues(1) -> DeskDemand(0, 1),
        queues(2) -> DeskDemand(0, 3)
      )

      for {
        capacity <- 0 to 8
        minute <- -3L to 3L
      } {
        val allocation = ProportionalDeskAllocator.allocate(minute, demands, capacity)

        allocation(queues(0)) shouldBe 0
        allocation(queues(1)) should be <= 1
        allocation(queues(2)) should be <= 3
        allocation.values.sum shouldBe math.min(capacity, 4)
      }
    }

    "reject a negative capacity even with no queues" in {
      val error = intercept[IllegalArgumentException] {
        ProportionalDeskAllocator.allocate(0L, Map.empty, -1)
      }

      error.getMessage shouldBe "requirement failed: Capacity must be non-negative"
    }

    "reject a negative minimum synchronously even when capacity is zero" in {
      val error = intercept[IllegalArgumentException] {
        ProportionalDeskAllocator.allocate(0L, Map(EeaDesk -> DeskDemand(-1, 0)), 0)
      }

      error.getMessage shouldBe "requirement failed: Minimum desk counts must be non-negative"
    }

    "reject a negative required count synchronously even when capacity is zero" in {
      val error = intercept[IllegalArgumentException] {
        ProportionalDeskAllocator.allocate(0L, Map(EeaDesk -> DeskDemand(0, -1)), 0)
      }

      error.getMessage shouldBe "requirement failed: Required desk counts must be non-negative"
    }

    "handle Int.MaxValue targets, sums, products and remainders without overflow" in {
      val demands = queues.map(_ -> DeskDemand(0, Int.MaxValue)).toMap
      val expected = Map(queues(0) -> 715827883, queues(1) -> 715827882, queues(2) -> 715827882)

      ProportionalDeskAllocator.allocate(0L, demands, Int.MaxValue) shouldBe expected
      ProportionalDeskAllocator.allocate(0L, Map(EeaDesk -> DeskDemand(0, Int.MaxValue)), Int.MaxValue) shouldBe
        Map(EeaDesk -> Int.MaxValue)
    }

    "handle Int.MaxValue minimums and minimum shortages without overflow" in {
      val demands = queues.take(2).map(_ -> DeskDemand(Int.MaxValue, Int.MaxValue)).toMap
      val expected = Map(queues(0) -> 1073741824, queues(1) -> 1073741823)

      ProportionalDeskAllocator.allocate(0L, demands, Int.MaxValue) shouldBe expected
    }

    "compare unequal large remainders exactly without floating-point rounding" in {
      val demands = Map[Queue, DeskDemand](
        queues(0) -> DeskDemand(0, Int.MaxValue),
        queues(1) -> DeskDemand(0, Int.MaxValue - 1)
      )

      ProportionalDeskAllocator.allocate(1L, demands, Int.MaxValue) shouldBe
        Map(queues(0) -> 1073741824, queues(1) -> 1073741823)
    }

    "add extras to large protected minimums without overflow or overgranting" in {
      val demands = Map[Queue, DeskDemand](
        queues(0) -> DeskDemand(Int.MaxValue - 1, Int.MaxValue),
        queues(1) -> DeskDemand(0, Int.MaxValue)
      )

      ProportionalDeskAllocator.allocate(0L, demands, Int.MaxValue) shouldBe
        Map(queues(0) -> (Int.MaxValue - 1), queues(1) -> 1)
    }

    "satisfy allocation and exact proportional rounding invariants for bounded exhaustive inputs" in {
      val possibleDemands = for {
        minimum <- 0 to 2
        required <- 0 to 2
      } yield DeskDemand(minimum, required)

      for {
        first <- possibleDemands
        second <- possibleDemands
        third <- possibleDemands
        capacity <- 0 to 7
        minute <- -1L to 1L
      } {
        val demands = queues.zip(Seq(first, second, third)).toMap
        val targets = demands.map { case (queue, demand) => queue -> math.max(demand.minimum, demand.required) }
        val totalMinimums = demands.valuesIterator.map(_.minimum.toLong).sum
        val totalTargets = targets.valuesIterator.map(_.toLong).sum

        withClue(s"demands=$demands, capacity=$capacity, minute=$minute: ") {
          val allocation = ProportionalDeskAllocator.allocate(minute, demands, capacity)

          allocation.keySet shouldBe demands.keySet
          allocation.valuesIterator.map(_.toLong).sum shouldBe math.min(capacity.toLong, totalTargets)

          queues.foreach { queue =>
            allocation(queue) should be >= 0
            allocation(queue) should be <= targets(queue)
            if (totalMinimums <= capacity.toLong)
              allocation(queue) should be >= demands(queue).minimum
            else
              allocation(queue) should be <= demands(queue).minimum
          }

          if (totalTargets <= capacity.toLong)
            allocation shouldBe targets
          else {
            val minimumsFeasible = totalMinimums <= capacity.toLong
            val budget = if (minimumsFeasible) capacity.toLong - totalMinimums else capacity.toLong
            val weights = demands.map { case (queue, demand) =>
              queue -> (if (minimumsFeasible) targets(queue) - demand.minimum else demand.minimum)
            }
            val totalWeight = weights.valuesIterator.map(_.toLong).sum

            queues.foreach { queue =>
              val baseline = if (minimumsFeasible) demands(queue).minimum else 0
              val product = budget * weights(queue).toLong
              val floor = product / totalWeight
              val ceiling = floor + (if (product % totalWeight == 0) 0L else 1L)
              val proportionalAllocation = allocation(queue).toLong - baseline.toLong

              proportionalAllocation should be >= floor
              proportionalAllocation should be <= ceiling
            }
          }
        }
      }
    }
  }
}
