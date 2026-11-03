package services

import org.specs2.mutable.Specification
import services.crunch.{ CalculationAborted, CalculationControl, CalculationControlTestSupport }
import services.workload.QueuePassenger
import uk.gov.homeoffice.drt.egates.{ Desk, EgateBank }

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._
import scala.util.{ Failure, Try }

class OptimiserWithFlexibleProcessorsSpec extends Specification {
  val oneDesk: Seq[Int] = Seq.fill(30)(1)
  val oneBank: Seq[Int] = Seq.fill(30)(1)

  private val zeroWaitFor30Minutes: Seq[Int] = Seq.fill(30)(0)

  private def randomPassengersForMinutes(minutes: Int): Seq[List[QueuePassenger]] = (0 until minutes).map(_ =>
    randomPassengersForMinute
  )

  private def randomPassengersForMinute: List[QueuePassenger] = (0 to (Math.random() * 20).toInt).map(_ =>
    QueuePassenger(randomProcessingSeconds)
  ).toList

  private def randomProcessingSeconds: Int = (Math.random() * 100).toInt + 40

  "Controlled optimisation" should {
    "preserve legacy setup exceptions while controlled requests return failures" in {
      val passengers = IndexedSeq.fill(60)(List(1d))
      val minimum = IndexedSeq.fill(60)(1)
      val maximum = IndexedSeq.fill(60)(2)
      val mismatched = OptimiserConfig(5, WorkloadProcessorsProvider(IndexedSeq.fill(59)(Seq(Desk))))
      val zeroSla = OptimiserConfig(0, WorkloadProcessorsProvider(IndexedSeq.fill(60)(Seq(Desk))))
      val legacyShape = Try(
        OptimiserWithFlexibleProcessors.crunchWholePax(false)(passengers, minimum, maximum, mismatched)
      )
      val controlledShape = OptimiserWithFlexibleProcessors.crunchWholePaxControlled(
        false,
        CalculationControl.Unlimited
      )(passengers, minimum, maximum, mismatched)
      val legacySla = Try(
        OptimiserWithFlexibleProcessors.crunchWholePax(true)(passengers, minimum, maximum, zeroSla)
      )
      val controlledSla = OptimiserWithFlexibleProcessors.crunchWholePaxControlled(
        true,
        CalculationControl.Unlimited
      )(passengers, minimum, maximum, zeroSla)
      (legacyShape.isFailure must beTrue) and
        (controlledShape.isFailure must beTrue) and
        (legacySla.isFailure must beTrue) and
        (controlledSla.isFailure must beTrue)
    }

    Seq(false, true).foreach { fair =>
      s"match the existing real optimiser with unlimited control and fair-Xmax=$fair" in {
        val passengers = IndexedSeq.tabulate(120) { minute =>
          List.fill(if (minute < 30) 4 else 2)(1d)
        }
        val minimum = IndexedSeq.fill(120)(1)
        val maximum = IndexedSeq.fill(120)(3)
        val config = OptimiserConfig(5, WorkloadProcessorsProvider(IndexedSeq.fill(120)(Seq.fill(3)(Desk))))
        val original = OptimiserWithFlexibleProcessors.crunchWholePax(fair)(passengers, minimum, maximum, config)
        val controlled = OptimiserWithFlexibleProcessors.crunchWholePaxControlled(fair, CalculationControl.Unlimited)(
          passengers,
          minimum,
          maximum,
          config
        )
        controlled.get mustEqual original.get
      }

      s"abort the optimiser's own final passenger simulation with fair-Xmax=$fair" in {
        val passengers = IndexedSeq.fill(60)(List.fill(50)(0.02d))
        val fixed = IndexedSeq.fill(60)(1)
        val config = OptimiserConfig(5, WorkloadProcessorsProvider(IndexedSeq.fill(60)(Seq(Desk))))
        val control = CalculationControlTestSupport.expiringIn("processQueue", 8L)
        val result = OptimiserWithFlexibleProcessors.crunchWholePaxControlled(fair, control)(
          passengers,
          fixed,
          fixed,
          config
        )
        result mustEqual Failure(CalculationAborted("expired"))
      }
    }

    "return a typed failure when input conversion exhausts the budget" in {
      val control = CalculationControlTestSupport.expiringIn("crunchWholePaxControlled", 3L)
      val result = OptimiserWithFlexibleProcessors.crunchWholePaxControlled(useFairXmax = false, control = control)(
        IndexedSeq.fill(30)(List.fill(100)(1d)),
        IndexedSeq.fill(30)(1),
        IndexedSeq.fill(30)(1),
        OptimiserConfig(5, WorkloadProcessorsProvider(IndexedSeq.fill(30)(Seq(Desk))))
      )
      result mustEqual Failure(CalculationAborted("expired"))
    }

    "reach cancellation inside fair-Xmax's actual leftward trial calculation" in {
      val work = IndexedSeq.tabulate(60)(minute => if (minute < 5) 40d else 0d)
      val control = CalculationControlTestSupport.expiringIn("leftwardDesks", 25L)
      val result = Try(OptimiserWithFlexibleProcessors.rollingFairXmax(
        work,
        IndexedSeq.fill(60)(0),
        5,
        4,
        60,
        120,
        WorkloadProcessorsProvider(IndexedSeq.fill(60)(Seq.fill(10)(Desk))),
        control
      ))
      result mustEqual Failure(CalculationAborted("expired"))
    }

    "propagate cancellation from search through tryOptimiseWin" in {
      val result = OptimiserWithFlexibleProcessors.tryOptimiseWin(
        IndexedSeq.fill(30)(2d),
        IndexedSeq.fill(30)(0),
        IndexedSeq.fill(30)(3),
        5,
        50,
        0.05,
        3,
        10,
        WorkloadProcessorsProvider(IndexedSeq.fill(30)(Seq.fill(3)(Desk))),
        CalculationControlTestSupport.expiringIn("branchBound", 30L)
      )
      result mustEqual Failure(CalculationAborted("expired"))
    }

    Seq(false, true).foreach { binary =>
      s"stop the actual candidate loop after cost cancellation with binary search=$binary" in {
        val control = CalculationControl.budget(1.minute)
        val evaluations = new AtomicInteger(0)
        val trialCost: IndexedSeq[Int] => Cost = _ => {
          if (evaluations.incrementAndGet() == 2) control.cancel("cancelled")
          Cost(0d, 0d, 0d, 0, 0d)
        }
        val result = Try {
          if (binary)
            OptimiserWithFlexibleProcessors.branchBoundBinarySearch(
              IndexedSeq(1),
              trialCost,
              IndexedSeq(0),
              IndexedSeq(3),
              30,
              control
            )
          else
            OptimiserWithFlexibleProcessors.branchBound(
              IndexedSeq(1),
              trialCost,
              IndexedSeq(0),
              IndexedSeq(3),
              30,
              control
            )
        }
        result mustEqual Failure(CalculationAborted("cancelled"))
      }
    }

    "abort the legacy simulator between minutes" in {
      val result = OptimiserWithFlexibleProcessors.legacyTryProcessWork(
        IndexedSeq.fill(100)(0d),
        IndexedSeq.fill(100)(1),
        5,
        IndexedSeq(0d),
        WorkloadProcessorsProvider(IndexedSeq.fill(100)(Seq(Desk))),
        CalculationControlTestSupport.expiringIn("legacyTryProcessWork", 8L)
      )
      result mustEqual Failure(CalculationAborted("expired"))
    }
  }

