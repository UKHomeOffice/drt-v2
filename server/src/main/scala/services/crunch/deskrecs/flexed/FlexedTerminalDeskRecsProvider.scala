package services.crunch.deskrecs.flexed

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.Queues.Queue
import services.crunch.desklimits.TerminalDeskLimitsLike
import services.crunch.deskrecs.TerminalDeskRecsProviderLike
import services.{OptimizerConfig, OptimizerCrunchResult, TryCrunch}

import scala.collection.immutable.{Map, NumericRange}
import scala.util.{Failure, Success}

case class FlexedTerminalDeskRecsProvider(slas: Map[Queue, Int],
                                          flexedQueuesPriority: List[Queue],
                                          cruncher: TryCrunch,
                                          bankSize: Int) extends TerminalDeskRecsProviderLike {
  override def desksAndWaits(minuteMillis: NumericRange[MillisSinceEpoch],
                             loads: Map[Queue, Seq[Double]],
                             minMaxDeskProvider: TerminalDeskLimitsLike): Map[Queue, (List[Int], List[Int])] = {
    val queuesToOptimise: Set[Queue] = loads.keys.toSet
    val flexedQueuesToOptimise = queuesToOptimise.filter(q => flexedQueuesPriority.contains(q))
    val staticQueuesToOptimise = queuesToOptimise.filter(q => !flexedQueuesPriority.contains(q))

    val flexedQueueLoads = loads.filterKeys(flexedQueuesToOptimise.contains)
    val flexedRecs = flexedDesksAndWaits(minuteMillis, flexedQueueLoads, minMaxDeskProvider)

    val staticQueueLoads = loads.filterKeys(staticQueuesToOptimise.contains)
    val staticRecs = staticDesksAndWaits(minuteMillis, staticQueueLoads, flexedRecs, minMaxDeskProvider)

    flexedRecs ++ staticRecs
  }

  def flexedDesksAndWaits(minuteMillis: NumericRange[MillisSinceEpoch],
                          flexedLoads: Map[Queue, Seq[Double]],
                          desksProvider: TerminalDeskLimitsLike): Map[Queue, (List[Int], List[Int])] = {
    val queuesToProcess = flexedLoads.keys.toSet

    flexedQueuesPriority
      .filter(queuesToProcess.contains)
      .foldLeft(Map[Queue, (List[Int], List[Int])]()) {
        case (queueRecsSoFar, queue) =>
          log.info(s"Flexed optimising $queue")
          val queueWork = adjustedWork(queue, flexedLoads(queue))
          val minDesks = desksProvider.minDesksForMinutes(minuteMillis, queue).toSeq
          val maxDesks = desksProvider.maxDesksForMinutes(minuteMillis, queue, queueRecsSoFar).toSeq
          flexedQueueDesksAndWaits(queue, queueWork, minDesks, maxDesks, queueRecsSoFar)
      }
  }

  def flexedQueueDesksAndWaits(queueProcessing: Queue,
                               loads: Seq[Double],
                               minDesks: Seq[Int],
                               maxStaff: Seq[Int],
                               existingAllocations: Map[Queue, (List[Int], List[Int])]): Map[Queue, (List[Int], List[Int])] = {
    val queueSlas = slas(queueProcessing)

    cruncher(loads, minDesks, maxStaff, OptimizerConfig(queueSlas)) match {
      case Success(OptimizerCrunchResult(desks, waits)) => existingAllocations + (queueProcessing -> ((desks.toList, waits.toList)))
      case Failure(t) =>
        log.error(s"Crunch failed for $queueProcessing", t)
        existingAllocations
    }
  }
}
