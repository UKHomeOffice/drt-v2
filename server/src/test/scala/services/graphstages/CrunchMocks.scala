package services.graphstages

import org.slf4j.{Logger, LoggerFactory}
import services.{OptimiserConfig, OptimizerCrunchResult}

import scala.util.{Success, Try}

object CrunchMocks {
  val log: Logger = LoggerFactory.getLogger(getClass)
  val oneDayMillis: Int = 60 * 60 * 24 * 1000

  def mockCrunch(wl: Seq[Double], minDesks: Seq[Int], maxDesks: Seq[Int], config: OptimiserConfig): Try[OptimizerCrunchResult] = {
    log.info(s"Using mock crunch! ${wl.size}")
    Try(OptimizerCrunchResult(minDesks.toIndexedSeq, Seq.fill(wl.length)(config.sla), Vector.fill[Double](wl.length)(0)))
  }

  def mockCrunchWholePax(wl: Iterable[Iterable[Double]], minDesks: Seq[Int], maxDesks: Seq[Int], config: OptimiserConfig): Try[OptimizerCrunchResult] = {
    log.info(s"Using mock crunch! ${wl.size}")
    Try(OptimizerCrunchResult(minDesks.toIndexedSeq, Seq.fill(wl.size)(config.sla), Vector.fill[Double](wl.size)(0)))
  }

  def mockSimulator(workloads: Seq[Double], desks: Seq[Int], config: OptimiserConfig): Try[Seq[Int]] = {
    Success(Seq.fill(workloads.length)(config.sla))
  }
}