  "crunchWholePax" should {
    "return a result even when there are no available desks" in {
      val mins = 360
      val config = OptimiserConfig(60, WorkloadProcessorsProvider(List.fill(mins)(List.fill(0)(Desk))))
      val result: Try[OptimizerCrunchResult] = OptimiserWithFlexibleProcessors.crunchWholePax(useFairXmax = true)(
        randomPassengersForMinutes(mins).map(_.map(_.load.toDouble / 60)),
        List.fill(mins)(0),
        List.fill(mins)(0),
        config
      )

      result.get.recommendedDesks should ===(List.fill(mins)(0))
      result.get.waitTimes should ===(0 to 359)
    }
  }

  "Crunch with desk workload processors" >> {
    "Given 1 minutes incoming workload per minute, and desks fixed at 1 per minute" >> {
      "I should see all the workload completed each minute, leaving zero wait times" >> {
        val oneMinuteWorkloadFor30Minutes = Seq.fill(30)(Iterable(1d))
        val result: Try[OptimizerCrunchResult] = OptimiserWithFlexibleProcessors.crunchWholePax(useFairXmax = true)(
          passengerLoads = oneMinuteWorkloadFor30Minutes,
          minDesks = oneDesk,
          maxDesks = oneDesk,
          config =
            OptimiserConfig(20, WorkloadProcessorsProvider(IndexedSeq.fill(30)(WorkloadProcessors(Seq.fill(10)(Desk)))))
        )

        result.get.waitTimes === zeroWaitFor30Minutes
      }
    }

    "Given 2x 1 minute pax incoming workload per minute, and desks fixed at 1 per minute" >> {
      "I should see a minute wait for the passenger processed at minute 2" >> {
        val twoOneMinuteOnFirstMinutePax = Seq(Iterable(1d, 1d)) ++ Seq.fill(29)(Iterable())
        val result: Try[OptimizerCrunchResult] = OptimiserWithFlexibleProcessors.crunchWholePax(useFairXmax = true)(
          passengerLoads = twoOneMinuteOnFirstMinutePax,
          minDesks = oneDesk,
          maxDesks = oneDesk,
          config =
            OptimiserConfig(20, WorkloadProcessorsProvider(IndexedSeq.fill(30)(WorkloadProcessors(Seq.fill(10)(Desk)))))
        )

        result.get.waitTimes === Seq(0, 1) ++ Seq.fill(28)(0)
      }
    }

    "Given 2 minutes incoming workload per minute, and desks fixed at 1 per minute" >> {
      "I should see workload spilling over each minute, leaving increasing wait times" >> {
        val twoMinuteWorkloadFor30Minutes = Seq.fill(30)(Iterable(2d))
        val result: Try[OptimizerCrunchResult] = OptimiserWithFlexibleProcessors.crunchWholePax(useFairXmax = true)(
          passengerLoads = twoMinuteWorkloadFor30Minutes,
          minDesks = oneDesk,
          maxDesks = oneDesk,
          config =
            OptimiserConfig(20, WorkloadProcessorsProvider(IndexedSeq.fill(30)(WorkloadProcessors(Seq.fill(10)(Desk)))))
        )

        val increasingWaitTimes =
          Seq(0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13, 14, 14, 15)

        result.get.waitTimes === increasingWaitTimes
      }
    }
  }

