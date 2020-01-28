package services.crunch.deskrecs

import drt.shared.CrunchApi.{DeskRecMinutes, MillisSinceEpoch}
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.Queues.{Queue, Transfer}
import drt.shared.Terminals.Terminal
import drt.shared.{AirportConfig, CrunchApi, PaxTypeAndQueue, TQM}
import org.slf4j.{Logger, LoggerFactory}
import services.graphstages.Crunch.LoadMinute
import services.graphstages.WorkloadCalculator
import services.{SDate, TryCrunch}

import scala.collection.immutable.{Map, NumericRange, SortedMap}

trait ProductionPortDeskRecsProviderLike extends PortDeskRecsProviderLike {
  val log: Logger
  val queuesByTerminal: SortedMap[Terminal, Seq[Queue]]
  val terminalProcessingTimes: Map[Terminal, Map[PaxTypeAndQueue, Double]]
  val divertedQueues: Map[Queue, Queue]

  val tryCrunch: TryCrunch

  def queueLoadsFromFlights(flights: FlightsWithSplits): Map[TQM, LoadMinute] = WorkloadCalculator
    .flightLoadMinutes(flights, terminalProcessingTimes).minutes
    .groupBy {
      case (TQM(t, q, m), _) => val finalQueueName = divertedQueues.getOrElse(q, q)
        TQM(t, finalQueueName, m)
    }
    .map {
      case (tqm, minutes) =>
        val loads = minutes.values
        (tqm, LoadMinute(tqm.terminal, tqm.queue, loads.map(_.paxLoad).sum, loads.map(_.workLoad).sum, tqm.minute))
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

  def flightsToDeskRecs(flights: FlightsWithSplits, crunchStartMillis: MillisSinceEpoch): CrunchApi.DeskRecMinutes = {
    val crunchEndMillis = SDate(crunchStartMillis).addMinutes(minutesToCrunch).millisSinceEpoch
    val minuteMillis = crunchStartMillis until crunchEndMillis by 60000

    val terminals = flights.flightsToUpdate.map(_.apiFlight.Terminal).toSet
    val validTerminals = queuesByTerminal.keys.toList
    val terminalsToCrunch = terminals.filter(validTerminals.contains(_))

    val loadsWithDiverts = queueLoadsFromFlights(flights)

    val terminalQueueDeskRecs = terminalsToCrunch.map { terminal =>
      val terminalPax = terminalPaxLoadsByQueue(terminal, minuteMillis, loadsWithDiverts)
      val terminalWork = terminalWorkLoadsByQueue(terminal, minuteMillis, loadsWithDiverts)
      val deskRecsForTerminal: TerminalDeskRecsProviderLike = terminalDescRecs(terminal)
      log.info(s"Optimising $terminal")

      deskRecsForTerminal.terminalWorkToDeskRecs(terminal, minuteMillis, terminalPax, terminalWork, deskRecsForTerminal)
    }

    DeskRecMinutes(terminalQueueDeskRecs.toSeq.flatten)
  }

  def terminalDescRecs(terminal: Terminal): TerminalDeskRecsProviderLike
}

case class FlexedPortDeskRecsProvider(queuesByTerminal: SortedMap[Terminal, Seq[Queue]],
                                      divertedQueues: Map[Queue, Queue],
                                      minMaxDesks: Map[Terminal, Map[Queue, (List[Int], List[Int])]],
                                      desksByTerminal: Map[Terminal, Int],
                                      flexedQueuesPriority: List[Queue],
                                      slas: Map[Queue, Int],
                                      terminalProcessingTimes: Map[Terminal, Map[PaxTypeAndQueue, Double]],
                                      minutesToCrunch: Int,
                                      crunchOffsetMinutes: Int,
                                      eGateBankSize: Int,
                                      tryCrunch: TryCrunch) extends ProductionPortDeskRecsProviderLike {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def terminalDescRecs(terminal: Terminal): TerminalDeskRecsProviderLike =
    FlexedTerminalDeskRecsProvider(queuesByTerminal, minMaxDesks, slas, desksByTerminal(terminal), flexedQueuesPriority, tryCrunch, eGateBankSize)
}

object FlexedPortDeskRecsProvider {
  def apply(airportConfig: AirportConfig, minutesToCrunch: Int, tryCrunch: TryCrunch): FlexedPortDeskRecsProvider =
    FlexedPortDeskRecsProvider(airportConfig.queuesByTerminal,
                               airportConfig.divertedQueues,
                               airportConfig.minMaxDesksByTerminalQueue,
                               airportConfig.desksByTerminal,
                               airportConfig.flexedQueuesPriority,
                               airportConfig.slaByQueue,
                               airportConfig.terminalProcessingTimes,
                               minutesToCrunch,
                               airportConfig.crunchOffsetMinutes,
                               airportConfig.eGateBankSize,
                               tryCrunch)
}

case class StaticPortDeskRecsProvider(queuesByTerminal: SortedMap[Terminal, Seq[Queue]],
                                      divertedQueues: Map[Queue, Queue],
                                      minMaxDesks: Map[Terminal, Map[Queue, (List[Int], List[Int])]],
                                      slas: Map[Queue, Int],
                                      terminalProcessingTimes: Map[Terminal, Map[PaxTypeAndQueue, Double]],
                                      minutesToCrunch: Int,
                                      crunchOffsetMinutes: Int,
                                      eGateBankSize: Int,
                                      tryCrunch: TryCrunch) extends ProductionPortDeskRecsProviderLike {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def terminalDescRecs(terminal: Terminal): TerminalDeskRecsProviderLike =
    StaticTerminalDeskRecsProvider(queuesByTerminal, minMaxDesks, slas, tryCrunch, eGateBankSize)
}

object StaticPortDeskRecsProvider {
  def apply(airportConfig: AirportConfig, minutesToCrunch: Int, tryCrunch: TryCrunch): StaticPortDeskRecsProvider =
    StaticPortDeskRecsProvider(airportConfig.queuesByTerminal,
                               airportConfig.divertedQueues,
                               airportConfig.minMaxDesksByTerminalQueue,
                               airportConfig.slaByQueue,
                               airportConfig.terminalProcessingTimes,
                               minutesToCrunch,
                               airportConfig.crunchOffsetMinutes,
                               airportConfig.eGateBankSize,
                               tryCrunch)
}
