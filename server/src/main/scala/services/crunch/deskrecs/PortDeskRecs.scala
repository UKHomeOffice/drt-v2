package services.crunch.deskrecs

import drt.shared.{AirportConfig, CrunchApi, Queues, TQM}
import drt.shared.CrunchApi.{DeskRecMinutes, MillisSinceEpoch}
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.Queues.{Queue, Transfer}
import drt.shared.Terminals.Terminal
import services.{SDate, TryCrunch}
import services.graphstages.Crunch.LoadMinute
import services.graphstages.WorkloadCalculator

import scala.collection.immutable.{Map, NumericRange}

trait PortDescRecsLike {
  val airportConfig: AirportConfig

  val minutesToCrunch: Int

  def flightsToDeskRecs(flights: FlightsWithSplits, crunchStartMillis: MillisSinceEpoch): CrunchApi.DeskRecMinutes
}

trait PortDeskRecs extends PortDescRecsLike {
  val tryCrunch: TryCrunch

  def queueLoadsFromFlights(flights: FlightsWithSplits): Map[TQM, LoadMinute] = WorkloadCalculator
    .flightLoadMinutes(flights, airportConfig.terminalProcessingTimes).minutes
    .groupBy {
      case (TQM(t, q, m), _) => val finalQueueName = airportConfig.divertedQueues.getOrElse(q, q)
        TQM(t, finalQueueName, m)
    }
    .map {
      case (tqm, mins) =>
        val loads = mins.values
        (tqm, LoadMinute(tqm.terminal, tqm.queue, loads.map(_.paxLoad).sum, loads.map(_.workLoad).sum, tqm.minute))
    }

  def terminalWorkLoadsByQueue(terminal: Terminal, minuteMillis: NumericRange[MillisSinceEpoch], loadMinutes: Map[TQM, LoadMinute]): Map[Queue, Seq[Double]] = airportConfig
    .queuesByTerminal(terminal)
    .filterNot(_ == Transfer)
    .map { queue =>
      val lms = minuteMillis.map(minute => loadMinutes.getOrElse(TQM(terminal, queue, minute), LoadMinute(terminal, queue, 0, 0, minute)).workLoad)
      (queue, lms)
    }
    .toMap

  def terminalPaxLoadsByQueue(terminal: Terminal, minuteMillis: NumericRange[MillisSinceEpoch], loadMinutes: Map[TQM, LoadMinute]): Map[Queue, Seq[Double]] = airportConfig
    .queuesByTerminal(terminal)
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
    val validTerminals = airportConfig.queuesByTerminal.keys.toList
    val terminalsToCrunch = terminals.filter(validTerminals.contains(_))

    val loadsWithDiverts = queueLoadsFromFlights(flights)

    val terminalQueueDeskRecs = terminalsToCrunch.map { terminal =>
      val terminalPax = terminalPaxLoadsByQueue(terminal, minuteMillis, loadsWithDiverts)
      val terminalWork = terminalWorkLoadsByQueue(terminal, minuteMillis, loadsWithDiverts)
      val deskRecsForTerminal: TerminalDeskRecsLike = terminalDescRecs(terminal)

      deskRecsForTerminal.terminalWorkToDeskRecs(terminal, minuteMillis, terminalPax, terminalWork, deskRecsForTerminal)
    }

    DeskRecMinutes(terminalQueueDeskRecs.toSeq.flatten)
  }

  def terminalDescRecs(terminal: Terminal): TerminalDeskRecsLike
}

case class FlexedPortDeskRecs(airportConfig: AirportConfig, minutesToCrunch: Int, tryCrunch: TryCrunch) extends PortDeskRecs {
  def terminalDescRecs(terminal: Terminal): TerminalDeskRecsLike =
    FlexedTerminal(airportConfig, airportConfig.desksByTerminal(terminal), airportConfig.flexedQueuesPriority, tryCrunch, airportConfig.eGateBankSize)
}

case class StaticPortDeskRecs(airportConfig: AirportConfig, minutesToCrunch: Int, tryCrunch: TryCrunch) extends PortDeskRecs {
  def terminalDescRecs(terminal: Terminal): TerminalDeskRecsLike =
    StaticTerminal(airportConfig, tryCrunch, airportConfig.eGateBankSize)
}
