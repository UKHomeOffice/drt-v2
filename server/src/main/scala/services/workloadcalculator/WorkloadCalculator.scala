package services.workloadcalculator

import com.typesafe.config.{Config, ConfigFactory}
import controllers.SystemActors.SplitsProvider
import drt.shared.FlightsApi.{QueueName, QueuePaxAndWorkLoads, TerminalName, TerminalQueuePaxAndWorkLoads}
import drt.shared.SplitRatiosNs.SplitRatios
import drt.shared.{Arrival, _}
import org.slf4j.LoggerFactory
import services.PcpArrival.{pcpFrom, walkTimeMillisProviderFromCsv}
import services.{SDate, SplitsProvider}
import services.workloadcalculator.PaxLoadCalculator.{MillisSinceEpoch, PaxTypeAndQueueCount, ProcTime, voyagePaxSplitsFlowOverTime}

import scala.collection.immutable.{List, _}
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global


trait WorkloadCalculator {
  private val log = LoggerFactory.getLogger(getClass)

  def procTimesProvider(terminalName: TerminalName)(paxTypeAndQueue: PaxTypeAndQueue): Double

  def flightPaxTypeAndQueueCountsFlow(flight: Arrival): IndexedSeq[(MillisSinceEpoch, PaxTypeAndQueueCount)]

  type FlightsToQueueLoads[L] = (
    (Arrival) => IndexedSeq[(MillisSinceEpoch, PaxTypeAndQueueCount)],
      (PaxTypeAndQueue) => ProcTime
    ) => (List[Arrival]) => Map[QueueName, L]

  def queueLoadsByTerminal[L](flights: Future[List[Arrival]], flightsToQueueLoads: FlightsToQueueLoads[L]): Future[TerminalQueuePaxAndWorkLoads[L]] = {
    val flightsByTerminalFut: Future[Map[TerminalName, List[Arrival]]] = flights.map(fs => {
      val flightsByTerminal = fs.filterNot(freightOrEngineering).groupBy(_.Terminal)
      flightsByTerminal
    })


    val workloadByTerminal: Future[Map[TerminalName, Map[QueueName, L]]] = flightsByTerminalFut.map((flightsByTerminal: Map[TerminalName, List[Arrival]]) =>
      flightsByTerminal.map((fbt: (TerminalName, List[Arrival])) => {
        log.debug(s"Got flights by terminal ${fbt}")
        val terminalName = fbt._1
        val flights = fbt._2
        val queueLoadsForFlights = flightsToQueueLoads(flightPaxTypeAndQueueCountsFlow, procTimesProvider(terminalName))
        terminalName -> queueLoadsForFlights(flights)
      }))

    workloadByTerminal
  }

  def freightOrEngineering(flight: Arrival): Boolean = Set("FRT", "ENG").contains(flight.Terminal)
}

object PassengerQueueTypes {
  type FlightCode = String
}

object PaxLoadCalculator {
  val log = LoggerFactory.getLogger(getClass)
  val paxOffFlowRate = 20
  val oneMinute = 60000L
  type MillisSinceEpoch = Long
  type Load = Double
  type ProcTime = Double

  case class PaxTypeAndQueueCount(paxAndQueueType: PaxTypeAndQueue, paxSum: Load)

  def calcAndSumPaxAndWorkLoads(procTimeProvider: (PaxTypeAndQueue) => ProcTime, paxTypeAndQueueCounts: List[PaxTypeAndQueueCount]): (Load, Load) = {
    val paxSum = paxTypeAndQueueCounts.map(_.paxSum).sum
    val workloadSum = paxTypeAndQueueCounts.map(ptQc => procTimeProvider(ptQc.paxAndQueueType) * ptQc.paxSum).sum
    (paxSum, workloadSum)
  }

  def queueLoadCalculator(calcPaxTypeAndQueueCountForAFlightOverTime: (Arrival) => IndexedSeq[(MillisSinceEpoch, PaxTypeAndQueueCount)],
                          calcAndSumLoads: List[PaxTypeAndQueueCount] => Load)
                         (flights: List[Arrival]): Map[QueueName, List[(MillisSinceEpoch, Load)]] = {
    val flightsPaxSplits = flights.flatMap(calcPaxTypeAndQueueCountForAFlightOverTime)
    val flightsPaxSplitsByQueueAndMinute = flightsPaxSplits.groupBy(t => (t._2.paxAndQueueType.queueType, t._1))

    val loadsByQueueAndMinute: Map[(QueueName, MillisSinceEpoch), Load] = flightsPaxSplitsByQueueAndMinute
      .mapValues { tmPtQcs => calcAndSumLoads(tmPtQcs.map(_._2)) }

    val workLoadsByQueue: List[(QueueName, (MillisSinceEpoch, Load))] = loadsByQueueAndMinute.toList.map {
      case ((queueName, time), workSum) => (queueName, (time, workSum))
    }

    workLoadsByQueue.groupBy(_._1).mapValues(extractMillisAndLoadSortedByMillis(_))
  }

