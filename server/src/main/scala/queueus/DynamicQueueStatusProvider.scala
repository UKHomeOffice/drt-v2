package queueus

import drt.shared.CrunchApi.MillisSinceEpoch
import uk.gov.homeoffice.drt.time.SDate
import uk.gov.homeoffice.drt.egates.PortEgateBanksUpdates
import uk.gov.homeoffice.drt.ports.AirportConfig
import uk.gov.homeoffice.drt.ports.Queues._
import uk.gov.homeoffice.drt.ports.Terminals.Terminal

import scala.collection.immutable.{Map, NumericRange}
import scala.concurrent.{ExecutionContext, Future}

case class DynamicQueueStatusProvider(airportConfig: AirportConfig, egatesProvider: () => Future[PortEgateBanksUpdates])
                                     (implicit ec: ExecutionContext) {

  def allStatusesForPeriod: NumericRange[MillisSinceEpoch] => Future[Map[Terminal, Map[Queue, Map[MillisSinceEpoch, QueueStatus]]]] =
    period => {
      egatesProvider().map { egatePortUpdates =>
        airportConfig.queuesByTerminalWithDiversions.map { case (terminal, queues) =>
          val byQueue = queues.map {
            case (EGate, _) => (EGate, egateStatuses(period, egatePortUpdates, terminal))
            case (from, to) => (from, deskStatuses(airportConfig.maxDesksByTerminalAndQueue24Hrs, period, terminal, to))
          }.toMap
          (terminal, byQueue)
        }
      }
    }

  private def egateStatuses(period: NumericRange[MillisSinceEpoch],
                            egatePortUpdates: PortEgateBanksUpdates,
                            terminal: Terminal): Map[MillisSinceEpoch, QueueStatus] =
    egatePortUpdates.updatesByTerminal
      .get(terminal)
      .map { updates =>
        val statuses = updates.forPeriod(period).map(b => if (b.exists(!_.isClosed)) Open else Closed)
        period.zip(statuses)
      }
      .getOrElse(period.map(m => (m, Closed)))
      .toMap

  private def deskStatuses(maxDesks: Map[Terminal, Map[Queue, IndexedSeq[Int]]],
                           period: NumericRange[MillisSinceEpoch],
                           terminal: Terminal,
                           queue: Queue): Map[MillisSinceEpoch, QueueStatus] =
    maxDesks
      .get(terminal)
      .flatMap(_.get(queue).map { maxByHour =>
        val maxByHourLifted = maxByHour.lift
        period.map { minute =>
          val status = maxByHourLifted(SDate(minute).getHours()) match {
            case None => Closed
            case Some(0) => Closed
            case Some(_) => Open
          }
          (minute, status)
        }
      })
      .getOrElse(period.map(m => (m, Closed)))
      .toMap
}

