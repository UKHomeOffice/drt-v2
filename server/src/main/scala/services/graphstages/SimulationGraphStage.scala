package services.graphstages

import akka.stream._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import drt.shared.CrunchApi._
import drt.shared.FlightsApi.{QueueName, TerminalName}
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import services.graphstages.Crunch._
import services.graphstages.StaffDeploymentCalculator.deploymentWithinBounds
import services.{OptimizerConfig, OptimizerCrunchResult, SDate, TryRenjin}

import scala.collection.immutable.Map
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}


class SimulationGraphStage(optionalInitialCrunchMinutes: Option[CrunchMinutes],
                           airportConfig: AirportConfig,
                           expireAfterMillis: MillisSinceEpoch,
                           now: () => SDateLike,
                           crunch: (Seq[Double], Seq[Int], Seq[Int], OptimizerConfig) => Try[OptimizerCrunchResult])
  extends GraphStage[FlowShape[Loads, SimulationMinutes]] {

  val inLoads: Inlet[Loads] = Inlet[Loads]("inLoads.in")
  val outSimulationMinutes: Outlet[SimulationMinutes] = Outlet[SimulationMinutes]("outSimulationMinutes.out")

  override val shape = new FlowShape(inLoads, outSimulationMinutes)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    var loadMinutes: Map[Int, LoadMinute] = Map()
    var existingSimulationMinutes: Map[Int, SimulationMinute] = Map()
    var simulationMinutesToPush: Map[Int, SimulationMinute] = Map()

    val log: Logger = LoggerFactory.getLogger(getClass)

    override def preStart(): Unit = {
      loadMinutes = optionalInitialCrunchMinutes match {
        case None => Map()
        case Some(CrunchMinutes(cms)) => cms.map(cm => {
          val lm = LoadMinute(cm.terminalName, cm.queueName, cm.paxLoad, cm.workLoad, cm.minute)
          (lm.uniqueId, lm)
        }).toMap
      }

      super.preStart()
    }

    setHandler(inLoads, new InHandler {
      override def onPush(): Unit = {
        val incomingLoads = grab(inLoads)
        log.info(s"Received ${incomingLoads.loadMinutes.size} loads")

        val allMinuteMillis = incomingLoads.loadMinutes.map(_.minute)
        val firstMinute = Crunch.getLocalLastMidnight(SDate(allMinuteMillis.min))
        val lastMinute = Crunch.getLocalNextMidnight(SDate(allMinuteMillis.max))
        log.info(s"Crunch ${firstMinute.toLocalDateTimeString()} - ${lastMinute.toLocalDateTimeString()}")

        val updatedLoads: Map[Int, LoadMinute] = mergeLoads(incomingLoads.loadMinutes, loadMinutes)
        loadMinutes = Crunch.purgeExpired(updatedLoads, (lm: LoadMinute) => lm.minute, now, expireAfterMillis)

        val simulationMinutes: Set[SimulationMinute] = crunchLoads(firstMinute.millisSinceEpoch, lastMinute.millisSinceEpoch, loadMinutes.values.toSet)
        val simulationMinutesByKey = simulationMinutes.map(cm => (cm.key, cm)).toMap

        val diff = simulationMinutes -- existingSimulationMinutes.values.toSet

        existingSimulationMinutes = Crunch.purgeExpired(simulationMinutesByKey, (cm: SimulationMinute) => cm.minute, now, expireAfterMillis)

        val mergedSimulationMinutes = mergeSimulationMinutes(diff, simulationMinutesToPush)
        simulationMinutesToPush = purgeExpired(mergedSimulationMinutes, (cm: SimulationMinute) => cm.minute, now, expireAfterMillis)
        log.info(s"Now have ${simulationMinutesToPush.size} load minutes to push")

        pushStateIfReady()

        pullLoads()
      }
    })

    def crunchLoads(firstMinute: MillisSinceEpoch, lastMinute: MillisSinceEpoch, loads: Set[LoadMinute]): Set[SimulationMinute] = {
      loads
        .filter(lm => firstMinute <= lm.minute && lm.minute < lastMinute)
        .groupBy(_.terminalName)
        .flatMap {
          case (tn, tLms) => tLms
            .groupBy(_.queueName)
            .flatMap {
              case (qn, qLms) =>
                log.info(s"Crunching $tn $qn")
                val sla = airportConfig.slaByQueue.getOrElse(qn, 15)
                val sortedLms = qLms.toSeq.sortBy(_.minute)
                val workMinutes: Map[MillisSinceEpoch, Double] = sortedLms.map(m => (m.minute, m.workLoad)).toMap
                val adjustedWorkloadMinutes = if (qn != Queues.EGate)
                  workMinutes
                else
                  workMinutes.mapValues(wl => adjustEgateWorkload(airportConfig.eGateBankSize, wl))
                val minuteMillis = firstMinute until lastMinute by 60000
                val fullWorkMinutes = minuteMillis.map(m => adjustedWorkloadMinutes.getOrElse(m, 0d))
//                val (minDesks, maxDesks) = minMaxDesksForQueue(minuteMillis, tn, qn)

//                val minWlSd = cms.map(cm => Tuple3(cm.minute, cm.workLoad, cm.deployedDesks))
//                val workLoads = qLms.map {
//                  case lm => lm.copy(workLoad = adjustEgateWorkload(airportConfig.eGateBankSize, lm.workLoad))
//                  case (_, wl, _) => wl
//                }

                val workloadAndQueueNames: Seq[(QueueName, Double)] = qLms.map(lm => (lm.queueName, lm.workLoad)).toSeq.sortBy(_._1)
                val available = optionalStaffSources.map(_.available(minute, tn)).getOrElse(0)

                val deploymentsAndQueueNames: Map[String, Int] = deployer(workloadAndQueueNames, available, minMaxByQueue).toMap

                val deployedDesks = minWlSd.map { case (_, _, sd) => sd.getOrElse(0) }

                val waits: Seq[Int] = TryRenjin.runSimulationOfWork(fullWorkMinutes, deployedDesks, OptimizerConfig(sla))

                minuteMillis.zipWithIndex.map {
                  case (minute, idx) =>
                    val waitTime = waits(idx)
                    SimulationMinute(tn, qn, minute, 0, waitTime)
                }
            }
        }.toSet
    }

    def adjustEgateWorkload(eGateBankSize: Int, wl: Double): Double = {
      wl / eGateBankSize
    }

    def queueRecsToDeployments(round: Double => Int)
                              (queueRecs: Seq[(String, Int)], staffAvailable: Int, minMaxDesks: Map[String, (Int, Int)]): Seq[(String, Int)] = {
      val queueRecsCorrected = if (queueRecs.map(_._2).sum == 0) queueRecs.map(qr => (qr._1, 1)) else queueRecs

      val totalStaffRec = queueRecsCorrected.map(_._2).sum

      queueRecsCorrected.foldLeft(List[(String, Int)]()) {
        case (agg, (queue, deskRec)) if agg.length < queueRecsCorrected.length - 1 =>
          val ideal = round(staffAvailable * (deskRec.toDouble / totalStaffRec))
          val totalRecommended = agg.map(_._2).sum
          val dr = deploymentWithinBounds(minMaxDesks(queue)._1, minMaxDesks(queue)._2, ideal, staffAvailable - totalRecommended)
          agg :+ Tuple2(queue, dr)
        case (agg, (queue, _)) =>
          val totalRecommended = agg.map(_._2).sum
          val ideal = staffAvailable - totalRecommended
          val dr = deploymentWithinBounds(minMaxDesks(queue)._1, minMaxDesks(queue)._2, ideal, staffAvailable - totalRecommended)
          agg :+ Tuple2(queue, dr)
      }
    }

    val deployer: (Seq[(String, Int)], Int, Map[String, (Int, Int)]) => Seq[(String, Int)] = queueRecsToDeployments(_.toInt)

    def minMaxDesksForQueue(simulationMinutes: Seq[MillisSinceEpoch], tn: TerminalName, qn: QueueName): (Seq[Int], Seq[Int]) = {
      val defaultMinMaxDesks = (Seq.fill(24)(0), Seq.fill(24)(10))
      val queueMinMaxDesks = airportConfig.minMaxDesksByTerminalQueue.getOrElse(tn, Map()).getOrElse(qn, defaultMinMaxDesks)
      val minDesks = simulationMinutes.map(desksForHourOfDayInUKLocalTime(_, queueMinMaxDesks._1))
      val maxDesks = simulationMinutes.map(desksForHourOfDayInUKLocalTime(_, queueMinMaxDesks._2))
      (minDesks, maxDesks)
    }

    def mergeSimulationMinutes(updatedCms: Set[SimulationMinute], existingCms: Map[Int, SimulationMinute]): Map[Int, SimulationMinute] = {
      updatedCms.foldLeft(existingCms) {
        case (soFar, newLoadMinute) => soFar.updated(newLoadMinute.key, newLoadMinute)
      }
    }

    def loadDiff(updatedLoads: Set[LoadMinute], existingLoads: Set[LoadMinute]): Set[LoadMinute] = {
      val loadDiff = updatedLoads -- existingLoads
      log.info(s"${loadDiff.size} updated load minutes")

      loadDiff
    }

    def mergeLoads(incomingLoads: Set[LoadMinute], existingLoads: Map[Int, LoadMinute]): Map[Int, LoadMinute] = {
      incomingLoads.foldLeft(existingLoads) {
        case (soFar, load) =>
          soFar.updated(load.uniqueId, load)
      }
    }

    setHandler(outSimulationMinutes, new OutHandler {
      override def onPull(): Unit = {
        log.debug(s"outLoads onPull called")
        pushStateIfReady()
        pullLoads()
      }
    })

    def pullLoads(): Unit = {
      if (!hasBeenPulled(inLoads)) {
        log.info(s"Pulling inFlightsWithSplits")
        pull(inLoads)
      }
    }

    def pushStateIfReady(): Unit = {
      if (simulationMinutesToPush.isEmpty) log.info(s"We have no crunch minutes. Nothing to push")
      else if (isAvailable(outSimulationMinutes)) {
        log.info(s"Pushing ${simulationMinutesToPush.size} crunch minutes")
        push(outSimulationMinutes, SimulationMinutes(simulationMinutesToPush.values.toSet))
        simulationMinutesToPush = Map()
      } else log.info(s"outSimulationMinutes not available to push")
    }
  }
}
