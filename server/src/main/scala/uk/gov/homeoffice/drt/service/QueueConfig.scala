package uk.gov.homeoffice.drt.service

import uk.gov.homeoffice.drt.ports.Queues._
import uk.gov.homeoffice.drt.ports.Terminals.{T1, T2, Terminal}
import uk.gov.homeoffice.drt.ports.{AirportConfig, PortCode}
import uk.gov.homeoffice.drt.time.LocalDate

import scala.collection.SortedMap
import scala.concurrent.{ExecutionContext, Future}

object QueueConfig {
  val queueConfig: AirportConfig => Map[LocalDate, Map[Terminal, Seq[Queue]]] =
    airportConfig =>
      airportConfig.portCode match {
        case PortCode("BHX") => Map(
          LocalDate(2014, 1, 1) -> Map(T1 -> Seq(EeaDesk, EGate, NonEeaDesk), T2 -> Seq(EeaDesk, NonEeaDesk)),
          LocalDate(2025, 6, 2) -> Map(T1 -> Seq(EeaDesk, EGate, NonEeaDesk), T2 -> Seq(QueueDesk)),
        )
        case _ => Map(LocalDate(2024, 1, 1) -> airportConfig.queuesByTerminal)
      }

  def queuesForDateAndTerminal(provider: () => Future[Map[LocalDate, Map[Terminal, Seq[Queue]]]])
                              (implicit ec: ExecutionContext): (LocalDate, Terminal) => Future[Seq[Queue]] =
    (queryDate: LocalDate, terminal: Terminal) => {
      provider().map { conf =>
        val relevantDates: SortedMap[LocalDate, Map[Terminal, Seq[Queue]]] = SortedMap.empty[LocalDate, Map[Terminal, Seq[Queue]]] ++ conf.filter {
          case (configDate, _) => configDate <= queryDate
        }

        relevantDates.toSeq.reverse.headOption
          .map {
            case (_, terminalQueues) => terminalQueues.getOrElse(terminal, Seq.empty[Queue])
          }
          .getOrElse(Seq.empty[Queue])
      }
    }

  def queuesForDateRangeAndTerminal(provider: () => Future[Map[LocalDate, Map[Terminal, Seq[Queue]]]])
                                   (implicit ec: ExecutionContext): (LocalDate, LocalDate, Terminal) => Future[Set[Queue]] =
    (start: LocalDate, end: LocalDate, terminal: Terminal) => {
      provider().map { conf =>
        val relevantDates = conf.keys.toSeq
          .filter(d => start <= d && d <= end)
          .sorted

        relevantDates.foldLeft(Set.empty[Queue]) {
          case (agg, date) =>
            val dateQueues = conf.get(date).map(_.getOrElse(terminal, Seq())).getOrElse(Seq())
            agg ++ dateQueues.toSet
        }
      }
    }
}
