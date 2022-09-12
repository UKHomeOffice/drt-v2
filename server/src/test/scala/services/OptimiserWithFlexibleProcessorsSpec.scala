package services

import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.egates.{Desk, EgateBank}

import scala.util.Try


class OptimiserWithFlexibleProcessorsSpec extends Specification {
  val oneDesk: Seq[Int] = Seq.fill(30)(1)
  val oneBank: Seq[Int] = Seq.fill(30)(1)

  private val zeroWaitFor30Minutes: Seq[Int] = Seq.fill(30)(0)

  "Crunch with desk workload processors" >> {
    "Given 1 minutes incoming workload per minute, and desks fixed at 1 per minute" >> {
      "I should see all the workload completed each minute, leaving zero wait times" >> {
        val oneMinuteWorkloadFor30Minutes = Seq.fill(30)(Iterable(1d))
        val result: Try[OptimizerCrunchResult] = OptimiserWithFlexibleProcessors.crunchWholePax(
          passengers = oneMinuteWorkloadFor30Minutes,
          minDesks = oneDesk,
          maxDesks = oneDesk,
          config = OptimiserConfig(20, WorkloadProcessorsProvider(IndexedSeq.fill(30)(WorkloadProcessors(Seq.fill(10)(Desk))))))

        result.get.waitTimes === zeroWaitFor30Minutes
      }
    }

    "Given 2x 1 minute pax incoming workload per minute, and desks fixed at 1 per minute" >> {
      "I should see a minute wait for the passenger processed at minute 2" >> {
        val twoOneMinuteOnFirstMinutePax = Seq(Iterable(1d, 1d)) ++ Seq.fill(29)(Iterable())
        val result: Try[OptimizerCrunchResult] = OptimiserWithFlexibleProcessors.crunchWholePax(
          passengers = twoOneMinuteOnFirstMinutePax,
          minDesks = oneDesk,
          maxDesks = oneDesk,
          config = OptimiserConfig(20, WorkloadProcessorsProvider(IndexedSeq.fill(30)(WorkloadProcessors(Seq.fill(10)(Desk))))))

        result.get.waitTimes === Seq(0, 1) ++ Seq.fill(28)(0)
      }
    }

    "Given 2 minutes incoming workload per minute, and desks fixed at 1 per minute" >> {
      "I should see workload spilling over each minute, leaving increasing wait times" >> {
        val twoMinuteWorkloadFor30Minutes = Seq.fill(30)(Iterable(2d))
        val result: Try[OptimizerCrunchResult] = OptimiserWithFlexibleProcessors.crunchWholePax(
          passengers = twoMinuteWorkloadFor30Minutes,
          minDesks = oneDesk,
          maxDesks = oneDesk,
          config = OptimiserConfig(20, WorkloadProcessorsProvider(IndexedSeq.fill(30)(WorkloadProcessors(Seq.fill(10)(Desk))))))

        val increasingWaitTimes = Seq(0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13, 14, 14, 15)

        result.get.waitTimes === increasingWaitTimes
      }
    }
  }

  def egateProcessorsProvider(minutes: Int, bankSizes: Iterable[Int]): WorkloadProcessorsProvider =
    WorkloadProcessorsProvider(IndexedSeq.fill(minutes)(WorkloadProcessors(bankSizes.map(g => EgateBank(IndexedSeq.fill(g)(true))))))

