package services.crunch.deskrecs

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import drt.shared.CrunchApi.{DeskRecMinute, MillisSinceEpoch}
import org.slf4j.{Logger, LoggerFactory}
import services._
import services.crunch.desklimits.TerminalDeskLimitsLike
import uk.gov.homeoffice.drt.ports.Queues.{Queue, QueueStatus}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal

import scala.collection.immutable.{Map, NumericRange}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

case class TerminalDesksAndWaitsProvider(slas: Map[Queue, Int], queuePriority: List[Queue], cruncher: TryCrunch) {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def workToDeskRecs(terminal: Terminal,
                     minuteMillis: NumericRange[MillisSinceEpoch],
                     terminalPax: Map[Queue, Seq[Double]],
                     terminalWork: Map[Queue, Seq[Double]],
                     deskLimitsProvider: TerminalDeskLimitsLike)
                    (implicit ec: ExecutionContext, mat: Materializer): Future[Iterable[DeskRecMinute]] = {
    desksAndWaits(minuteMillis, terminalWork, deskLimitsProvider).map { queueDesksAndWaits =>
      queueDesksAndWaits.flatMap {
        case (queue, (desks, waits)) =>
          minuteMillis.zip(terminalPax(queue).zip(terminalWork(queue))).zip(desks.zip(waits)).map {
            case ((minute, (pax, work)), (desk, wait)) => DeskRecMinute(terminal, queue, minute, pax, work, desk, wait)
          }
      }
    }
  }

  def desksAndWaits(minuteMillis: NumericRange[MillisSinceEpoch],
                    loadsByQueue: Map[Queue, Seq[Double]],
                    deskLimitsProvider: TerminalDeskLimitsLike)
                   (implicit ec: ExecutionContext, mat: Materializer): Future[Map[Queue, (Iterable[Int], Iterable[Int])]] = {
    val queuesToProcess = loadsByQueue.keys.toSet

    val queues = Source(queuePriority.filter(queuesToProcess.contains))

    queues
      .runFoldAsync(Map[Queue, (Iterable[Int], Iterable[Int])]()) {
        case (queueRecsSoFar, queue) =>
          log.debug(s"Optimising $queue")
          val queueWork = loadsByQueue(queue)
          val queueDeskAllocations = queueRecsSoFar.mapValues { case (desks, _) => desks.toList }

          for {
            (minDesks, processorsProvider) <- deskLimitsProvider.deskLimitsForMinutes(minuteMillis, queue, queueDeskAllocations)
          } yield {
            queueWork match {
              case noWork if noWork.isEmpty || noWork.max == 0 =>
                log.info(s"No workload to crunch for $queue on ${SDate(minuteMillis.min).toISOString()}. Filling with min desks and zero wait times")
                queueRecsSoFar + (queue -> ((minDesks, List.fill(minDesks.size)(0))))
              case someWork =>
                val start = System.currentTimeMillis()
                val maxDesks = processorsProvider.maxProcessors(someWork.size)
                val optimisedDesks = cruncher(someWork, minDesks.toSeq, maxDesks, OptimiserConfig(slas(queue), processorsProvider)) match {
                  case Success(OptimizerCrunchResult(desks, waits)) =>
                    queueRecsSoFar + (queue -> ((desks.toList, waits.toList)))
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
}
