package services.crunch.deskrecs

import drt.shared.CrunchApi.{DeskRecMinutes, MillisSinceEpoch}
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.Queues.{Queue, Transfer}
import drt.shared.Terminals.Terminal
import drt.shared.{PaxTypeAndQueue, TQM}
import org.slf4j.Logger
import services.TryCrunch
import services.crunch.desklimits.TerminalDeskLimitsLike
import services.graphstages.Crunch.LoadMinute
import services.graphstages.WorkloadCalculator

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

  override def flightsToLoads(flights: FlightsWithSplits,
                              crunchStartMillis: MillisSinceEpoch): Map[TQM, LoadMinute] = queueLoadsFromFlights(flights)

  override def loadsToDesks(minuteMillis: NumericRange[MillisSinceEpoch],
                            loads: Map[TQM, LoadMinute],
                            maxDesksByTerminal: Map[Terminal, TerminalDeskLimitsLike]): DeskRecMinutes = {
    val terminalQueueDeskRecs = maxDesksByTerminal
      .map { case (terminal, maxDesksProvider) =>
        val terminalPax = terminalPaxLoadsByQueue(terminal, minuteMillis, loads)
        val terminalWork = terminalWorkLoadsByQueue(terminal, minuteMillis, loads)
        log.info(s"Optimising $terminal")

        terminalDescRecs(terminal).workToDeskRecs(terminal, minuteMillis, terminalPax, terminalWork, maxDesksProvider)
      }

    DeskRecMinutes(terminalQueueDeskRecs.toSeq.flatten)
  }

  def terminalDescRecs(terminal: Terminal): TerminalDeskRecsProviderLike
}
