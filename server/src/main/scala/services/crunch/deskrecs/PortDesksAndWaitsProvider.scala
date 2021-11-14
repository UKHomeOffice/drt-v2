package services.crunch.deskrecs

import akka.stream.Materializer
import drt.shared.CrunchApi.{DeskRecMinute, DeskRecMinutes, MillisSinceEpoch}
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import services.crunch.desklimits.TerminalDeskLimitsLike
import services.crunch.deskrecs
import services.graphstages.Crunch.LoadMinute
import services.graphstages.QueueStatusProviders.DynamicQueueStatusProvider
import services.graphstages.{DynamicWorkloadCalculator, FlightFilter, WorkloadCalculatorLike}
import services.{SDate, TryCrunch}
import uk.gov.homeoffice.drt.egates.PortEgateBanksUpdates
import uk.gov.homeoffice.drt.ports.Queues._
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{AirportConfig, PaxTypeAndQueue}
import uk.gov.homeoffice.drt.redlist.RedListUpdates

import scala.collection.immutable
import scala.collection.immutable.{Map, NumericRange, SortedMap}
import scala.concurrent.{ExecutionContext, Future}

case class PortDesksAndWaitsProvider(queuesByTerminal: SortedMap[Terminal, Seq[Queue]],
                                     divertedQueues: Map[Queue, Queue],
                                     desksByTerminal: Map[Terminal, Int],
                                     flexedQueuesPriority: List[Queue],
                                     slas: Map[Queue, Int],
                                     terminalProcessingTimes: Map[Terminal, Map[PaxTypeAndQueue, Double]],
                                     minutesToCrunch: Int,
                                     crunchOffsetMinutes: Int,
                                     tryCrunch: TryCrunch,
                                     workloadCalculator: WorkloadCalculatorLike,
                                     dynamicQueueStatusProvider: DynamicQueueStatusProvider,
                                    ) extends PortDesksAndWaitsProviderLike {
  val log: Logger = LoggerFactory.getLogger(getClass)

  override def loadsToSimulations(minuteMillis: NumericRange[MillisSinceEpoch],
                                  loadsByQueue: Map[TQM, LoadMinute],
                                  deskLimitProviders: Map[Terminal, TerminalDeskLimitsLike])
                                 (implicit ec: ExecutionContext, mat: Materializer): Future[SimulationMinutes] = {
    loadsToDesks(minuteMillis, loadsByQueue, deskLimitProviders, dynamicQueueStatusProvider).map(deskRecMinutes =>
      SimulationMinutes(deskRecsToSimulations(deskRecMinutes.minutes).values)
    )
  }

  def deskRecsToSimulations(terminalQueueDeskRecs: Seq[DeskRecMinute]): Map[TQM, SimulationMinute] = terminalQueueDeskRecs
    .map {
      case DeskRecMinute(t, q, m, _, _, d, w) => (TQM(t, q, m), SimulationMinute(t, q, m, d, w))
    }.toMap

  def terminalDescRecs(terminal: Terminal): TerminalDesksAndWaitsProvider =
    deskrecs.TerminalDesksAndWaitsProvider(slas, flexedQueuesPriority, tryCrunch)

  override def flightsToLoads(flights: FlightsWithSplits, redListUpdates: RedListUpdates)
                             (implicit ec: ExecutionContext, mat: Materializer): Future[Map[TQM, LoadMinute]] = workloadCalculator
    .flightLoadMinutes(flights, redListUpdates).map {
    _.minutes
      .groupBy {
        case (TQM(t, q, m), _) => val finalQueueName = divertedQueues.getOrElse(q, q)
          TQM(t, finalQueueName, m)
      }
      .map {
        case (tqm, minutes) =>
          val loads = minutes.values
          (tqm, LoadMinute(tqm.terminal, tqm.queue, loads.map(_.paxLoad).sum, loads.map(_.workLoad).sum, tqm.minute))
      }
  }

  def terminalWorkLoadsByQueue(terminal: Terminal,
                               minuteMillis: NumericRange[MillisSinceEpoch],
                               loadMinutes: Map[TQM, LoadMinute]): Map[Queue, Seq[Double]] = queuesByTerminal(terminal)
    .filterNot(_ == Transfer)
    .map { queue =>
      val lms = minuteMillis.map(minute => loadMinutes.getOrElse(TQM(terminal, queue, minute), LoadMinute(terminal, queue, 0, 0, minute)).workLoad)
      (queue, lms)
    }
    .toMap

  def terminalPaxLoadsByQueue(terminal: Terminal, minuteMillis: NumericRange[MillisSinceEpoch],
                              loadMinutes: Map[TQM, LoadMinute]): Map[Queue, Seq[Double]] = queuesByTerminal(terminal)
    .filterNot(_ == Transfer)
    .map { queue =>
      val paxLoads = minuteMillis.map(minute => loadMinutes.getOrElse(TQM(terminal, queue, minute), LoadMinute(terminal, queue, 0, 0, minute)).paxLoad)
      (queue, paxLoads)
    }
    .toMap

  override def loadsToDesks(minuteMillis: NumericRange[MillisSinceEpoch],
                            loads: Map[TQM, LoadMinute],
                            deskLimitProviders: Map[Terminal, TerminalDeskLimitsLike],
                            dynamicQueueStatusProvider: DynamicQueueStatusProvider,
                           )
                           (implicit ec: ExecutionContext, mat: Materializer): Future[DeskRecMinutes] = {
    val terminalQueueDeskRecs = deskLimitProviders.map {
      case (terminal, maxDesksProvider) =>
        val terminalPax = terminalPaxLoadsByQueue(terminal, minuteMillis, loads)
        val terminalWork = terminalWorkLoadsByQueue(terminal, minuteMillis, loads)
        val statuses: Queue => Future[Map[MillisSinceEpoch, QueueStatus]] = (queue: Queue) => dynamicQueueStatusProvider.statusesForPeriod(terminal, queue, minuteMillis)
        log.debug(s"Optimising $terminal")
        terminalDescRecs(terminal).workToDeskRecs(terminal, minuteMillis, terminalPax, terminalWork, maxDesksProvider, statuses)
    }

    Future.sequence(terminalQueueDeskRecs).map { deskRecs =>
      DeskRecMinutes(deskRecs.toSeq.flatten)
    }
  }
}

