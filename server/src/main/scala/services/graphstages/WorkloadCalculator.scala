package services.graphstages

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.SplitRatiosNs.SplitSources
import drt.shared._
import services.graphstages.Crunch.{FlightSplitMinute, log}
import services.workloadcalculator.PaxLoadCalculator.{Load, minutesForHours, paxDeparturesPerMinutes, paxOffFlowRate}

import scala.collection.immutable.Map

object WorkloadCalculator {
  def flightToFlightSplitMinutes(flight: Arrival,
                                 splits: Set[ApiSplits],
                                 procTimes: Map[PaxTypeAndQueue, Double],
                                 nationalityProcessingTimes: Map[String, Double]): Set[FlightSplitMinute] = {
    val apiSplitsDc = splits.find(s => s.source == SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages && s.eventType.contains(DqEventCodes.DepartureConfirmed))
    val apiSplitsCi = splits.find(s => s.source == SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages && s.eventType.contains(DqEventCodes.CheckIn))
    val historicalSplits = splits.find(_.source == SplitSources.Historical)
    val terminalSplits = splits.find(_.source == SplitSources.TerminalAverage)

    val splitsToUseOption: Option[ApiSplits] = List(apiSplitsDc, apiSplitsCi, historicalSplits, terminalSplits).find {
      case Some(_) => true
      case _ => false
    }.getOrElse {
      log.error(s"Couldn't find terminal splits from AirportConfig to fall back on...")
      None
    }

    splitsToUseOption.map(splitsToUse => {
      val totalPax = splitsToUse.splitStyle match {
        case UndefinedSplitStyle => 0
        case _ => ArrivalHelper.bestPax(flight)
      }
      val splitRatios: Set[ApiPaxTypeAndQueueCount] = splitsToUse.splitStyle match {
        case UndefinedSplitStyle => splitsToUse.splits.map(qc => qc.copy(paxCount = 0))
        case PaxNumbers => {
          val splitsWithoutTransit = splitsToUse.splits.filter(_.queueType != Queues.Transfer)
          val totalSplitsPax = splitsWithoutTransit.toList.map(_.paxCount).sum
          splitsWithoutTransit.map(qc => qc.copy(paxCount = qc.paxCount / totalSplitsPax))
        }
        case _ => splitsToUse.splits.map(qc => qc.copy(paxCount = qc.paxCount / 100))
      }

      val splitsWithoutTransit = splitRatios.filterNot(_.queueType == Queues.Transfer)

      minutesForHours(flight.PcpTime, 1)
        .zip(paxDeparturesPerMinutes(totalPax.toInt, paxOffFlowRate))
        .flatMap {
          case (minuteMillis, flightPaxInMinute) =>
            splitsWithoutTransit
              .map(apiSplit => {
                flightSplitMinute(flight, procTimes, minuteMillis, flightPaxInMinute, apiSplit, Percentage, nationalityProcessingTimes)
              })
        }.toSet
    }).getOrElse(Set())
  }

  def flightSplitMinute(flight: Arrival,
                        procTimes: Map[PaxTypeAndQueue, Load],
                        minuteMillis: MillisSinceEpoch,
                        flightPaxInMinute: Int,
                        apiSplitRatio: ApiPaxTypeAndQueueCount,
                        splitStyle: SplitStyle,
                        nationalityProcessingTimes: Map[String, Double]): FlightSplitMinute = {
    val splitPaxInMinute = apiSplitRatio.paxCount * flightPaxInMinute
    val paxTypeQueueProcTime = procTimes(PaxTypeAndQueue(apiSplitRatio.passengerType, apiSplitRatio.queueType))
    val defaultWorkload = splitPaxInMinute * paxTypeQueueProcTime
    log.info(s"Looking at $apiSplitRatio")
    val splitWorkLoadInMinute = apiSplitRatio.nationalities match {
      case Some(nats) if nats.values.sum > 0 =>
        val totalNats = nats.values.sum
        val bestPax = ArrivalHelper.bestPax(flight)
        val natsToPaxRatio = totalNats.toDouble / bestPax
        val natFactor = (flightPaxInMinute.toDouble / bestPax) / natsToPaxRatio
        log.info(s"totalNats: $totalNats / bestPax: $bestPax, natFactor: $natFactor - ($flightPaxInMinute / $bestPax) / $natsToPaxRatio")
        log.info(s"Split nationality available. Looking up processing time")
        val natBasedWorkload = nats
          .map {
            case (nat, pax) => nationalityProcessingTimes.get(nat) match {
              case Some(procTime) =>
                val procTimeInMinutes = procTime / 60
                log.info(s"Using processing time for $pax $nat: $procTimeInMinutes (rather than $paxTypeQueueProcTime)")
                pax * procTimeInMinutes * natFactor
              case None =>
                log.info(s"Processing time for $nat not found. Using ${apiSplitRatio.passengerType} -> ${apiSplitRatio.queueType}: $paxTypeQueueProcTime")
                pax * paxTypeQueueProcTime
            }
          }
          .sum
        log.info(s"Nationality based workload: $natBasedWorkload vs $defaultWorkload default workload")
        natBasedWorkload
      case _ =>
        log.info(s"No nats available. Using general processing times")
        defaultWorkload
    }
    FlightSplitMinute(flight.uniqueId, apiSplitRatio.passengerType, flight.Terminal, apiSplitRatio.queueType, splitPaxInMinute, splitWorkLoadInMinute, minuteMillis)
  }
}
