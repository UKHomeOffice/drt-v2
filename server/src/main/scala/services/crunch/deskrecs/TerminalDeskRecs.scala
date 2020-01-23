package services.crunch.deskrecs

import drt.shared.AirportConfig
import drt.shared.CrunchApi.{DeskRecMinute, MillisSinceEpoch}
import drt.shared.Queues.{EGate, Queue}
import drt.shared.Terminals.Terminal
import org.slf4j.{Logger, LoggerFactory}
import services.{OptimizerConfig, OptimizerCrunchResult, TryCrunch}
import services.crunch.deskrecs.DeskRecs.desksForHourOfDayInUKLocalTime

import scala.collection.immutable.{Map, NumericRange}
import scala.util.{Failure, Success}

trait TerminalDeskRecsLike {
  val log: Logger = LoggerFactory.getLogger(getClass)

  val airportConfig: AirportConfig
  val cruncher: TryCrunch
  val bankSize: Int

  def desksAndWaits(loads: Map[Queue, Seq[Double]],
                    minDesks: Map[Queue, List[Int]],
                    maxDesks: Map[Queue, List[Int]],
                    slas: Map[Queue, Int]): Map[Queue, (List[Int], List[Int])] = loads
    .map { case (queueProcessing, loadsForQueue) =>
      val min = minDesks(queueProcessing)
      val max = maxDesks(queueProcessing)
      val sla = slas(queueProcessing)
      cruncher(adjustedWork(queueProcessing, loadsForQueue, bankSize), min, max, OptimizerConfig(sla)) match {
        case Success(OptimizerCrunchResult(desks, waits)) => Option(queueProcessing -> ((desks.toList, waits.toList)))
        case Failure(_) => None
      }
    }
    .collect { case Some(result) => result }
    .toMap

  def adjustedWork(queue: Queue, work: Seq[Double], bankSize: Int): Seq[Double] = queue match {
    case EGate => work.map(_ / bankSize)
    case _ => work
  }

  def minMaxDesksForQueue(deskRecMinutes: Iterable[MillisSinceEpoch], tn: Terminal, qn: Queue, airportConfig: AirportConfig): (List[Int], List[Int]) = {
    val defaultMinMaxDesks = (List.fill(24)(0), List.fill(24)(10))
    val queueMinMaxDesks = airportConfig.minMaxDesksByTerminalQueue.getOrElse(tn, Map()).getOrElse(qn, defaultMinMaxDesks)
    val minDesks = deskRecMinutes.map(desksForHourOfDayInUKLocalTime(_, queueMinMaxDesks._1))
    val maxDesks = deskRecMinutes.map(desksForHourOfDayInUKLocalTime(_, queueMinMaxDesks._2))
    (minDesks.toList, maxDesks.toList)
  }

  def terminalWorkToDeskRecs(terminal: Terminal,
                             minuteMillis: NumericRange[MillisSinceEpoch],
                             terminalPax: Map[Queue, Seq[Double]],
                             terminalWork: Map[Queue, Seq[Double]],
                             terminalRecs: TerminalDeskRecsLike): Iterable[DeskRecMinute] = {
    val minMaxDesks = airportConfig.queuesByTerminal(terminal).map { queue =>
      (queue, minMaxDesksForQueue(minuteMillis, terminal, queue, airportConfig))
    }.toMap
    val minDesks = minMaxDesks.mapValues(_._1)
    val maxDesks = minMaxDesks.mapValues(_._2)

    val queueDesksAndWaits = terminalRecs.desksAndWaits(terminalWork, minDesks, maxDesks, airportConfig.slaByQueue)

    queueDesksAndWaits.flatMap {
      case (queue, (desks, waits)) =>
        minuteMillis.zip(terminalPax(queue).zip(terminalWork(queue))).zip(desks.zip(waits)).map {
          case ((minute, (pax, work)), (desk, wait)) => DeskRecMinute(terminal, queue, minute, pax, work, desk, wait)
        }
    }
  }

}

case class StaticTerminal(airportConfig: AirportConfig, cruncher: TryCrunch, bankSize: Int) extends TerminalDeskRecsLike