  def egateProcessorsProvider(minutes: Int, bankSizes: Iterable[Int]): WorkloadProcessorsProvider =
    WorkloadProcessorsProvider(IndexedSeq.fill(minutes)(WorkloadProcessors(bankSizes.map(g =>
      EgateBank(IndexedSeq.fill(g)(true))
    ))))

  "Crunch with egate workload processors" >> {
    val tenMinutesWorkloadFor30Minutes = List.fill(30)(Iterable(10d))
    "Given 10 minutes incoming workload per minute, and egate banks of size 10 gates fixed at 1 bank per minute" >> {
      "I should see all the workload completed each minute, leaving zero wait times" >> {
        val bankSizes = Iterable(10)
        val result: Try[OptimizerCrunchResult] = OptimiserWithFlexibleProcessors.crunchWholePax(useFairXmax = true)(
          passengerLoads = tenMinutesWorkloadFor30Minutes,
          minDesks = oneBank,
          maxDesks = oneBank,
          config = OptimiserConfig(20, egateProcessorsProvider(tenMinutesWorkloadFor30Minutes.size, bankSizes))
        )

        result.get.waitTimes === zeroWaitFor30Minutes
      }
    }

    "Given 10 minutes incoming workload per minute, and egate banks of size 5 gates fixed at 1 bank per minute" >> {
      "I should see wait times creeping up by a minute every 2 minutes" >> {
        val bankSizes = Iterable(5)
        val result: Try[OptimizerCrunchResult] = OptimiserWithFlexibleProcessors.crunchWholePax(useFairXmax = true)(
          passengerLoads = tenMinutesWorkloadFor30Minutes,
          minDesks = oneBank,
          maxDesks = oneBank,
          config = OptimiserConfig(20, egateProcessorsProvider(tenMinutesWorkloadFor30Minutes.size, bankSizes))
        )

        val increasingWaitTimes =
          Seq(0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13, 14, 14, 15)

        result.get.waitTimes === increasingWaitTimes
      }
    }

    "Given 20 minutes incoming workload per minute, and egate banks of size 15 gates fixed at 1 bank per minute" >> {
      "I should see wait times creeping up by a minute every 4 minutes" >> {
        val twentyMinutesWorkloadFor30Minutes = List.fill(30)(Iterable(20d))
        val bankSizes = Iterable(15)
        val result: Try[OptimizerCrunchResult] = OptimiserWithFlexibleProcessors.crunchWholePax(useFairXmax = true)(
          passengerLoads = twentyMinutesWorkloadFor30Minutes,
          minDesks = oneBank,
          maxDesks = oneBank,
          config = OptimiserConfig(20, egateProcessorsProvider(twentyMinutesWorkloadFor30Minutes.size, bankSizes))
        )

        val increasingWaitTimes =
          Seq(0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 6, 6, 6, 6, 7, 7, 7, 7, 8)

        result.get.waitTimes === increasingWaitTimes
      }
    }

    val oneBankFor15Minutes: Seq[Int] = Seq.fill(15)(1)
    val twoBanksFor15Minutes: Seq[Int] = Seq.fill(15)(2)
    "Given 10 minutes incoming workload per minute, and egate banks of sizes 5 & 5 gates fixed at 1 bank for 15 mins followed by 2 banks for 15 mins" >> {
      "I should see wait times creeping up by a minute every 2 minutes for the first 15 minutes and then holding steady for the remaining time" >> {
        val bankSizes = Iterable(5, 5)
        val result: Try[OptimizerCrunchResult] = OptimiserWithFlexibleProcessors.crunchWholePax(useFairXmax = true)(
          tenMinutesWorkloadFor30Minutes,
          oneBankFor15Minutes ++ twoBanksFor15Minutes,
          oneBankFor15Minutes ++ twoBanksFor15Minutes,
          OptimiserConfig(20, egateProcessorsProvider(tenMinutesWorkloadFor30Minutes.size, bankSizes))
        )

        val increasingWaitTimes =
          Seq(0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8)

        result.get.waitTimes === increasingWaitTimes
      }
    }

    "Given 10 minutes incoming workload per minute, and egate banks of sizes 5 & 10 gates fixed at 1 bank for 15 mins followed by 2 banks for 15 mins" >> {
      "I should see wait times creeping up by a minute every 2 minutes for the first 15 minutes and then falling for the remaining time" >> {
        val bankSizes = Iterable(5, 10)
        val result: Try[OptimizerCrunchResult] = OptimiserWithFlexibleProcessors.crunchWholePax(useFairXmax = true)(
          tenMinutesWorkloadFor30Minutes,
          oneBankFor15Minutes ++ twoBanksFor15Minutes,
          oneBankFor15Minutes ++ twoBanksFor15Minutes,
          OptimiserConfig(20, egateProcessorsProvider(tenMinutesWorkloadFor30Minutes.size, bankSizes))
        )

        val increasingWaitTimes =
          Seq(0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 7, 7, 6, 6, 5, 5, 4, 4, 3, 3, 2, 2, 1, 1)

        result.get.waitTimes === increasingWaitTimes
      }
    }

    "Given 10 minutes incoming workload per minute, and egate banks of sizes 5 & 10 gates with min 1 and max 3, and small SLA of 5 minutes" >> {
      "The optimiser should decide on 2 banks (9 gates) for 15 minutes followed by 3 banks (11 gates), with wait times slowly climbing and then slowly falling" >> {
        val bankSizes = Iterable(6, 3, 2)
        val threeDesksOrGates = Seq.fill(30)(3)
        val result: Try[OptimizerCrunchResult] = OptimiserWithFlexibleProcessors.crunchWholePax(useFairXmax = true)(
          tenMinutesWorkloadFor30Minutes,
          oneBank,
          threeDesksOrGates,
          OptimiserConfig(5, egateProcessorsProvider(tenMinutesWorkloadFor30Minutes.size, bankSizes))
        )

        val expected = OptimizerCrunchResult(
          Vector(2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3),
          List(0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1),
          Vector(1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0, 1.0,
            1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 0.0)
        )

        result.get === expected
      }
    }
  }