object PortDesksAndWaitsProvider {
  def apply(airportConfig: AirportConfig, tryCrunch: TryCrunch, flightFilter: FlightFilter, egatesProvider: () => Future[PortEgateBanksUpdates])
           (implicit ec: ExecutionContext): PortDesksAndWaitsProvider = {
    //    val maxDeskProvider: (Terminal, Queue, SDateLike) => Future[Queues.QueueStatus] = (terminal: Terminal, queue: Queue, time: SDateLike) => {
    //      val maxDesks = airportConfig
    //        .maxDesksByTerminalAndQueue24Hrs
    //        .get(terminal)
    //        .flatMap(_.get(queue).flatMap(_.lift(time.getHours())))
    //        .map(maxDesks => if (maxDesks > 0) Open else Closed)
    //        .getOrElse(Closed)
    //
    //      Future.successful(maxDesks)
    //    }

    val desksStatusForPeriod: (Terminal, Queue, NumericRange[MillisSinceEpoch]) => Future[Map[MillisSinceEpoch, QueueStatus]] =
      (terminal: Terminal, queue: Queue, period: NumericRange[MillisSinceEpoch]) => {
        val statusByHour = airportConfig
          .maxDesksByTerminalAndQueue24Hrs
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
        Future.successful(statusByHour.toMap)
      }

    //    val maxEgatesProvider: (Terminal, SDateLike) => Future[Queues.QueueStatus] = (terminal: Terminal, time: SDateLike) => {
    //      egatesProvider().map { portUpdates =>
    //        portUpdates.updatesByTerminal
    //          .get(terminal)
    //          .flatMap { updates =>
    //            updates
    //              .forPeriod(time.millisSinceEpoch to time.millisSinceEpoch).headOption
    //              .map(banks => if (banks.exists(!_.isClosed)) Open else Closed)
    //          }
    //          .getOrElse(Closed)
    //      }
    //    }

    val egatesStatusForPeriod: (Terminal, NumericRange[MillisSinceEpoch]) => Future[Map[MillisSinceEpoch, QueueStatus]] =
      (terminal: Terminal, period: NumericRange[MillisSinceEpoch]) => {
        egatesProvider().map { portUpdates =>
          portUpdates.updatesByTerminal
            .get(terminal)
            .map { updates =>
              val statuses = updates
                .forPeriod(period)
                .map(b => if (b.exists(!_.isClosed)) Open else Closed)
              val statusesByMinute: immutable.Seq[(MillisSinceEpoch, QueueStatus)] = period.zip(statuses)
              statusesByMinute.toMap
            }
            .getOrElse(period.map(m => (m, Closed)))
        }
      }

    val calculator = DynamicWorkloadCalculator(
      airportConfig.terminalProcessingTimes,
      DynamicStatusProvider(desksStatusForPeriod, egatesStatusForPeriod),
      QueueFallbacks(airportConfig.queuesByTerminal),
      flightFilter,
    )

    PortDesksAndWaitsProvider(
      queuesByTerminal = airportConfig.queuesByTerminal,
      divertedQueues = airportConfig.divertedQueues,
      desksByTerminal = airportConfig.desksByTerminal,
      flexedQueuesPriority = airportConfig.queuePriority,
      slas = airportConfig.slaByQueue,
      terminalProcessingTimes = airportConfig.terminalProcessingTimes,
      minutesToCrunch = airportConfig.minutesToCrunch,
      crunchOffsetMinutes = airportConfig.crunchOffsetMinutes,
      tryCrunch = tryCrunch,
      workloadCalculator = calculator
    )
  }
}
