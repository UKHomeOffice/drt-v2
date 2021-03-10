package services.graphstages

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.Terminals.Terminal
import drt.shared._
import drt.shared.api.Arrival
import org.slf4j.{Logger, LoggerFactory}
import services.graphstages.Crunch.{FlightSplitMinute, SplitMinutes}
import services.workloadcalculator.PaxLoadCalculator.Load

import scala.collection.immutable.Map

object WorkloadCalculator {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def flightLoadMinutes(flights: FlightsWithSplits,
                        defaultProcTimes: Map[Terminal, Map[PaxTypeAndQueue, Double]]): SplitMinutes = {
    val uniqueFlights: Iterable[ApiFlightWithSplits] = flights
      .flights.values.toList
      .sortBy(_.apiFlight.ActPax.getOrElse(0))
      .map { fws => (CodeShareKeyOrderedBySchedule(fws), fws) }
      .toMap.values

    val minutes = new SplitMinutes

    uniqueFlights
      .filter { fws =>
        !fws.apiFlight.isCancelled &&
          defaultProcTimes.contains(fws.apiFlight.Terminal) &&
          !fws.apiFlight.Origin.isCta
      }
      .foreach { incoming =>
        val procTimes = defaultProcTimes(incoming.apiFlight.Terminal)
        val flightMinutes = flightToFlightSplitMinutes(
          incoming,
          procTimes,
          Map(),
          useNationalityBasedProcTimes = false
        )
        minutes ++= flightMinutes
      }

    minutes
  }

  def flightToFlightSplitMinutes(flightWithSplits: ApiFlightWithSplits,
                                 procTimes: Map[PaxTypeAndQueue, Double],
                                 nationalityProcessingTimes: Map[Nationality, Double],
                                 useNationalityBasedProcTimes: Boolean
                                ): Iterable[FlightSplitMinute] = {
    val flight = flightWithSplits.apiFlight
    val splitsToUseOption = flightWithSplits.bestSplits
    splitsToUseOption.map(splitsToUse => {
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

      val splitsWithoutTransit = splitRatios.filterNot(_.queueType == Queues.Transfer)

      val totalPaxWithNationality = splitsWithoutTransit.toList.flatMap(_.nationalities.map(_.values.sum)).sum

      flight.paxDeparturesByMinute(20)
        .flatMap {
          case (minuteMillis, flightPaxInMinute) =>
            splitsWithoutTransit
              .map(apiSplit => {
                flightSplitMinute(
                  flight,
                  procTimes,
                  minuteMillis,
                  flightPaxInMinute,
                  apiSplit,
                  nationalityProcessingTimes,
                  totalPaxWithNationality,
                  useNationalityBasedProcTimes
                )
              })
        }
    }).getOrElse(Seq())
  }

  def flightSplitMinute(arrival: Arrival,
                        procTimes: Map[PaxTypeAndQueue, Load],
                        minuteMillis: MillisSinceEpoch,
                        flightPaxInMinute: Int,
                        apiSplitRatio: ApiPaxTypeAndQueueCount,
                        nationalityProcessingTimes: Map[Nationality, Double],
                        totalPaxWithNationality: Double,
                        useNationalityBasedProcTimes: Boolean
                       ): FlightSplitMinute = {
    val splitPaxInMinute = apiSplitRatio.paxCount * flightPaxInMinute
    val paxTypeQueueProcTime = procTimes.getOrElse(PaxTypeAndQueue(apiSplitRatio.passengerType, apiSplitRatio.queueType), 0d)
    val defaultWorkload = splitPaxInMinute * paxTypeQueueProcTime

    val splitWorkLoadInMinute = (apiSplitRatio.nationalities, useNationalityBasedProcTimes) match {
      case (Some(nats), true) if nats.values.sum > 0 =>
        val natsToPaxRatio = totalPaxWithNationality / arrival.bestPaxEstimate
        val natFactor = (flightPaxInMinute.toDouble / arrival.bestPaxEstimate) / natsToPaxRatio
        log.debug(s"totalNats: $totalPaxWithNationality / bestPax: ${arrival.bestPaxEstimate}, natFactor: $natFactor - ($flightPaxInMinute / ${arrival.bestPaxEstimate}) / $natsToPaxRatio")
        val natBasedWorkload = nats
          .map {
            case (nat, pax) => nationalityProcessingTimes.get(nat) match {
              case Some(procTime) =>
                val procTimeInMinutes = procTime / 60
                log.debug(s"Using processing time for $pax $nat: $procTimeInMinutes (rather than $paxTypeQueueProcTime)")
                pax * procTimeInMinutes * natFactor
              case None =>
                log.debug(s"Processing time for $nat not found. Using ${apiSplitRatio.passengerType} -> ${apiSplitRatio.queueType}: $paxTypeQueueProcTime")
                pax * paxTypeQueueProcTime
            }
          }
          .sum
        log.debug(s"Nationality based workload: $natBasedWorkload vs $defaultWorkload default workload")
        natBasedWorkload
      case _ =>
        defaultWorkload
    }
    FlightSplitMinute(CodeShareKeyOrderedBySchedule(arrival), apiSplitRatio.passengerType, arrival.Terminal, apiSplitRatio.queueType, splitPaxInMinute, splitWorkLoadInMinute, minuteMillis)
  }
}