  "rollingFairXmax with egate workload processors" >> {
    val oneGateFor60Minutes: IndexedSeq[Int] = IndexedSeq.fill(60)(1)

    "Given 60 minutes of 3.5 minutes work per minute" >> {
      "When comparing the original rollingFairXmax to the new one given banks of size 1" >> {
        "Both results should be the same, 3 desks" >> {
          val bankSizes = Iterable(1, 1, 1, 1, 1, 1, 1, 1)
          val workPerMinute = 3.5
          val workloadFor60Minutes = IndexedSeq.fill(60)(workPerMinute)
          val provider = egateProcessorsProvider(workloadFor60Minutes.size * 3, bankSizes)
          val result: IndexedSeq[Int] = OptimiserWithFlexibleProcessors.rollingFairXmax(
            workloadFor60Minutes,
            oneGateFor60Minutes,
            5,
            15,
            60,
            120,
            provider
          )

          val threeBanksFor60Minutes = Seq.fill(60)(3)

          result === threeBanksFor60Minutes
        }
      }
    }

    "Given 60 minutes of 3.6 minutes work per minute" >> {
      "When comparing the original rollingFairXmax to the new one given banks of size 1" >> {
        "Both results should be the same, 4 desks" >> {
          val bankSizes = Iterable(1, 1, 1, 1, 1, 1, 1, 1)
          val workPerMinute = 3.6
          val workloadFor60Minutes = IndexedSeq.fill(60)(workPerMinute)
          val provider = egateProcessorsProvider(workloadFor60Minutes.size * 3, bankSizes)
          val result: IndexedSeq[Int] = OptimiserWithFlexibleProcessors.rollingFairXmax(
            workloadFor60Minutes,
            oneGateFor60Minutes,
            5,
            15,
            60,
            120,
            provider
          )

          val fourBanksFor60Minutes = Seq.fill(60)(4)

          result === fourBanksFor60Minutes
        }
      }
    }

    "Given 60 minutes of 3 minutes work per minute, and bank sizes of 3, 5, 5" >> {
      "When asking for the rolling fair xmax" >> {
        "The result should be 1 bank, since 3 gates can clear 3 minutes of work per minute" >> {
          val bankSizes = Iterable(3, 5, 5)
          val workPerMinute = 3d
          val workloadFor60Minutes = IndexedSeq.fill(60)(workPerMinute)
          val provider = egateProcessorsProvider(workloadFor60Minutes.size * 3, bankSizes)
          val result: IndexedSeq[Int] = OptimiserWithFlexibleProcessors.rollingFairXmax(
            workloadFor60Minutes,
            oneGateFor60Minutes,
            5,
            15,
            60,
            120,
            provider
          )

          val oneBankFor60Minutes = Seq.fill(60)(1)

          result === oneBankFor60Minutes
        }
      }
    }

    "Given 60 minutes of 6 minutes work per minute, and bank sizes of 3, 5, 5" >> {
      "When asking for the rolling fair xmax" >> {
        "The result should be 2 banks, since 3 gates is insufficient, but 8 (3 + 5) would be enough" >> {
          val bankSizes = Iterable(3, 5, 5)
          val workPerMinute = 6d
          val workloadFor60Minutes = IndexedSeq.fill(60)(workPerMinute)
          val provider = egateProcessorsProvider(workloadFor60Minutes.size * 3, bankSizes)
          val result: IndexedSeq[Int] = OptimiserWithFlexibleProcessors.rollingFairXmax(
            workloadFor60Minutes,
            oneGateFor60Minutes,
            5,
            15,
            60,
            120,
            provider
          )

          val twoBanksFor60Minutes = Seq.fill(60)(2)

          result === twoBanksFor60Minutes
        }
      }
    }
  }

