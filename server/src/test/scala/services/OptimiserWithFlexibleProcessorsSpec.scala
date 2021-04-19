package services

import org.specs2.mutable.Specification

import scala.util.Try


class OptimiserWithFlexibleProcessorsSpec extends Specification {
  val oneDesk: Seq[Int] = Seq.fill(30)(1)
  val oneBank: Seq[Int] = Seq.fill(30)(1)

  private val zeroWaitFor30Minutes: Seq[Int] = Seq.fill(30)(0)

  "Given 1 minutes incoming workload per minute, and desks fixed at 1 per minute" >> {
    "I should see all the workload completed each minute, leaving zero wait times" >> {
      val oneMinuteWorkloadFor30Minutes = Seq.fill(30)(1d)
      val result: Try[OptimizerCrunchResult] = OpOptimiserWithFlexibleProcessorsrunch(
        workloads = oneMinuteWorkloadFor30Minutes,
        minDesks = oneDesk,
        maxDesks = oneDesk,
        config = OptimiserConfig(20, DeskWorkloadProcessors))

      result.get.waitTimes === zeroWaitFor30Minutes
    }
  }

  "Given 2 minutes incoming workload per minute, and desks fixed at 1 per minute" >> {
    "I should see workload spilling over each minute, leaving increasing wait times" >> {
      val twoMinuteWorkloadFor30Minutes = Seq.fill(30)(2d)
      val result: Try[OptimizerCrunchResult] = OpOptimiserWithFlexibleProcessorsrunch(
        workloads = twoMinuteWorkloadFor30Minutes,
        minDesks = oneDesk,
        maxDesks = oneDesk,
        config = OptimiserConfig(20, DeskWorkloadProcessors))

      val increasingWaitTimes = Seq(1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13, 14, 14, 15, 15)

      result.get.waitTimes === increasingWaitTimes
    }
  }

  private val tenMinutesWorkloadFor30Minutes = List.fill(30)(10d)
  "Given 10 minutes incoming workload per minute, and egate banks of size 10 gates fixed at 1 bank per minute" >> {
    "I should see all the workload completed each minute, leaving zero wait times" >> {
      val bankSizes = Iterable(10)
      val result: Try[OptimizerCrunchResult] = OpOptimiserWithFlexibleProcessorsrunch(
        workloads = tenMinutesWorkloadFor30Minutes,
        minDesks = oneBank,
        maxDesks = oneBank,
        config = OptimiserConfig(20, EGateWorkloadProcessors(bankSizes)))

      result.get.waitTimes === zeroWaitFor30Minutes
    }
  }

  "Given 10 minutes incoming workload per minute, and egate banks of size 5 gates fixed at 1 bank per minute" >> {
    "I should see wait times creeping up by a minute every 2 minutes" >> {
      val bankSizes = Iterable(5)
      val result: Try[OptimizerCrunchResult] = OpOptimiserWithFlexibleProcessorsrunch(
        workloads = tenMinutesWorkloadFor30Minutes,
        minDesks = oneBank,
        maxDesks = oneBank,
        config = OptimiserConfig(20, EGateWorkloadProcessors(bankSizes)))

      val increasingWaitTimes = Seq(1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13, 14, 14, 15, 15)

      result.get.waitTimes === increasingWaitTimes
    }
  }

  "Given 20 minutes incoming workload per minute, and egate banks of size 15 gates fixed at 1 bank per minute" >> {
    "I should see wait times creeping up by a minute every 4 minutes" >> {
      val twentyMinutesWorkloadFor30Minutes = List.fill(30)(20d)
      val banksSizes = Iterable(15)
      val result: Try[OptimizerCrunchResult] = OpOptimiserWithFlexibleProcessorsrunch(
        workloads = twentyMinutesWorkloadFor30Minutes,
        minDesks = oneBank,
        maxDesks = oneBank,
        config = OptimiserConfig(20, EGateWorkloadProcessors(banksSizes)))

      val increasingWaitTimes = Seq(1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 6, 6, 6, 6, 7, 7, 7, 7, 8, 8)

      result.get.waitTimes === increasingWaitTimes
    }
  }

  private val oneBankFor15Minutes: Seq[Int] = Seq.fill(15)(1)
  private val twoBanksFor15Minutes: Seq[Int] = Seq.fill(15)(2)
  "Given 10 minutes incoming workload per minute, and egate banks of sizes 5 & 5 gates fixed at 1 bank for 15 mins followed by 2 banks for 15 mins" >> {
    "I should see wait times creeping up by a minute every 2 minutes for the first 15 minutes and then holding steady for the remaining time" >> {
      val bankSizes = Iterable(5, 5)
      val result: Try[OptimizerCrunchResult] = OpOptimiserWithFlexibleProcessorsrunch(
        tenMinutesWorkloadFor30Minutes,
        oneBankFor15Minutes ++ twoBanksFor15Minutes,
        oneBankFor15Minutes ++ twoBanksFor15Minutes,
        OptimiserConfig(20, EGateWorkloadProcessors(bankSizes)))

      val increasingWaitTimes = Seq(1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8)

      result.get.waitTimes === increasingWaitTimes
    }
  }

