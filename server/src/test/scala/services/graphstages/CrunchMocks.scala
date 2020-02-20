package services.graphstages

import org.slf4j.{Logger, LoggerFactory}
import services.{OptimizerConfig, OptimizerCrunchResult}

import scala.util.{Success, Try}

object CrunchMocks {
  val log: Logger = LoggerFactory.getLogger(getClass)
  val oneDayMillis: Int = 60 * 60 * 24 * 1000

  def mockCrunch(wl: Seq[Double], minDesks: Seq[Int], maxDesks: Seq[Int], config: OptimizerConfig): Try[OptimizerCrunchResult] = {
    log.info(s"Using mock crunch! ${wl.size}")
    Try(OptimizerCrunchResult(minDesks.toIndexedSeq, Seq.fill(wl.length)(config.sla)))
  }

  def mockSimulator(workloads: Seq[Double], desks: Seq[Int], config: OptimizerConfig): Try[Seq[Int]] = {
    Success(Seq.fill(workloads.length)(config.sla))
  }
}