  "churn penalty" >> {
    "Given a fluctuating number of desks" >> {
      val churnStart = 1
      val desks = IndexedSeq(5, 10, 5, 15, 12, 14, 9, 5, 3, 7, 3, 9, 2, 10, 10, 14, 11, 16, 50, 25, 15, 10)
      "When I calculate the churn" >> {
        val churn = OptimiserWithFlexibleProcessors.totalDesksOpeningFromClosed(churnStart, desks)
        val expected = 82
        "It should be the sum of the number of desks that had to open from closed across the period" >> {
          churn === expected
        }
      }
    }
  }

  "Processing work" >> {
    "Given a constant workload of 5, 1 max processor for the first 30 mins, followed by 5" >> {
      val passengers = IndexedSeq.fill(120)(Iterable(5d))
      val minDesks = IndexedSeq.fill(120)(0)
      val maxDesks = IndexedSeq.fill(120)(5)
      "When I ask for the recommended desks" >> {
        val provider1then10 = WorkloadProcessorsProvider(IndexedSeq.fill(30)(WorkloadProcessors(Seq.fill(1)(Desk))) ++
          IndexedSeq.fill(120 - 30)(WorkloadProcessors(Seq.fill(10)(Desk))))
        val processed = OptimiserWithFlexibleProcessors.crunchWholePax(useFairXmax =
          true
        )(passengers, minDesks, maxDesks, OptimiserConfig(25, provider1then10))
        "I should see 1 for the first 30 minutes, followed by 5 for the last 90 (to keep the wait time from creeping up)" >> {
          processed.get.recommendedDesks === IndexedSeq.fill(30)(1) ++ IndexedSeq.fill(90)(5)
        }
      }
    }
  }

