package services.graphstages

import drt.shared.CrunchApi.{DeskRecMinutes, MillisSinceEpoch}
import drt.shared.TQM
import drt.shared.Terminals.Terminal
import org.slf4j.{Logger, LoggerFactory}
import services.crunch.deskrecs.StaffProviders.MaxDesksProvider
import services.graphstages.Crunch.LoadMinute
import services.{OptimizerConfig, OptimizerCrunchResult}

import scala.collection.immutable
import scala.collection.immutable.{Map, NumericRange}
import scala.util.Try

object CrunchMocks {
  val log: Logger = LoggerFactory.getLogger(getClass)
  val oneDayMillis: Int = 60 * 60 * 24 * 1000

  def mockCrunch(wl: Seq[Double], minDesks: Seq[Int], maxDesks: Seq[Int], config: OptimizerConfig): Try[OptimizerCrunchResult] = {
    log.info(s"Using mock crunch! ${wl.size}")
    Try(OptimizerCrunchResult(minDesks.toIndexedSeq, Seq.fill(wl.length)(config.sla)))
  }

  def mockSimulator(workloads: Seq[Double], desks: Seq[Int], config: OptimizerConfig): Seq[Int] = {
    Seq.fill(workloads.length)(config.sla)
  }
}
