//package services.crunch.deployments
//
//import drt.shared.CrunchApi.{DeploymentMinute, DeskRecMinute, MillisSinceEpoch}
//import drt.shared.Queues.{EGate, Queue}
//import drt.shared.TQM
//import drt.shared.Terminals.Terminal
//import org.slf4j.{Logger, LoggerFactory}
//import services.crunch.deskrecs.DeskRecs.desksForHourOfDayInUKLocalTime
//import services.{OptimizerConfig, OptimizerCrunchResult, TryCrunch}
//
//import scala.collection.immutable.{Map, NumericRange, SortedMap}
//import scala.util.{Failure, Success}
//
//trait TerminalDeploymentProviderLike {
//  val log: Logger = LoggerFactory.getLogger(getClass)
//
//  val queuesByTerminal: SortedMap[Terminal, Seq[Queue]]
//  val cruncher: TryCrunch
//  val bankSize: Int
//  val slas: Map[Queue, Int]
//
//  def deploymentsAndWaits(loads: Map[Queue, Seq[Double]],
//                          minDeploymentByMinute: Map[Queue, List[Int]],
//                          maxDeploymentByMinute: Map[Queue, List[Int]],
//                          availableStaffByMinute: List[Int]
//                         ): Map[Queue, (List[Int], List[Int])]
//
////  def staticDeploymentsAndWaits(loads: Map[Queue, Seq[Double]],
////                                minDesks: Map[Queue, List[Int]],
////                                maxDesks: Map[Queue, List[Int]]): Map[Queue, (List[Int], List[Int])] = loads
////    .map { case (queueProcessing, loadsForQueue) =>
////      log.info(s"Static optimising $queueProcessing")
////      val min = minDesks(queueProcessing)
////      val max = maxDesks(queueProcessing)
////      val sla = slas(queueProcessing)
////      cruncher(adjustedWork(queueProcessing, loadsForQueue), min, max, OptimizerConfig(sla)) match {
////        case Success(OptimizerCrunchResult(desks, waits)) => Option(queueProcessing -> ((desks.toList, waits.toList)))
////        case Failure(_) => None
////      }
////    }
////    .collect { case Some(result) => result }
////    .toMap
//
//  def adjustedWork(queue: Queue, work: Seq[Double]): Seq[Double] = queue match {
//    case EGate => work.map(_ / bankSize)
//    case _ => work
//  }
//
//  //  def minMaxDesksForQueue(deskRecMinutes: Iterable[MillisSinceEpoch], tn: Terminal, qn: Queue): (List[Int], List[Int]) = {
//  //    val defaultMinMaxDesks = (List.fill(24)(0), List.fill(24)(10))
//  //    val queueMinMaxDesks = minMaxDesks.getOrElse(tn, Map()).getOrElse(qn, defaultMinMaxDesks)
//  //    val minDesks = deskRecMinutes.map(desksForHourOfDayInUKLocalTime(_, queueMinMaxDesks._1))
//  //    val maxDesks = deskRecMinutes.map(desksForHourOfDayInUKLocalTime(_, queueMinMaxDesks._2))
//  //    (minDesks.toList, maxDesks.toList)
//  //  }
//
//  def terminalWorkToDeployments(terminal: Terminal,
//                                minuteMillis: NumericRange[MillisSinceEpoch],
//                                paxByQueue: Map[Queue, Seq[Double]],
//                                workByQueue: Map[Queue, Seq[Double]],
//                                minDeploymentByMinute: Map[Queue, List[Int]],
//                                maxDeploymentByMinute: Map[Queue, List[Int]],
//                                availableStaffByMinute: List[Int],
//                                queuePriority: List[Queue],
//                                deploymentProvider: TerminalDeploymentProviderLike): Iterable[DeploymentMinute] = {
//    //    val terminalMinMaxDesks = queuesByTerminal(terminal).map { queue =>
//    //      (queue, minMaxDesksForQueue(minuteMillis, terminal, queue))
//    //    }.toMap
//    //    val minDesks = terminalMinMaxDesks.mapValues(_._1)
//    //    val maxDesks = terminalMinMaxDesks.mapValues(_._2)
//
//    val queueDesksAndWaits = deploymentProvider.deploymentsAndWaits(workByQueue, minDeploymentByMinute, maxDeploymentByMinute, availableStaffByMinute)
//
//    queueDesksAndWaits.flatMap {
//      case (queue, (desks, waits)) =>
//        minuteMillis.zip(paxByQueue(queue).zip(workByQueue(queue))).zip(desks.zip(waits)).map {
//          case ((minute, (pax, work)), (desk, wait)) => DeploymentMinute(TQM(terminal, queue, minute), desk, wait)
//        }
//    }
//  }
//}
//
//case class FlexedTerminalDeploymentProvider(queuesByTerminal: SortedMap[Terminal, Seq[Queue]],
//                                            slas: Map[Queue, Int],
//                                            terminalDesks: Int,
//                                            queuePriority: List[Queue],
//                                            cruncher: TryCrunch,
//                                            bankSize: Int) extends TerminalDeploymentProviderLike {
//  override def deploymentsAndWaits(loads: Map[Queue, Seq[Double]],
//                                   minDeploymentByMinute: Map[Queue, List[Int]],
//                                   maxDeploymentByMinute: Map[Queue, List[Int]],
//                                   availableStaffByMinute: List[Int]): Map[Queue, (List[Int], List[Int])] = {
//    val queuesToOptimise: Set[Queue] = loads.keys.toSet
//    val flexedQueuesToOptimise = queuesToOptimise.filter(q => queuePriority.contains(q))
////    val staticQueuesToOptimise = queuesToOptimise.filter(q => !flexedQueuesPriority.contains(q))
//
//    val flexedRecs = flexedDesksAndWaits(flexedQueuesToOptimise, loads, minDeploymentByMinute, maxDeploymentByMinute, availableStaffByMinute, queuePriority)
//
////    val staticRecs = staticDeploymentsAndWaits(loads.filterKeys(staticQueuesToOptimise), minDesks, maxDesks)
//
//    flexedRecs //++ staticRecs
//  }
//
//  def flexedDesksAndWaits(flexedQueuesToOptimise: Set[Queue],
//                          loads: Map[Queue, Seq[Double]],
//                          minDeploymentByMinute: Map[Queue, List[Int]],
//                          maxDeploymentByMinute: Map[Queue, List[Int]],
//                          availableStaffByMinute: List[Int],
//                          flexedQueuesPriority: List[Queue]): Map[Queue, (List[Int], List[Int])] = flexedQueuesPriority
//    .filter(flexedQueued => flexedQueuesToOptimise.toList.contains(flexedQueued))
//    .foldLeft(Map[Queue, (List[Int], List[Int])]()) {
//      case (queueRecsSoFar, queueProcessing) =>
//        log.info(s"Flexed optimising $queueProcessing")
//        flexedQueueDesksAndWaits(terminalDesks, loads, minDeploymentByMinute, maxDeploymentByMinute, availableStaffByMinute, flexedQueuesToOptimise, queueRecsSoFar, queueProcessing)
//    }
//
//  def flexedQueueDesksAndWaits(terminalDesks: Int,
//                               loads: Map[Queue, Seq[Double]],
//                               minDeploymentByMinute: Map[Queue, List[Int]],
//                               maxDeploymentByMinute: Map[Queue, List[Int]],
//                               availableStaffByMinute: List[Int],
//                               flexedQueuesToOptimise: Set[Queue],
//                               queueRecsSoFar: Map[Queue, (List[Int], List[Int])],
//                               queueProcessing: Queue): Map[Queue, (List[Int], List[Int])] = {
//    val queuesProcessed = queueRecsSoFar.keys.toSet
//    val queuesToBeProcessed = flexedQueuesToOptimise -- (queuesProcessed + queueProcessing)
//    val availableMinusRemainingMinimums: List[Int] = queuesToBeProcessed.foldLeft(availableStaffByMinute) {
//      case (availableSoFar, queue) => availableSoFar.zip(minDeploymentByMinute(queue)).map { case (a, b) => a - b }
//    }
//    val actualAvailable: List[Int] = queueRecsSoFar.values
//      .foldLeft(availableMinusRemainingMinimums) {
//        case (availableSoFar, (recs, _)) => availableSoFar.zip(recs).map { case (a, b) => a - b }
//      }
//    val queueWork = adjustedWork(queueProcessing, loads(queueProcessing))
//    val queueMinDesks = minDeploymentByMinute(queueProcessing)
//    val queueSlas = slas(queueProcessing)
//    cruncher(queueWork, queueMinDesks, actualAvailable, OptimizerConfig(queueSlas)) match {
//      case Success(OptimizerCrunchResult(desks, waits)) => queueRecsSoFar + (queueProcessing -> ((desks.toList, waits.toList)))
//      case Failure(t) =>
//        log.error(s"Crunch failed for $queueProcessing", t)
//        queueRecsSoFar
//    }
//  }
//}
