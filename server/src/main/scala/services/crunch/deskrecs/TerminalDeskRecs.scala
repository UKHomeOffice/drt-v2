package services.crunch.deskrecs

import drt.shared.CrunchApi.{DeskRecMinute, MillisSinceEpoch}
import drt.shared.Queues.{EGate, Queue}
import drt.shared.Terminals.Terminal
import org.slf4j.{Logger, LoggerFactory}
import services.crunch.deskrecs.DeskRecs.desksForHourOfDayInUKLocalTime
import services.crunch.deskrecs.StaffProviders.MaxDesksProvider
import services.{OptimizerConfig, OptimizerCrunchResult, TryCrunch}

import scala.collection.immutable.{Map, NumericRange, SortedMap}
import scala.util.{Failure, Success}


trait TerminalDeskRecsProviderLike {
  val log: Logger = LoggerFactory.getLogger(getClass)

  val queuesByTerminal: SortedMap[Terminal, Seq[Queue]]
  val minMaxDesks: Map[Terminal, Map[Queue, (List[Int], List[Int])]]
  val cruncher: TryCrunch
  val bankSize: Int
  val slas: Map[Queue, Int]

  def desksAndWaits(loads: Map[Queue, Seq[Double]],
                    minDesks: Map[Queue, List[Int]],
                    maxDesks: Map[Queue, List[Int]],
                    flexedMaxDeskProvider: MaxDesksProvider): Map[Queue, (List[Int], List[Int])]

  def staticDesksAndWaits(loads: Map[Queue, Seq[Double]],
                          minDesks: Map[Queue, List[Int]],
                          maxDesks: Map[Queue, List[Int]]): Map[Queue, (List[Int], List[Int])] = loads
    .map { case (queueProcessing, loadsForQueue) =>
      log.info(s"Static optimising $queueProcessing")
      val min = minDesks(queueProcessing)
      val max = maxDesks(queueProcessing)
      val sla = slas(queueProcessing)
      cruncher(adjustedWork(queueProcessing, loadsForQueue), min, max, OptimizerConfig(sla)) match {
        case Success(OptimizerCrunchResult(desks, waits)) => Option(queueProcessing -> ((desks.toList, waits.toList)))
        case Failure(_) => None
      }
    }
    .collect { case Some(result) => result }
    .toMap

  def adjustedWork(queue: Queue, work: Seq[Double]): Seq[Double] = queue match {
    case EGate => work.map(_ / bankSize)
    case _ => work
  }

  def minMaxDesksForQueue(deskRecMinutes: Iterable[MillisSinceEpoch],
                          tn: Terminal,
                          qn: Queue): (List[Int], List[Int]) = {
    val defaultMinMaxDesks = (List.fill(24)(0), List.fill(24)(10))
    val queueMinMaxDesks = minMaxDesks.getOrElse(tn, Map()).getOrElse(qn, defaultMinMaxDesks)
    val minDesks = deskRecMinutes.map(desksForHourOfDayInUKLocalTime(_, queueMinMaxDesks._1))
    val maxDesks = deskRecMinutes.map(desksForHourOfDayInUKLocalTime(_, queueMinMaxDesks._2))
    (minDesks.toList, maxDesks.toList)
  }

  def workToDeskRecs(terminal: Terminal,
                     minuteMillis: NumericRange[MillisSinceEpoch],
                     terminalPax: Map[Queue, Seq[Double]],
                     terminalWork: Map[Queue, Seq[Double]],
                     maxStaff: MaxDesksProvider): Iterable[DeskRecMinute] = {
    val terminalMinMaxDesks = queuesByTerminal(terminal).map { queue =>
      (queue, minMaxDesksForQueue(minuteMillis, terminal, queue))
    }.toMap
    val minDesks = terminalMinMaxDesks.mapValues(_._1)
    val maxDesks = terminalMinMaxDesks.mapValues(_._2)

    val queueDesksAndWaits = desksAndWaits(terminalWork, minDesks, maxDesks, maxStaff)

    queueDesksAndWaits.flatMap {
      case (queue, (desks, waits)) =>
        minuteMillis.zip(terminalPax(queue).zip(terminalWork(queue))).zip(desks.zip(waits)).map {
          case ((minute, (pax, work)), (desk, wait)) => DeskRecMinute(terminal, queue, minute, pax, work, desk, wait)
        }
    }
  }
}