  def extractMillisAndLoadSortedByMillis(queueNameMillisLoad: List[(QueueName, (MillisSinceEpoch, Load))]): List[(MillisSinceEpoch, Load)] = {
    queueNameMillisLoad.map(_._2).sortBy(_._1)
  }

  def queueWorkAndPaxLoadCalculator(calcPaxTypeAndQueueCountForAFlightOverTime: (Arrival) => IndexedSeq[(MillisSinceEpoch, PaxTypeAndQueueCount)]
                                    , procTimeProvider: (PaxTypeAndQueue) => ProcTime)(flights: List[Arrival]): Map[QueueName, QueuePaxAndWorkLoads] = {
    val queuePaxLoads = queueLoadCalculator(calcPaxTypeAndQueueCountForAFlightOverTime, calcAndSumPaxLoads)(flights)
    val queueWorkLoads = queueLoadCalculator(calcPaxTypeAndQueueCountForAFlightOverTime, calcAndSumWorkLoads(procTimeProvider))(flights)
    queuePaxLoads.keys.map(queueName => {
      (queueName, (
        queueWorkLoads(queueName).map(tuple => WL(tuple._1, tuple._2)),
        queuePaxLoads(queueName).map(tuple => Pax(tuple._1, tuple._2))))
    }).toMap
  }

  def queueWorkLoadCalculator(calcPaxTypeAndQueueCountForAFlightOverTime: (Arrival) => IndexedSeq[(MillisSinceEpoch, PaxTypeAndQueueCount)]
                              , procTimeProvider: (PaxTypeAndQueue) => ProcTime)(flights: List[Arrival]): Map[QueueName, Seq[WL]] = {
    queueLoadCalculator(calcPaxTypeAndQueueCountForAFlightOverTime, calcAndSumWorkLoads(procTimeProvider))(flights)
      .mapValues(millisAndLoadsToWorkLoads(ml => WL(ml._1, ml._2)))
  }

  def millisAndLoadsToWorkLoads[B <: Time](constructLoad: ((MillisSinceEpoch, Load)) => B): (List[(MillisSinceEpoch, Load)]) => List[B] = {
    x => x.map(constructLoad)
  }

  def queuePaxLoadCalculator(calcPaxTypeAndQueueCountForAFlightOverTime: (Arrival) => IndexedSeq[(MillisSinceEpoch, PaxTypeAndQueueCount)]
                             , procTimeProvider: (PaxTypeAndQueue) => ProcTime)(flights: List[Arrival]): Map[QueueName, Seq[Pax]] = {
    queueLoadCalculator(calcPaxTypeAndQueueCountForAFlightOverTime, calcAndSumPaxLoads)(flights)
      .mapValues(millisAndLoadsToWorkLoads(ml => Pax(ml._1, ml._2)))
  }

  def calcAndSumPaxLoads(paxTypeAndQueueCounts: List[PaxTypeAndQueueCount]): Load = {
    paxTypeAndQueueCounts.map(_.paxSum).sum
  }

  def calcAndSumWorkLoads(procTimeProvider: (PaxTypeAndQueue) => ProcTime)(paxTypeAndQueueCounts: List[PaxTypeAndQueueCount]): Load = {
    paxTypeAndQueueCounts.map(ptQc => procTimeProvider(ptQc.paxAndQueueType) * ptQc.paxSum).sum
  }

  def flightPaxFlowProvider(splitRatioProvider: (Arrival) => Option[SplitRatios],
                            bestPax: (Arrival) => Int): (Arrival) => IndexedSeq[(MillisSinceEpoch, PaxTypeAndQueueCount)] = {
    voyagePaxSplitsFlowOverTime(splitRatioProvider, bestPax)
  }

  def voyagePaxSplitsFlowOverTime(splitsRatioProvider: (Arrival) => Option[SplitRatios], bestPax: (Arrival) => Int)
                                 (flight: Arrival): IndexedSeq[(MillisSinceEpoch, PaxTypeAndQueueCount)] = {
    val pcpStartTimeMillis = MilliDate(flight.PcpTime).millisSinceEpoch
    //TODO fix this get
    val splits = splitsRatioProvider(flight).get.splits
    val splitsOverTime: IndexedSeq[(MillisSinceEpoch, PaxTypeAndQueueCount)] = minutesForHours(pcpStartTimeMillis, 1)
      .zip(paxDeparturesPerMinutes(bestPax(flight), paxOffFlowRate))
      .flatMap {
        case (m, paxInMinute) =>
          splits.map(splitRatio => (m, PaxTypeAndQueueCount(splitRatio.paxType, splitRatio.ratio * paxInMinute)))
      }

    splitsOverTime
  }

  def minutesForHours(timesMin: MillisSinceEpoch, hours: Int) = timesMin until (timesMin + oneMinute * 60 * hours) by oneMinute

  def paxDeparturesPerMinutes(remainingPax: Int, departRate: Int): List[Int] = {
    if (remainingPax % departRate != 0)
      List.fill(remainingPax / departRate)(departRate) ::: remainingPax % departRate :: Nil
    else
      List.fill(remainingPax / departRate)(departRate)
  }
}
