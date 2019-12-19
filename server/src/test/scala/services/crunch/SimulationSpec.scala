package services.crunch

import org.specs2.mutable.Specification
import services.{Optimiser, OptimizerConfig, TryRenjin}

object Memory {
  val runtime: Runtime = Runtime.getRuntime
  val mb: Int = 1024 * 1024

  def used: Long = runtime.totalMemory - runtime.freeMemory

  def printUsedSince(lastUsed: Long): Unit = println(s"${(used - lastUsed) / mb}MB")
}

class SimulationSpec extends Specification {
  val simService: (Seq[Double], Seq[Int], OptimizerConfig) => Seq[Int] = Optimiser.runSimulationOfWork
  val optimizerConfig = OptimizerConfig(25)

  def randomWorkload: Seq[Double] = 1 to 1440 map (_ => Math.random() * 25)
  def randomDesks: Seq[Int] = 1 to 1440 map (_ => (Math.random() * 30).toInt)
  def zeroDesks: Seq[Int] = 1 to 1440 map (_ => 0)

  "Given some a simulation of random loads and desks, and one of random load and zero desks " +
    "I want to see what the memory usage difference is" >> {
    skipped("exploratory")

    println(s"$randomWorkload")
    println(s"$randomDesks")

    memUsage(randomWorkload, randomDesks)
    memUsage(randomWorkload, zeroDesks)

    true
  }

  private def memUsage(workload: Seq[Double], desks: Seq[Int]): Unit = {
    val used = Memory.used

    simService(workload, desks, optimizerConfig)

    Memory.printUsedSince(used)
  }
}