case class StaticTerminalDeskRecsProvider(queuesByTerminal: SortedMap[Terminal, Seq[Queue]],
                                          minMaxDesks: Map[Terminal, Map[Queue, (List[Int], List[Int])]],
                                          slas: Map[Queue, Int],
                                          cruncher: TryCrunch,
                                          bankSize: Int) extends TerminalDeskRecsProviderLike {
  override def desksAndWaits(loads: Map[Queue, Seq[Double]],
                             minDesks: Map[Queue, List[Int]],
                             maxDesks: Map[Queue, List[Int]],
                             flexedMaxDeskProvider: MaxDesksProvider): Map[Queue, (List[Int], List[Int])] =
    staticDesksAndWaits(loads, minDesks, maxDesks)
}

case class FlexedTerminalDeskRecsProvider(queuesByTerminal: SortedMap[Terminal, Seq[Queue]],
                                          minMaxDesks: Map[Terminal, Map[Queue, (List[Int], List[Int])]],
                                          slas: Map[Queue, Int],
                                          terminalDesks: Int,
                                          flexedQueuesPriority: List[Queue],
                                          cruncher: TryCrunch,
                                          bankSize: Int) extends TerminalDeskRecsProviderLike {
  override def desksAndWaits(loads: Map[Queue, Seq[Double]],
                             minDesks: Map[Queue, List[Int]],
                             maxDesks: Map[Queue, List[Int]],
                             flexedMaxDeskProvider: MaxDesksProvider): Map[Queue, (List[Int], List[Int])] = {
    val queuesToOptimise: Set[Queue] = loads.keys.toSet
    val flexedQueuesToOptimise = queuesToOptimise.filter(q => flexedQueuesPriority.contains(q))
    val staticQueuesToOptimise = queuesToOptimise.filter(q => !flexedQueuesPriority.contains(q))

    val flexedRecs = flexedDesksAndWaits(flexedQueuesToOptimise, loads, minDesks, flexedMaxDeskProvider)

    val staticRecs = staticDesksAndWaits(loads.filterKeys(staticQueuesToOptimise), minDesks, maxDesks)

    flexedRecs ++ staticRecs
  }

  def flexedDesksAndWaits(flexedQueuesToOptimise: Set[Queue],
                                loads: Map[Queue, Seq[Double]],
                                minDesks: Map[Queue, List[Int]],
                                maxStaff: MaxDesksProvider): Map[Queue, (List[Int], List[Int])] = flexedQueuesPriority
    .filter(flexedQueued => flexedQueuesToOptimise.toList.contains(flexedQueued))
    .foldLeft(Map[Queue, (List[Int], List[Int])]()) {
      case (queueRecsSoFar, queueProcessing) =>
        log.info(s"Flexed optimising $queueProcessing")
        val queueWork = adjustedWork(queueProcessing, loads(queueProcessing))
        val terminalDesksByMinute = List.fill(queueWork.length)(terminalDesks)
        val actualMax = maxStaff(terminalDesksByMinute, flexedQueuesToOptimise, minDesks, queueRecsSoFar, queueProcessing)
        flexedQueueDesksAndWaits(queueWork, minDesks(queueProcessing), queueRecsSoFar, queueProcessing, actualMax)
    }

  def flexedQueueDesksAndWaits(loads: Seq[Double],
                                     queueMinDesks: List[Int],
                                     queueRecsSoFar: Map[Queue, (List[Int], List[Int])],
                                     queueProcessing: Queue,
                                     maxStaff: List[Int]): Map[Queue, (List[Int], List[Int])] = {
    val queueSlas = slas(queueProcessing)

    cruncher(loads, queueMinDesks, maxStaff, OptimizerConfig(queueSlas)) match {
      case Success(OptimizerCrunchResult(desks, waits)) => queueRecsSoFar + (queueProcessing -> ((desks.toList, waits.toList)))
      case Failure(t) =>
        log.error(s"Crunch failed for $queueProcessing", t)
        queueRecsSoFar
    }
  }
}
