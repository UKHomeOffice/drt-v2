//package services.crunch.deployments
//
//import drt.shared.CrunchApi.{DeploymentMinute, DeskRecMinutes, MillisSinceEpoch}
//import drt.shared.FlightsApi.FlightsWithSplits
//import drt.shared.Queues.{Queue, Transfer}
//import drt.shared.Terminals.Terminal
//import drt.shared.{AirportConfig, CrunchApi, PaxTypeAndQueue, TQM}
//import org.slf4j.{Logger, LoggerFactory}
//import services.graphstages.Crunch.LoadMinute
//import services.graphstages.WorkloadCalculator
//import services.{SDate, TryCrunch}
//
//import scala.collection.immutable.{Map, NumericRange, SortedMap}
//
//trait ProductionPortDeploymentProviderLike extends PortDeploymentProviderLike {
//  val log: Logger
//  val queuesByTerminal: SortedMap[Terminal, Seq[Queue]]
//  val terminalProcessingTimes: Map[Terminal, Map[PaxTypeAndQueue, Double]]
//  val divertedQueues: Map[Queue, Queue]
//
//  val tryCrunch: TryCrunch
//
//  def queueLoadsFromFlights(flights: FlightsWithSplits): Map[TQM, LoadMinute] = WorkloadCalculator
//    .flightLoadMinutes(flights, terminalProcessingTimes).minutes
//    .groupBy {
//      case (TQM(t, q, m), _) => val finalQueueName = divertedQueues.getOrElse(q, q)
//        TQM(t, finalQueueName, m)
//    }
//    .map {
//      case (tqm, minutes) =>
//        val loads = minutes.values
//        (tqm, LoadMinute(tqm.terminal, tqm.queue, loads.map(_.paxLoad).sum, loads.map(_.workLoad).sum, tqm.minute))
//    }
//
//  def terminalWorkLoadsByQueue(terminal: Terminal,
//                               minuteMillis: NumericRange[MillisSinceEpoch],
//                               loadMinutes: Map[TQM, LoadMinute]): Map[Queue, Seq[Double]] = queuesByTerminal(terminal)
//    .filterNot(_ == Transfer)
//    .map { queue =>
//      val lms = minuteMillis.map(minute => loadMinutes.getOrElse(TQM(terminal, queue, minute), LoadMinute(terminal, queue, 0, 0, minute)).workLoad)
//      (queue, lms)
//    }
//    .toMap
//
//  def terminalPaxLoadsByQueue(terminal: Terminal, minuteMillis: NumericRange[MillisSinceEpoch],
//                              loadMinutes: Map[TQM, LoadMinute]): Map[Queue, Seq[Double]] = queuesByTerminal(terminal)
//    .filterNot(_ == Transfer)
//    .map { queue =>
//      val paxLoads = minuteMillis.map(minute => loadMinutes.getOrElse(TQM(terminal, queue, minute), LoadMinute(terminal, queue, 0, 0, minute)).paxLoad)
//      (queue, paxLoads)
//    }
//    .toMap
//
//  def flightsToDeployments(flights: FlightsWithSplits,
//                           crunchStartMillis: MillisSinceEpoch,
//                           minDeploymentByMillis: Map[Queue, List[Int]],
//                           maxDeploymentByMillis: Map[Queue, List[Int]],
//                           queuePriority: List[Queue]): Seq[DeploymentMinute] = {
//    val crunchEndMillis = SDate(crunchStartMillis).addMinutes(minutesToCrunch).millisSinceEpoch
//    val minuteMillis = crunchStartMillis until crunchEndMillis by 60000
//
//    val terminals = flights.flightsToUpdate.map(_.apiFlight.Terminal).toSet
//    val validTerminals = queuesByTerminal.keys.toList
//    val terminalsToCrunch = terminals.filter(validTerminals.contains(_))
//
//    val loadsWithDiverts = queueLoadsFromFlights(flights)
//
//    val terminalQueueDeployments = terminalsToCrunch.map { terminal =>
//      val terminalPax = terminalPaxLoadsByQueue(terminal, minuteMillis, loadsWithDiverts)
//      val terminalWork = terminalWorkLoadsByQueue(terminal, minuteMillis, loadsWithDiverts)
//      val deploymentsForTerminal: TerminalDeploymentProviderLike = terminalDescRecs(terminal)
//      log.info(s"Optimising $terminal")
//
//      deploymentsForTerminal.terminalWorkToDeployments(terminal, minuteMillis, terminalPax, terminalWork, minDeploymentByMillis, maxDeploymentByMillis, queuePriority, deploymentsForTerminal)
//    }
//
//    terminalQueueDeployments.toSeq.flatten
//  }
//
//  def terminalDescRecs(terminal: Terminal): TerminalDeploymentProviderLike
//}
//
//case class FlexedPortDeploymentProvider(queuesByTerminal: SortedMap[Terminal, Seq[Queue]],
//                                        divertedQueues: Map[Queue, Queue],
//                                        desksByTerminal: Map[Terminal, Int],
//                                        queuePriority: List[Queue],
//                                        slas: Map[Queue, Int],
//                                        terminalProcessingTimes: Map[Terminal, Map[PaxTypeAndQueue, Double]],
//                                        minutesToCrunch: Int,
//                                        crunchOffsetMinutes: Int,
//                                        eGateBankSize: Int,
//                                        tryCrunch: TryCrunch) extends ProductionPortDeploymentProviderLike {
//  val log: Logger = LoggerFactory.getLogger(getClass)
//
//  def terminalDescRecs(terminal: Terminal): TerminalDeploymentProviderLike =
//    FlexedTerminalDeploymentProvider(queuesByTerminal, slas, desksByTerminal(terminal), queuePriority, tryCrunch, eGateBankSize)
//}
//
//object FlexedPortDeploymentProvider {
//  def apply(airportConfig: AirportConfig, minutesToCrunch: Int, tryCrunch: TryCrunch): FlexedPortDeploymentProvider =
//    FlexedPortDeploymentProvider(airportConfig.queuesByTerminal,
//                                 airportConfig.divertedQueues,
//                                 airportConfig.desksByTerminal,
//                                 airportConfig.flexedQueuesPriority,
//                                 airportConfig.slaByQueue,
//                                 airportConfig.terminalProcessingTimes,
//                                 minutesToCrunch,
//                                 airportConfig.crunchOffsetMinutes,
//                                 airportConfig.eGateBankSize,
//                                 tryCrunch)
//}
//
//case class StaticPortDeploymentProvider(queuesByTerminal: SortedMap[Terminal, Seq[Queue]],
//                                        divertedQueues: Map[Queue, Queue],
//                                        minMaxDesks: Map[Terminal, Map[Queue, (List[Int], List[Int])]],
//                                        slas: Map[Queue, Int],
//                                        terminalProcessingTimes: Map[Terminal, Map[PaxTypeAndQueue, Double]],
//                                        minutesToCrunch: Int,
//                                        crunchOffsetMinutes: Int,
//                                        eGateBankSize: Int,
//                                        tryCrunch: TryCrunch) extends ProductionPortDeploymentProviderLike {
//  val log: Logger = LoggerFactory.getLogger(getClass)
//
//  def terminalDescRecs(terminal: Terminal): TerminalDeploymentProviderLike =
//    StaticTerminalDeploymentProvider(queuesByTerminal, minMaxDesks, slas, tryCrunch, eGateBankSize)
//}
//
//object StaticPortDeploymentProvider {
//  def apply(airportConfig: AirportConfig, minutesToCrunch: Int, tryCrunch: TryCrunch): StaticPortDeploymentProvider =
//    StaticPortDeploymentProvider(airportConfig.queuesByTerminal,
//                                 airportConfig.divertedQueues,
//                                 airportConfig.minMaxDesksByTerminalQueue,
//                                 airportConfig.slaByQueue,
//                                 airportConfig.terminalProcessingTimes,
//                                 minutesToCrunch,
//                                 airportConfig.crunchOffsetMinutes,
//                                 airportConfig.eGateBankSize,
//                                 tryCrunch)
//}
