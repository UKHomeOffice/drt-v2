package services.crunch.deskrecs

import drt.shared.CrunchApi.{DeskRecMinute, MillisSinceEpoch}
import drt.shared.Queues.{EGate, Queue}
import drt.shared.Terminals.Terminal
import org.slf4j.{Logger, LoggerFactory}
import services.crunch.desklimits.TerminalDeskLimitsLike
import services._

import scala.collection.immutable.{Map, NumericRange}
import scala.util.{Failure, Success}

case class TerminalDesksAndWaitsProvider(slas: Map[Queue, Int],
                                         queuePriority: List[Queue],
                                         cruncher: TryCrunch,
                                         bankSizes: Iterable[Int]) {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def workToDeskRecs(terminal: Terminal,
                     minuteMillis: NumericRange[MillisSinceEpoch],
                     terminalPax: Map[Queue, Seq[Double]],
                     terminalWork: Map[Queue, Seq[Double]],
                     deskLimitsProvider: TerminalDeskLimitsLike): Iterable[DeskRecMinute] = {
    val queueDesksAndWaits = desksAndWaits(minuteMillis, terminalWork, deskLimitsProvider)

    val minutes = queueDesksAndWaits.flatMap {
      case (queue, (desks, waits)) =>
        minuteMillis.zip(terminalPax(queue).zip(terminalWork(queue))).zip(desks.zip(waits)).map {
          case ((minute, (pax, work)), (desk, wait)) => DeskRecMinute(terminal, queue, minute, pax, work, desk, wait)
        }
    }
    minutes
  }

  def desksAndWaits(minuteMillis: NumericRange[MillisSinceEpoch],
                    loadsByQueue: Map[Queue, Seq[Double]],
                    deskLimitsProvider: TerminalDeskLimitsLike): Map[Queue, (Iterable[Int], Iterable[Int])] = {
    val queuesToProcess = loadsByQueue.keys.toSet

    queuePriority
      .filter(queuesToProcess.contains)
      .foldLeft(Map[Queue, (Iterable[Int], Iterable[Int])]()) {
        case (queueRecsSoFar, queue) =>
          log.debug(s"Optimising $queue")
          val queueWork = loadsByQueue(queue)
          val queueDeskAllocations = queueRecsSoFar.mapValues { case (desks, _) => desks.toList }

          val (minDesks, maxDesks) = deskLimitsProvider.deskLimitsForMinutes(minuteMillis, queue, queueDeskAllocations)

          queueWork match {
            case noWork if noWork.isEmpty || noWork.max == 0 =>
              log.info(s"No workload to crunch for $queue on ${SDate(minuteMillis.min).toISOString()}. Filling with min desks and zero wait times")
              queueRecsSoFar + (queue -> ((minDesks, List.fill(minDesks.size)(0))))
            case someWork =>
              val start = System.currentTimeMillis()
              val processors = if (queue == EGate) EGateWorkloadProcessors(bankSizes) else DeskWorkloadProcessors$
              val optimisedDesks = cruncher(someWork, minDesks.toSeq, maxDesks.toSeq, OptimiserPlusConfig(slas(queue), processors)) match {
                case Success(OptimizerCrunchResult(desks, waits)) => queueRecsSoFar + (queue -> ((desks.toList, waits.toList)))
                case Failure(t) =>
                  log.error(s"Crunch failed for $queue", t)
                  queueRecsSoFar
              }
              log.info(s"$queue crunch for ${SDate(minuteMillis.min).toISOString()} took: ${System.currentTimeMillis() - start}ms")
              optimisedDesks
          }
      }
  }
}
