package services

import org.specs2.mutable.Specification

import scala.collection.immutable
import scala.util.Try

class OptimiserPlusSpec extends Specification {
  "Given some 1 minutes of workload per minute, and desks fixed at 1 per minute" >> {
    "I should see all the workload completed each minute, leaving zero wait times" >> {
      val result: Try[OptimizerCrunchResult] = OptimiserPlus.crunch(Seq.fill(30)(1), Seq.fill(30)(1), Seq.fill(30)(1), OptimizerConfig(20))

      val expected = Seq.fill(30)(0)

      result.get.waitTimes === expected
    }
  }

  "Given some 2 minutes of workload per minute, and desks fixed at 1 per minute" >> {
    "I should see workload spilling over each minute, leaving increasing wait times" >> {
      val result: Try[OptimizerCrunchResult] = OptimiserPlus.crunch(Seq.fill(30)(2), Seq.fill(30)(1), Seq.fill(30)(1), OptimizerConfig(20))

      val expected = Seq(1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10 , 11, 11, 12, 12, 13, 13, 14, 14, 15, 15)

      result.get.waitTimes === expected
    }
  }

  "Given some 2 minutes of workload per minute, and desks fixed at 1 per minute" >> {
    "I should see workload spilling over each minute, leaving increasing wait times" >> {
      val result: Try[OptimizerCrunchResult] = OptimiserPlus.crunch((10d :: List.fill(59)(0d)), Seq.fill(60)(1), Seq.fill(60)(1), OptimizerConfig(20))


      val expected = OptimizerCrunchResult(immutable.IndexedSeq.fill(60)(1), Seq.fill(60)(0))

      result.get === expected
    }
  }
}