case class FlexedTerminal(airportConfig: AirportConfig, terminalDesks: Int, flexedQueuesPriority: List[Queue], cruncher: TryCrunch, bankSize: Int) extends TerminalDeskRecsLike {
  override def desksAndWaits(loads: Map[Queue, Seq[Double]],
                             minDesks: Map[Queue, List[Int]],
                             maxDesks: Map[Queue, List[Int]],
                             slas: Map[Queue, Int]): Map[Queue, (List[Int], List[Int])] = {
    val queuesToOptimise: Set[Queue] = loads.keys.toSet
    val flexedQueuesToOptimise = queuesToOptimise.filter(q => flexedQueuesPriority.contains(q))
    val staticQueuesToOptimise = queuesToOptimise.filter(q => !flexedQueuesPriority.contains(q))

    val flexedRecs = flexedQueuesPriority
      .filter(flexedQueued => flexedQueuesToOptimise.toList.contains(flexedQueued))
      .foldLeft(Map[Queue, (List[Int], List[Int])]()) {
        case (queueRecsSoFar, queueProcessing) =>
          flexedQueueTerminalRecs(terminalDesks, cruncher, bankSize, loads, minDesks, slas, flexedQueuesToOptimise, queueRecsSoFar, queueProcessing)
      }

    val staticRecs = super.desksAndWaits(loads.filterKeys(staticQueuesToOptimise), minDesks, maxDesks, slas)

    flexedRecs ++ staticRecs
  }

  def staticQueueTerminalRecs(cruncher: TryCrunch,
                              bankSize: Int,
                              loads: Map[Queue, Seq[Double]],
                              minDesks: Map[Queue, List[Int]],
                              maxDesks: Map[Queue, List[Int]],
                              slas: Map[Queue, Int],
                              queueProcessing: Queue): Option[(Queue, (List[Int], List[Int]))] = {
    val work = adjustedWork(queueProcessing, loads(queueProcessing), bankSize)
    val queueMinDesks = minDesks(queueProcessing)
    val queueMaxDesks = maxDesks(queueProcessing)
    val queueSla = slas(queueProcessing)
    cruncher(work, queueMinDesks, queueMaxDesks, OptimizerConfig(queueSla)) match {
      case Success(OptimizerCrunchResult(desks, waits)) => Option(queueProcessing -> ((desks.toList, waits.toList)))
      case Failure(_) => None
    }
  }

  def flexedQueueTerminalRecs(terminalDesks: Int,
                              cruncher: TryCrunch,
                              bankSize: Int,
                              loads: Map[Queue, Seq[Double]],
                              minDesks: Map[Queue, List[Int]],
                              slas: Map[Queue, Int],
                              flexedQueuesToOptimise: Set[Queue],
                              queueRecsSoFar: Map[Queue, (List[Int], List[Int])],
                              queueProcessing: Queue): Map[Queue, (List[Int], List[Int])] = {
    val queuesProcessed = queueRecsSoFar.keys.toSet
    val queuesToBeProcessed = flexedQueuesToOptimise -- (queuesProcessed + queueProcessing)
    val availableMinusRemainingMinimums: List[Int] = queuesToBeProcessed.foldLeft(List.fill(loads(queueProcessing).length)(terminalDesks)) {
      case (availableSoFar, queue) => availableSoFar.zip(minDesks(queue)).map { case (a, b) => a - b }
    }
    val actualAvailable: List[Int] = queueRecsSoFar.values
      .foldLeft(availableMinusRemainingMinimums) {
        case (availableSoFar, (recs, _)) => availableSoFar.zip(recs).map { case (a, b) => a - b }
      }
    cruncher(adjustedWork(queueProcessing, loads(queueProcessing), bankSize), minDesks(queueProcessing), actualAvailable, OptimizerConfig(slas(queueProcessing))) match {
      case Success(OptimizerCrunchResult(desks, waits)) => queueRecsSoFar + (queueProcessing -> ((desks.toList, waits.toList)))
      case Failure(t) =>
        log.error(s"Crunch failed for $queueProcessing", t)
        queueRecsSoFar
    }
  }
}
