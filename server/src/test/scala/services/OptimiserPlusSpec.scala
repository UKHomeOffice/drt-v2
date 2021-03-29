package services

import org.specs2.mutable.Specification

import scala.collection.immutable
import scala.util.Try

class OptimiserPlusSpec extends Specification {
  "Given some 1 minutes of workload per minute, and desks fixed at 1 per minute" >> {
    "I should see all the workload completed each minute, leaving zero wait times" >> {
      val result: Try[OptimizerCrunchResult] = OptimiserPlus.crunch(Seq.fill(60)(1), Seq.fill(60)(1), Seq.fill(60)(1), OptimizerConfig(20))
      println(s"result: $result")

      val expected = OptimizerCrunchResult(immutable.IndexedSeq.fill(60)(1), Seq.fill(60)(0))

      result.get === expected
    }
  }

  "Given some 2 minutes of workload per minute, and desks fixed at 1 per minute" >> {
    "I should see workload spilling over each minute, leaving increasing wait times" >> {
      val result: Try[OptimizerCrunchResult] = OptimiserPlus.crunch(Seq.fill(60)(2), Seq.fill(60)(1), Seq.fill(60)(1), OptimizerConfig(20))
      println(s"result: $result")

      val expected = OptimizerCrunchResult(immutable.IndexedSeq.fill(60)(1), Seq.fill(60)(0))

      result.get === expected
    }
  }
}

