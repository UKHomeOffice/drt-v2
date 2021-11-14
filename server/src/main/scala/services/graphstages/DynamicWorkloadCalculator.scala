package services.graphstages

import akka.stream.Materializer
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared._
import drt.shared.api.Arrival
import org.slf4j.{Logger, LoggerFactory}
import services.SDate
import services.graphstages.Crunch.{FlightSplitMinute, SplitMinutes}
import services.graphstages.QueueStatusProviders.{DynamicQueueStatusProvider, QueueStatusProvider}
import services.workloadcalculator.PaxLoadCalculator.Load
import uk.gov.homeoffice.drt.egates.PortEgateBanksUpdates
import uk.gov.homeoffice.drt.ports.Queues._
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{AirportConfig, ApiPaxTypeAndQueueCount, PaxTypeAndQueue, Queues}
import uk.gov.homeoffice.drt.redlist.RedListUpdates

import scala.collection.immutable
import scala.collection.immutable.{Map, NumericRange}
import scala.concurrent.{ExecutionContext, Future}

object QueueStatusProviders {

  trait QueueStatusProvider {
    def statusAt(terminal: Terminal, queue: Queue, time: SDateLike): Future[QueueStatus]
  }

  case object QueuesAlwaysOpen extends QueueStatusProvider {
    override def statusAt(terminal: Terminal, queue: Queue, time: SDateLike): Future[QueueStatus] = Future.successful(Open)
  }

  //  case class DynamicStatusProvider(desksStatus: (Terminal, Queue, NumericRange[MillisSinceEpoch]) => Future[Map[MillisSinceEpoch, QueueStatus]],
  //                                   egatesStatus: (Terminal, NumericRange[MillisSinceEpoch]) => Future[Map[MillisSinceEpoch, QueueStatus]])
  //                                  (implicit ec: ExecutionContext) extends QueueStatusProvider {
  //    override def statusAt(terminal: Terminal, queue: Queue, time: SDateLike): Future[QueueStatus] =
  //      queue match {
  //        case EGate => egatesStatus(terminal, time)
  //        case deskQueue => desksStatus(terminal, deskQueue, time)
  //      }
  //  }

  //  case class QueuesStatusProvider()

  case class DynamicQueueStatusProvider(airportConfig: AirportConfig, egatesProvider: () => Future[PortEgateBanksUpdates])
                                       (implicit ec: ExecutionContext) {
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

    def allStatusesForPeriod: NumericRange[MillisSinceEpoch] => Future[Map[Terminal, Map[Queue, Map[MillisSinceEpoch, QueueStatus]]]] =
      period => {
        egatesProvider().map { portUpdates =>
          airportConfig.queuesByTerminal.map { case (terminal, queues) =>
            portUpdates.updatesByTerminal
              .get(terminal)
              .map { updates =>
                updates.forPeriod(period).map(b => if (b.exists(!_.isClosed)) Open else Closed)
              }
          }
        }

      }

    def statusesForPeriod: (Terminal, Queue, NumericRange[MillisSinceEpoch]) => Future[Map[MillisSinceEpoch, QueueStatus]] =
      (terminal: Terminal, queue: Queue, period: NumericRange[MillisSinceEpoch]) => {
        queue match {
          case EGate => egatesStatusForPeriod(terminal, period)
          case deskQueue => desksStatusForPeriod(terminal, deskQueue, period)
        }
      }
  }


  case class FlexibleEGatesForSimulation(eGateOpenHours: Seq[Int]) extends QueueStatusProvider {
    override def statusAt(t: Terminal, queue: Queue, time: SDateLike): Future[QueueStatus] =
      queue match {
        case EGate if !eGateOpenHours.contains(time.getHours()) => Future.successful(Closed)
        case _ => Future.successful(Open)
      }
  }
}


trait WorkloadCalculatorLike {
  val queueStatusProvider: QueueStatusProvider

  val defaultProcTimes: Map[Terminal, Map[PaxTypeAndQueue, Double]]

  def flightLoadMinutes(flights: FlightsWithSplits, redListUpdates: RedListUpdates)
                       (implicit ex: ExecutionContext, mat: Materializer): Future[SplitMinutes]

  def combineCodeShares(flights: Iterable[ApiFlightWithSplits]): Iterable[ApiFlightWithSplits] = {
    val uniqueFlights: Iterable[ApiFlightWithSplits] = flights
      .toList
      .sortBy(_.apiFlight.ActPax.getOrElse(0))
      .map { fws => (CodeShareKeyOrderedBySchedule(fws), fws) }
      .toMap.values
    uniqueFlights
  }

  val flightHasWorkload: FlightFilter

  def flightsWithPcpWorkload(flights: Iterable[ApiFlightWithSplits], redListUpdates: RedListUpdates): Iterable[ApiFlightWithSplits] =
    flights.filter(fws => flightHasWorkload.apply(fws, redListUpdates))