  "fairXmax" >> {
    val minDesks = IndexedSeq.fill(120)(0)
    val maxDesks = IndexedSeq.fill(60)(5) ++ IndexedSeq.fill(60)(0)
    val passengers = IndexedSeq.fill(45)(Iterable(5d)) ++ IndexedSeq.fill(75)(Iterable.empty)
    "When I ask for the recommended desks using fair xmax" >> {
      val provider1then10 = WorkloadProcessorsProvider(IndexedSeq.fill(120)(WorkloadProcessors(Seq.fill(10)(Desk))))
      val processed = OptimiserWithFlexibleProcessors.crunchWholePax(useFairXmax =
        true
      )(passengers, minDesks, maxDesks, OptimiserConfig(25, provider1then10))
      "I should see desks being tapered off before dropping to 0" >> {
        processed.get.recommendedDesks === IndexedSeq.fill(45)(4) ++ IndexedSeq.fill(15)(3) ++ IndexedSeq.fill(60)(0)
      }
    }
    "When I ask for the recommended desks without using fair xmax" >> {
      val provider1then10 = WorkloadProcessorsProvider(IndexedSeq.fill(120)(WorkloadProcessors(Seq.fill(10)(Desk))))
      val processed = OptimiserWithFlexibleProcessors.crunchWholePax(useFairXmax =
        false
      )(passengers, minDesks, maxDesks, OptimiserConfig(25, provider1then10))
      "I should see the full 5 desks being used until the full workload is processed" >> {
        processed.get.recommendedDesks === IndexedSeq.fill(45)(5) ++ IndexedSeq.fill(75)(0)
      }
    }
  }
}
