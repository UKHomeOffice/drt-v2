package services.crunch.deskrecs

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import drt.shared.CrunchApi.{DeskRecMinute, MillisSinceEpoch}
import org.slf4j.{Logger, LoggerFactory}
import services.{OptimiserConfig, OptimizerCrunchResult}
import services.crunch.desklimits.TerminalDeskLimitsLike
import services.TryCrunchWholePax
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{LocalDate, SDate}

import scala.collection.immutable.{Map, NumericRange}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

case class TerminalDesksAndWaitsProvider(terminal: Terminal,
                                         sla: (LocalDate, Queue) => Future[Int],
                                         queuePriority: List[Queue],
                                         cruncher: TryCrunchWholePax,
                                         description: String,
                                        ) {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def workToDeskRecs(terminal: Terminal,
                     minuteMillis: NumericRange[MillisSinceEpoch],
                     terminalPassengers: Map[Queue, IndexedSeq[Iterable[Double]]],
                     terminalWork: Map[Queue, Seq[Double]],
                     deskLimitsProvider: TerminalDeskLimitsLike)
                    (implicit ec: ExecutionContext, mat: Materializer): Future[Iterable[DeskRecMinute]] = {
    desksAndWaits(minuteMillis, terminalPassengers, deskLimitsProvider).map { queueDesksAndWaits =>
      queueDesksAndWaits.flatMap {
        case (queue, (desks, waits, paxInQueue)) =>
          minuteMillis.indices
            .map { idx =>
              val maybeDrm = for {
                minute <- minuteMillis.lift(idx)
                pax <- terminalPassengers(queue).lift(idx)
                work <- terminalWork(queue).lift(idx)
                desk <- desks.toIndexedSeq.lift(idx)
                wait <- waits.toIndexedSeq.lift(idx)
                queueSize <- paxInQueue.toIndexedSeq.lift(idx)
              } yield DeskRecMinute(terminal, queue, minute, pax.size, work, desk, wait, Option(Math.round(queueSize).toInt))

              (idx, maybeDrm)
            }
            .map {
              case (_, Some(drm)) => drm
              case (idx, None) => DeskRecMinute(terminal, queue, minuteMillis(idx), 0, 0, 0, 0, None)
            }
      }
    }
  }

  def desksAndWaits(minuteMillis: NumericRange[MillisSinceEpoch],
                    passengersByQueue: Map[Queue, Iterable[Iterable[Double]]],
                    deskLimitsProvider: TerminalDeskLimitsLike)
                   (implicit ec: ExecutionContext, mat: Materializer): Future[Map[Queue, (Iterable[Int], Iterable[Int], Iterable[Double])]] = {
    val queuesToProcess = passengersByQueue.keys.toSet

    val queues = Source(queuePriority.filter(queuesToProcess.contains))

    val localDate = SDate(minuteMillis.min).toLocalDate

    queues
      .mapAsync(1) { queue =>
        sla(localDate, queue).map(sla => (queue, sla))
      }
      .runFoldAsync(Map[Queue, (Iterable[Int], Iterable[Int], Iterable[Double])]()) {
        case (queueRecsSoFar, (queue, sla)) =>
          val identifier = s"$description $terminal $queue"
          log.info(s"Optimising $identifier with sla $sla minutes")
          val queuePassengers = passengersByQueue(queue)
          val queueDeskAllocations = queueRecsSoFar.view.mapValues { case (desks, _, _) => desks.toList }.toMap

          for {
            (minDesks, processorsProvider) <- deskLimitsProvider.deskLimitsForMinutes(minuteMillis, queue, queueDeskAllocations)
          } yield {
            queuePassengers match {
              case noWork if noWork.isEmpty || noWork.map(_.sum).sum == 0 =>
                log.info(s"No workload to crunch for $identifier on ${SDate(minuteMillis.min).toISOString}. Filling with min desks and zero wait times")
                queueRecsSoFar + (queue -> ((minDesks, List.fill(minDesks.size)(0), List.fill(minDesks.size)(0d))))
              case someWork =>
                val start = System.currentTimeMillis()
                val maxDesks = processorsProvider.maxProcessors(someWork.size)
                val nonZeroMaxDesksPct = maxDesks.count(_ > 0).toDouble / maxDesks.size
                val optimisedDesks = if (nonZeroMaxDesksPct > 0.5) {
                  cruncher(someWork, minDesks.toSeq, maxDesks, OptimiserConfig(sla, processorsProvider)) match {
                    case Success(OptimizerCrunchResult(desks, waits, paxInQueue)) =>
                      queueRecsSoFar + (queue -> ((desks.toList, waits.toList, paxInQueue)))
                    case Failure(t) =>
                      log.error(s"Crunch failed for $identifier", t)
                      queueRecsSoFar
                  }
                } else {
                  log.info(s"Skipping crunch for $identifier as only ${nonZeroMaxDesksPct * 100}% of max desks are non-zero")
                  queueRecsSoFar
                }

                log.info(s"$identifier crunch for ${SDate(minuteMillis.min).toISOString} took: ${System.currentTimeMillis() - start}ms")
                optimisedDesks
            }
          }

      }
  }
}