  "Given 10 minutes incoming workload per minute, and egate banks of sizes 5 & 10 gates fixed at 1 bank for 15 mins followed by 2 banks for 15 mins" >> {
    "I should see wait times creeping up by a minute every 2 minutes for the first 15 minutes and then falling for the remaining time" >> {
      val bankSizes = Iterable(5, 10)
      val result: Try[OptimizerCrunchResult] = OpOptimiserWithFlexibleProcessorsrunch(
        tenMinutesWorkloadFor30Minutes,
        oneBankFor15Minutes ++ twoBanksFor15Minutes,
        oneBankFor15Minutes ++ twoBanksFor15Minutes,
        OptimiserConfig(20, EGateWorkloadProcessors(bankSizes)))

      val increasingWaitTimes = Seq(1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 7, 7, 6, 6, 5, 5, 4, 4, 3, 3, 2, 2, 1, 1, 0)

      result.get.waitTimes === increasingWaitTimes
    }
  }

  "Given 10 minutes incoming workload per minute, and egate banks of sizes 5 & 10 gates with min 1 and max 3, and small SLA of 5 minutes" >> {
    "The optimiser should decide on 2 banks (9 gates) for 15 minutes followed by 3 banks (11 gates), with wait times slowly climbing and then slowly falling" >> {
      val bankSizes = Iterable(6, 3, 2)
      val threeDesksOrGates = Seq.fill(30)(3)
      val result: Try[OptimizerCrunchResult] = OpOptimiserWithFlexibleProcessorsrunch(
        tenMinutesWorkloadFor30Minutes,
        oneBank,
        threeDesksOrGates,
        OptimiserConfig(5, EGateWorkloadProcessors(bankSizes)))

      val expected = OptimizerCrunchResult(
        Vector(2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3),
        Seq(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0))

      result.get === expected
    }
  }

  private val oneGateFor60Minutes: IndexedSeq[Int] = IndexedSeq.fill(60)(1)
  "Given 60 minutes of 3.5 minutes work per minute" >> {
    "When comparing the original rollingFairXmax to the new one given banks of size 1" >> {
      "Both results should be the same, 3 desks" >> {
        val bankSizes = Iterable(1, 1, 1, 1, 1, 1, 1, 1)
        val workPerMinute = 3.5
        val workloadFor60Minutes = IndexedSeq.fill(60)(workPerMinute)
        val result: IndexedSeq[Int] = OpOptimiserWithFlexibleProcessorsollingFairXmax(workloadFor60Minutes, oneGateFor60Minutes, 5, 15, 60, 120, EGateWorkloadProcessors(bankSizes))
        val resultOrig: IndexedSeq[Int] = Optimiser.rollingFairXmax(workloadFor60Minutes, oneGateFor60Minutes, 5, 15, 60, 120)

        val threeBanksFor60Minutes = Seq.fill(60)(3)

        result === resultOrig && result === threeBanksFor60Minutes
      }
    }
  }

  "Given 60 minutes of 3.6 minutes work per minute" >> {
    "When comparing the original rollingFairXmax to the new one given banks of size 1" >> {
      "Both results should be the same, 4 desks" >> {
        val bankSizes = Iterable(1, 1, 1, 1, 1, 1, 1, 1)
        val workPerMinute = 3.6
        val workloadFor60Minutes = IndexedSeq.fill(60)(workPerMinute)
        val result: IndexedSeq[Int] = OpOptimiserWithFlexibleProcessorsollingFairXmax(workloadFor60Minutes, oneGateFor60Minutes, 5, 15, 60, 120, EGateWorkloadProcessors(bankSizes))
        val resultOrig: IndexedSeq[Int] = Optimiser.rollingFairXmax(workloadFor60Minutes, oneGateFor60Minutes, 5, 15, 60, 120)

        val fourBanksFor60Minutes = Seq.fill(60)(4)

        result === resultOrig && result === fourBanksFor60Minutes
      }
    }
  }

  "Given 60 minutes of 3 minutes work per minute, and bank sizes of 3, 5, 5" >> {
    "When asking for the rolling fair xmax" >> {
      "The result should be 1 bank, since 3 gates can clear 3 minutes of work per minute" >> {
        val bankSizes = Iterable(3, 5, 5)
        val workPerMinute = 3d
        val workloadFor60Minutes = IndexedSeq.fill(60)(workPerMinute)
        val result: IndexedSeq[Int] = OpOptimiserWithFlexibleProcessorsollingFairXmax(workloadFor60Minutes, oneGateFor60Minutes, 5, 15, 60, 120, EGateWorkloadProcessors(bankSizes))

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
        val result: IndexedSeq[Int] = OpOptimiserWithFlexibleProcessorsollingFairXmax(workloadFor60Minutes, oneGateFor60Minutes, 5, 15, 60, 120, EGateWorkloadProcessors(bankSizes))

        val twoBanksFor60Minutes = Seq.fill(60)(2)

        result === twoBanksFor60Minutes
      }
    }
  }
}

