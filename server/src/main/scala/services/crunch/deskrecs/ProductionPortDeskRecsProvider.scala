package services.crunch.deskrecs

import drt.shared.CrunchApi.{DeskRecMinutes, MillisSinceEpoch}
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.Queues.{Queue, Transfer}
import drt.shared.Terminals.Terminal
import drt.shared.{AirportConfig, CrunchApi, PaxTypeAndQueue, TQM}
import org.slf4j.{Logger, LoggerFactory}
import services.crunch.deskrecs.StaffProviders.MaxDesksProvider
import services.graphstages.Crunch.LoadMinute
import services.graphstages.WorkloadCalculator
import services.{SDate, TryCrunch}

import scala.collection.immutable.{Map, NumericRange, SortedMap}

object StaffProviders {
  type AvailableStaffProvider = List[Int] => MaxDesksProvider
  type MaxDesksProvider = (List[Int], Set[Queue], Map[Queue, List[Int]], Map[Queue, (List[Int], List[Int])], Queue) => List[Int]

  def maxStaff: AvailableStaffProvider =
    (totalStaff: List[Int]) =>
      (terminalDesks: List[Int], flexedQueues: Set[Queue], minDesks: Map[Queue, List[Int]], queueRecsSoFar: Map[Queue, (List[Int], List[Int])], queueProcessing: Queue) => {
        val deployedByQueue = queueRecsSoFar.values.map(_._1).toList
        val totalDeployed = if (deployedByQueue.nonEmpty) deployedByQueue.reduce(listOp[Int](_ + _)) else List()
        val processedQueues = queueRecsSoFar.keys.toSet
        val remainingFlexedQueues = flexedQueues -- (processedQueues + queueProcessing)
        val minDesksForRemainingFlexedQueues: List[List[Int]] = minDesks.filterKeys(remainingFlexedQueues.contains).values.toList
        val availableDesks = (terminalDesks :: totalDeployed :: minDesksForRemainingFlexedQueues).reduce(listOp[Int](_ - _))

        val remainingQueues = minDesks.keys.toSet -- (processedQueues + queueProcessing)
        val minDesksForRemainingQueues = minDesks.filterKeys(remainingQueues.contains).values.toList
        val minimumPromisedStaff = if (minDesksForRemainingQueues.nonEmpty) minDesksForRemainingQueues.reduce(listOp[Int](_ + _)) else List()
        val availableStaff = List(totalStaff, totalDeployed, minimumPromisedStaff).reduce(listOp[Int](_ - _))

        List(availableDesks, availableStaff).reduce(listOp[Int](Math.min))
      }

  def maxDesks: MaxDesksProvider =
    (terminalDesks: List[Int], flexedQueues: Set[Queue], minDesks: Map[Queue, List[Int]], queueRecsSoFar: Map[Queue, (List[Int], List[Int])], queueProcessing: Queue) => {
      val deployedByQueue = queueRecsSoFar.values.map(_._1).toList
      val totalDeployed = if (deployedByQueue.nonEmpty) deployedByQueue.reduce(listOp[Int](_ + _)) else List()
      val processedQueues = queueRecsSoFar.keys.toSet
      val remainingFlexedQueues = flexedQueues -- (processedQueues + queueProcessing)
      val remainingMinDesks = minDesks.filterKeys(remainingFlexedQueues.contains).values.toList

      (terminalDesks :: totalDeployed :: remainingMinDesks).reduce(listOp[Int](_ - _))
    }

  def listOp[A](op: (A, A) => A)(v1: List[A], v2: List[A]): List[A] = (v1, v2) match {
    case (Nil, b) => b
    case (a, Nil) => a
    case (a, b) => a.zip(b).map {
      case (a, b) => op(a, b)
    }
  }

  def maxDesksForTerminal: Terminal => MaxDesksProvider = (_: Terminal) => StaffProviders.maxDesks

  def maxStaffForTerminal: Map[Terminal, List[Int]] => Terminal => MaxDesksProvider =
    (availableStaff: Map[Terminal, List[Int]]) => (terminal: Terminal) => StaffProviders.maxStaff(availableStaff(terminal))
}

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

  override def flightsToDeskRecs(flights: FlightsWithSplits,
                                 crunchStartMillis: MillisSinceEpoch,
                                 maxDesksProvider: Terminal => MaxDesksProvider): CrunchApi.DeskRecMinutes = {
    val crunchEndMillis = SDate(crunchStartMillis).addMinutes(minutesToCrunch).millisSinceEpoch
    val minuteMillis = crunchStartMillis until crunchEndMillis by 60000

    val terminals = flights.flightsToUpdate.map(_.apiFlight.Terminal).toSet
    val validTerminals = queuesByTerminal.keys.toList
    val terminalsToCrunch = terminals.filter(validTerminals.contains(_))

    val loadsWithDiverts = queueLoadsFromFlights(flights)

    loadsToDesks(maxDesksProvider, minuteMillis, terminalsToCrunch, loadsWithDiverts)
  }

  def loadsToDesks(maxDesksProvider: Terminal => MaxDesksProvider,
                   minuteMillis: NumericRange[MillisSinceEpoch],
                   terminalsToCrunch: Set[Terminal],
                   loadsWithDiverts: Map[TQM, LoadMinute]): DeskRecMinutes = {
    val terminalQueueDeskRecs = terminalsToCrunch.map { terminal =>
      val terminalPax = terminalPaxLoadsByQueue(terminal, minuteMillis, loadsWithDiverts)
      val terminalWork = terminalWorkLoadsByQueue(terminal, minuteMillis, loadsWithDiverts)
      log.info(s"Optimising $terminal")

      val maxDesksForQueue = maxDesksProvider(terminal)

      terminalDescRecs(terminal).workToDeskRecs(terminal, minuteMillis, terminalPax, terminalWork, maxDesksForQueue)
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
