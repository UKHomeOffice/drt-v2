package uk.gov.homeoffice.drt.service

import uk.gov.homeoffice.drt.ports.Queues._
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.LocalDate

import scala.collection.SortedMap

object QueueConfig {
  def queuesForDateAndTerminal(configOverTime: Map[LocalDate, Map[Terminal, Seq[Queue]]]): (LocalDate, Terminal) => Seq[Queue] =
    (queryDate: LocalDate, terminal: Terminal) => {
      val relevantDates: SortedMap[LocalDate, Map[Terminal, Seq[Queue]]] = SortedMap.empty[LocalDate, Map[Terminal, Seq[Queue]]] ++ configOverTime.filter {
        case (configDate, _) => configDate <= queryDate
      }

      relevantDates.toSeq.reverse.headOption
        .map {
          case (_, terminalQueues) => terminalQueues.getOrElse(terminal, Seq.empty[Queue])
        }
        .getOrElse(Seq.empty[Queue])
    }

  def terminalsForDate(configOverTime: Map[LocalDate, Map[Terminal, Seq[Queue]]]): LocalDate => Seq[Terminal] =
    queryDate => {
      val relevantDates: SortedMap[LocalDate, Map[Terminal, Seq[Queue]]] = SortedMap.empty[LocalDate, Map[Terminal, Seq[Queue]]] ++ configOverTime.filter {
        case (configDate, _) => configDate <= queryDate
      }

      relevantDates.toSeq.reverse.headOption
        .map {
          case (_, terminalQueues) => terminalQueues.keys.toSeq.sorted
        }
        .getOrElse(Seq.empty[Terminal])
    }

  def queuesForDateRangeAndTerminal(configOverTime: Map[LocalDate, Map[Terminal, Seq[Queue]]]): (LocalDate, LocalDate, Terminal) => Set[Queue] =
    (start: LocalDate, end: LocalDate, terminal: Terminal) => {
      val relevantDates = configOverTime.keys.toSeq
        .filter(d => start <= d && d <= end)
        .sorted

      relevantDates.foldLeft(Set.empty[Queue]) {
        case (agg, date) =>
          val dateQueues = configOverTime.get(date).map(_.getOrElse(terminal, Seq())).getOrElse(Seq())
          agg ++ dateQueues.toSet
      }
    }

//  def queuesByTerminalWithDiversions(queuesByTerminal: SortedMap[Terminal, Seq[Queue]],
//                                     divertedQueues: Map[Queue, Queue],
//                                    ): Map[Terminal, Map[Queue, Queue]] = queuesByTerminal
//    .view.mapValues { queues =>
//      val diversionQueues = divertedQueues.values.toSet
//      val nonDivertedQueues = queues
//        .filterNot(q => diversionQueues.contains(q))
//        .map(q => (q, q))
//      (nonDivertedQueues ++ divertedQueues).toMap
//    }.toMap
}