  "Crunch with egate workload processors" >> {
    val tenMinutesWorkloadFor30Minutes = List.fill(30)(Iterable(10d))
    "Given 10 minutes incoming workload per minute, and egate banks of size 10 gates fixed at 1 bank per minute" >> {
      "I should see all the workload completed each minute, leaving zero wait times" >> {
        val bankSizes = Iterable(10)
        val result: Try[OptimizerCrunchResult] = OptimiserWithFlexibleProcessors.crunchWholePax(
          passengers = tenMinutesWorkloadFor30Minutes,
          minDesks = oneBank,
          maxDesks = oneBank,
          config = OptimiserConfig(20, egateProcessorsProvider(tenMinutesWorkloadFor30Minutes.size, bankSizes)))

        result.get.waitTimes === zeroWaitFor30Minutes
      }
    }

    "Given 10 minutes incoming workload per minute, and egate banks of size 5 gates fixed at 1 bank per minute" >> {
      "I should see wait times creeping up by a minute every 2 minutes" >> {
        val bankSizes = Iterable(5)
        val result: Try[OptimizerCrunchResult] = OptimiserWithFlexibleProcessors.crunchWholePax(
          passengers = tenMinutesWorkloadFor30Minutes,
          minDesks = oneBank,
          maxDesks = oneBank,
          config = OptimiserConfig(20, egateProcessorsProvider(tenMinutesWorkloadFor30Minutes.size, bankSizes)))

        val increasingWaitTimes = Seq(0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13, 14, 14, 15)

        result.get.waitTimes === increasingWaitTimes
      }
    }

    "Given 20 minutes incoming workload per minute, and egate banks of size 15 gates fixed at 1 bank per minute" >> {
      "I should see wait times creeping up by a minute every 4 minutes" >> {
        val twentyMinutesWorkloadFor30Minutes = List.fill(30)(Iterable(20d))
        val bankSizes = Iterable(15)
        val result: Try[OptimizerCrunchResult] = OptimiserWithFlexibleProcessors.crunchWholePax(
          passengers = twentyMinutesWorkloadFor30Minutes,
          minDesks = oneBank,
          maxDesks = oneBank,
          config = OptimiserConfig(20, egateProcessorsProvider(twentyMinutesWorkloadFor30Minutes.size, bankSizes)))

        val increasingWaitTimes = Seq(0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 6, 6, 6, 6, 7, 7, 7, 7, 8)

        result.get.waitTimes === increasingWaitTimes
      }
    }

    val oneBankFor15Minutes: Seq[Int] = Seq.fill(15)(1)
    val twoBanksFor15Minutes: Seq[Int] = Seq.fill(15)(2)
    "Given 10 minutes incoming workload per minute, and egate banks of sizes 5 & 5 gates fixed at 1 bank for 15 mins followed by 2 banks for 15 mins" >> {
      "I should see wait times creeping up by a minute every 2 minutes for the first 15 minutes and then holding steady for the remaining time" >> {
        val bankSizes = Iterable(5, 5)
        val result: Try[OptimizerCrunchResult] = OptimiserWithFlexibleProcessors.crunchWholePax(
          tenMinutesWorkloadFor30Minutes,
          oneBankFor15Minutes ++ twoBanksFor15Minutes,
          oneBankFor15Minutes ++ twoBanksFor15Minutes,
          OptimiserConfig(20, egateProcessorsProvider(tenMinutesWorkloadFor30Minutes.size, bankSizes)))

        val increasingWaitTimes = Seq(0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8)

        result.get.waitTimes === increasingWaitTimes
      }
    }

    "Given 10 minutes incoming workload per minute, and egate banks of sizes 5 & 10 gates fixed at 1 bank for 15 mins followed by 2 banks for 15 mins" >> {
      "I should see wait times creeping up by a minute every 2 minutes for the first 15 minutes and then falling for the remaining time" >> {
        val bankSizes = Iterable(5, 10)
        val result: Try[OptimizerCrunchResult] = OptimiserWithFlexibleProcessors.crunchWholePax(
          tenMinutesWorkloadFor30Minutes,
          oneBankFor15Minutes ++ twoBanksFor15Minutes,
          oneBankFor15Minutes ++ twoBanksFor15Minutes,
          OptimiserConfig(20, egateProcessorsProvider(tenMinutesWorkloadFor30Minutes.size, bankSizes)))

        val increasingWaitTimes = Seq(0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 7, 7, 6, 6, 5, 5, 4, 4, 3, 3, 2, 2, 1, 1)

        result.get.waitTimes === increasingWaitTimes
      }
    }

    "Given 10 minutes incoming workload per minute, and egate banks of sizes 5 & 10 gates with min 1 and max 3, and small SLA of 5 minutes" >> {
      "The optimiser should decide on 2 banks (9 gates) for 15 minutes followed by 3 banks (11 gates), with wait times slowly climbing and then slowly falling" >> {
        val bankSizes = Iterable(6, 3, 2)
        val threeDesksOrGates = Seq.fill(30)(3)
        val result: Try[OptimizerCrunchResult] = OptimiserWithFlexibleProcessors.crunchWholePax(
          tenMinutesWorkloadFor30Minutes,
          oneBank,
          threeDesksOrGates,
          OptimiserConfig(5, egateProcessorsProvider(tenMinutesWorkloadFor30Minutes.size, bankSizes)))

        val expected = OptimizerCrunchResult(
          Vector(2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3),
          List(0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1),
          Vector(1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 0.0))

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
          val result: IndexedSeq[Int] = OptimiserWithFlexibleProcessors.rollingFairXmax(workloadFor60Minutes, oneGateFor60Minutes, 5, 15, 60, 120, provider)

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
          val result: IndexedSeq[Int] = OptimiserWithFlexibleProcessors.rollingFairXmax(workloadFor60Minutes, oneGateFor60Minutes, 5, 15, 60, 120, provider)

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
          val result: IndexedSeq[Int] = OptimiserWithFlexibleProcessors.rollingFairXmax(workloadFor60Minutes, oneGateFor60Minutes, 5, 15, 60, 120, provider)

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
          val result: IndexedSeq[Int] = OptimiserWithFlexibleProcessors.rollingFairXmax(workloadFor60Minutes, oneGateFor60Minutes, 5, 15, 60, 120, provider)

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
    "Given a workload and capacity containing some zeros" >> {
      val workload = IndexedSeq.fill(60)(5d)
      val capacity = IndexedSeq.fill(30)(5) ++ IndexedSeq.fill(30)(0)
      "When I ask for the ProcessedWork" >> {
        val processed = OptimiserWithFlexibleProcessors.tryProcessWork(workload, capacity, 25, IndexedSeq(), WorkloadProcessorsProvider(IndexedSeq.fill(60)(WorkloadProcessors(Seq.fill(10)(Desk)))))
        "I should not find any NaNs" >> {
          processed.get.util.exists(_.isNaN) === false
        }
      }
    }

    "Given a constant workload of 5, 1 max processor for the first 30 mins, followed by 5" >> {
      val passengers = IndexedSeq.fill(120)(Iterable(5d))
      val minDesks = IndexedSeq.fill(120)(0)
      val maxDesks = IndexedSeq.fill(120)(5)
      "When I ask for the recommended desks" >> {
        val provider1then10 = WorkloadProcessorsProvider(IndexedSeq.fill(30)(WorkloadProcessors(Seq.fill(1)(Desk))) ++ IndexedSeq.fill(120 - 30)(WorkloadProcessors(Seq.fill(10)(Desk))))
        val processed = OptimiserWithFlexibleProcessors.crunchWholePax(passengers, minDesks, maxDesks, OptimiserConfig(25, provider1then10))
        "I should see 1 for the first 30 minutes, followed by 5 for the last 90 (to keep the wait time from creeping up)" >> {
          processed.get.recommendedDesks === IndexedSeq.fill(30)(1) ++ IndexedSeq.fill(90)(5)
        }
      }
    }
  }
}