  def paxTypeAndQueueCountsFromSplits(splitsToUse: Splits): Set[ApiPaxTypeAndQueueCount] = {
    val splitRatios: Set[ApiPaxTypeAndQueueCount] = splitsToUse.splitStyle match {
      case UndefinedSplitStyle => splitsToUse.splits.map(qc => qc.copy(paxCount = 0))
      case PaxNumbers =>
        val splitsWithoutTransit = splitsToUse.splits.filter(_.queueType != Queues.Transfer)
        val totalSplitsPax: Load = splitsWithoutTransit.toList.map(_.paxCount).sum
        if (totalSplitsPax == 0.0)
          splitsWithoutTransit
        else
          splitsWithoutTransit.map(qc => qc.copy(paxCount = qc.paxCount / totalSplitsPax))
      case _ => splitsToUse.splits.map(qc => qc.copy(paxCount = qc.paxCount / 100))
    }
    splitRatios
  }

}

case class DynamicWorkloadCalculator(defaultProcTimes: Map[Terminal, Map[PaxTypeAndQueue, Double]],
                                     dynamicQueueStatusProvider: DynamicQueueStatusProvider,
                                     fallbacksProvider: QueueFallbacks,
                                     flightHasWorkload: FlightFilter)
  extends WorkloadCalculatorLike {

  val log: Logger = LoggerFactory.getLogger(getClass)

  override def flightLoadMinutes(flights: FlightsWithSplits, redListUpdates: RedListUpdates)
                                (implicit ex: ExecutionContext, mat: Materializer): Future[SplitMinutes] = {
    val pcpFlights: immutable.Seq[ApiFlightWithSplits] = flightsWithPcpWorkload(combineCodeShares(flights.flights.values), redListUpdates).toList
    dynamicQueueStatusProvider.
      Source(pcpFlights)
      .runFoldAsync(SplitMinutes(Map())) {
        case (acc, fws) => flightToFlightSplitMinutes(fws).map(fsm => acc ++ fsm)
      }
  }

  def flightToFlightSplitMinutes(flightWithSplits: ApiFlightWithSplits)
                                (implicit ec: ExecutionContext): Future[Iterable[FlightSplitMinute]] = {
    val eventualFlightSplitMinutes = defaultProcTimes.get(flightWithSplits.apiFlight.Terminal) match {
      case None => Iterable()
      case Some(procTimes) =>
        val flight = flightWithSplits.apiFlight

        flightWithSplits.bestSplits.map { splitsToUse =>
          val paxTypeAndQueueCounts = paxTypeAndQueueCountsFromSplits(splitsToUse)

          val paxTypesAndQueuesMinusTransit = paxTypeAndQueueCounts.filterNot(_.queueType == Queues.Transfer)

          flight.paxDeparturesByMinute(20)
            .flatMap {
              case (minuteMillis, flightPaxInMinute) =>
                paxTypesAndQueuesMinusTransit
                  .map { case ptqc@ApiPaxTypeAndQueueCount(pt, queue, _, _, _) =>
                    def findAlternativeQueue(originalQueue: Queue, queuesToTry: Iterable[Queue]): Future[Queue] = {
                      Future
                        .find(queuesToTry.map { q =>
                          queueStatusProvider.statusAt(flight.Terminal, q, SDate(minuteMillis)).map(s => (q, s))
                        }.toList)(_._2 == Open)
                        .map {
                          case None => originalQueue
                          case Some((q, _)) => q
                        }
                    }

                    queueStatusProvider
                      .statusAt(flight.Terminal, queue, SDate(minuteMillis))
                      .flatMap {
                        case Closed =>
                          val fallbacks = fallbacksProvider.availableFallbacks(flight.Terminal, queue, pt)
                          findAlternativeQueue(queue, fallbacks).map { newQueue =>
                            println(s"closed at ${SDate(minuteMillis).toISOString()}: fallbacks: $fallbacks, new queue: $newQueue")
                            ptqc.copy(queueType = newQueue)
                          }
                        case Open => Future.successful(ptqc)
                      }
                      .map(flightSplitMinute(flight, procTimes, minuteMillis, flightPaxInMinute, _))
                  }
            }
        }.getOrElse(Seq())
    }
    Future.sequence(eventualFlightSplitMinutes)
  }

  def flightSplitMinute(arrival: Arrival,
                        procTimes: Map[PaxTypeAndQueue, Load],
                        minuteMillis: MillisSinceEpoch,
                        flightPaxInMinute: Int,
                        apiSplitRatio: ApiPaxTypeAndQueueCount
                       ): FlightSplitMinute = {
    val splitPaxInMinute = apiSplitRatio.paxCount * flightPaxInMinute
    val paxTypeQueueProcTime = procTimes.getOrElse(PaxTypeAndQueue(apiSplitRatio.passengerType, apiSplitRatio.queueType), 0d)
    val defaultWorkload = splitPaxInMinute * paxTypeQueueProcTime

    FlightSplitMinute(CodeShareKeyOrderedBySchedule(arrival), apiSplitRatio.passengerType, arrival.Terminal, apiSplitRatio.queueType, splitPaxInMinute, defaultWorkload, minuteMillis)
  }
}
