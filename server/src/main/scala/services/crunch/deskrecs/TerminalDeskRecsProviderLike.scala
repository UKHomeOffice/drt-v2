package services.crunch.deskrecs

import drt.shared.CrunchApi.{DeskRecMinute, MillisSinceEpoch}
import drt.shared.Queues.{EGate, Queue}
import drt.shared.Terminals.Terminal
import org.slf4j.{Logger, LoggerFactory}
import services.crunch.desklimits.TerminalDeskLimitsLike
import services.{OptimizerConfig, OptimizerCrunchResult, TryCrunch}

import scala.collection.immutable.{Map, NumericRange}
import scala.util.{Failure, Success}


trait TerminalDeskRecsProviderLike {
  val log: Logger = LoggerFactory.getLogger(getClass)

  val cruncher: TryCrunch
  val bankSize: Int
  val slas: Map[Queue, Int]

  def desksAndWaits(minuteMillis: NumericRange[MillisSinceEpoch],
                    loads: Map[Queue, Seq[Double]],
                    minMaxDeskProvider: TerminalDeskLimitsLike): Map[Queue, (List[Int], List[Int])]

  def staticDesksAndWaits(minuteMillis: NumericRange[MillisSinceEpoch],
                          loads: Map[Queue, Seq[Double]],
                          recsSoFar: Map[Queue, (List[Int], List[Int])],
                          minMaxDesksProvider: TerminalDeskLimitsLike): Map[Queue, (List[Int], List[Int])] = loads
    .foldLeft(List[Option[(Queue, (List[Int], List[Int]))]]()) { case (deskRecsSoFar, (queue, loadsForQueue)) =>
      log.info(s"Static optimising $queue")
      val minDesks = minMaxDesksProvider.minDesksForMinutes(minuteMillis, queue).toList
      val alreadyRecommended = recsSoFar ++ deskRecsSoFar.collect { case Some(stuff) => stuff }
      val maxDesks = minMaxDesksProvider.maxDesksForMinutes(minuteMillis, queue, alreadyRecommended).toList
      val sla = slas(queue)
      val maybeDeskRecs: Option[(Queue, (List[Int], List[Int]))] = cruncher(adjustedWork(queue, loadsForQueue), minDesks, maxDesks, OptimizerConfig(sla)) match {
        case Success(OptimizerCrunchResult(desks, waits)) => Option(queue -> ((desks.toList, waits.toList)))
        case Failure(_) => None
      }
      maybeDeskRecs :: deskRecsSoFar
    }
    .collect { case Some(result) => result }
    .toMap

  def adjustedWork(queue: Queue, work: Seq[Double]): Seq[Double] = queue match {
    case EGate => work.map(_ / bankSize)
    case _ => work
  }

  def workToDeskRecs(terminal: Terminal,
                     minuteMillis: NumericRange[MillisSinceEpoch],
                     terminalPax: Map[Queue, Seq[Double]],
                     terminalWork: Map[Queue, Seq[Double]],
                     maxDesksProvider: TerminalDeskLimitsLike): Iterable[DeskRecMinute] = {
    val queueDesksAndWaits = desksAndWaits(minuteMillis, terminalWork, maxDesksProvider)

    queueDesksAndWaits.flatMap {
      case (queue, (desks, waits)) =>
        minuteMillis.zip(terminalPax(queue).zip(terminalWork(queue))).zip(desks.zip(waits)).map {
          case ((minute, (pax, work)), (desk, wait)) => DeskRecMinute(terminal, queue, minute, pax, work, desk, wait)
        }
    }
  }
}

