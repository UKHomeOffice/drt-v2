package services.graphstages

import akka.stream._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import drt.shared.CrunchApi._
import drt.shared.FlightsApi.{QueueName, TerminalName}
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import services.graphstages.Crunch._
import services.graphstages.StaffDeploymentCalculator.deploymentWithinBounds
import services.{SDate, _}

import scala.collection.immutable.{Map, NumericRange}
import scala.language.postfixOps


class SimulationGraphStage(name: String = "",
                           optionalInitialCrunchMinutes: Option[CrunchMinutes],
                           optionalInitialStaffMinutes: Option[StaffMinutes],
                           airportConfig: AirportConfig,
                           expireAfterMillis: MillisSinceEpoch,
                           now: () => SDateLike,
                           simulate: Simulator,
                           crunchPeriodStartMillis: SDateLike => SDateLike,
                           minutesToCrunch: Int)
  extends GraphStage[FanInShape2[Loads, StaffMinutes, SimulationMinutes]] {

  val inLoads: Inlet[Loads] = Inlet[Loads]("inLoads.in")
  val inStaffMinutes: Inlet[StaffMinutes] = Inlet[StaffMinutes]("inStaffMinutes.in")
  val outSimulationMinutes: Outlet[SimulationMinutes] = Outlet[SimulationMinutes]("outSimulationMinutes.out")

  override val shape = new FanInShape2[Loads, StaffMinutes, SimulationMinutes](inLoads, inStaffMinutes, outSimulationMinutes)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    var loadMinutes: Map[Int, LoadMinute] = Map()
    var staffMinutes: Map[Int, StaffMinute] = Map()
    var simulationMinutes: Map[Int, SimulationMinute] = Map()
    var simulationMinutesToPush: Map[Int, SimulationMinute] = Map()

    val log: Logger = LoggerFactory.getLogger(s"$getClass-$name")

    override def preStart(): Unit = {
      loadMinutes = optionalInitialCrunchMinutes match {
        case None => Map()
        case Some(CrunchMinutes(cms)) =>
          log.info(s"Received ${cms.size} initial crunch minutes")
          cms.map(cm => {
            val lm = LoadMinute(cm.terminalName, cm.queueName, cm.paxLoad, cm.workLoad, cm.minute)
            (lm.uniqueId, lm)
          }).toMap
      }

      staffMinutes = optionalInitialStaffMinutes match {
        case None => Map()
        case Some(StaffMinutes(sms)) =>
          log.info(s"Received ${sms.size} initial staff minutes")
          sms.map(sm => {
            (sm.key, sm)
          }).toMap
      }

      super.preStart()
    }

    setHandler(inLoads, new InHandler {
      override def onPush(): Unit = {
        val incomingLoads = grab(inLoads)
        log.info(s"Received ${incomingLoads.loadMinutes.size} loads")

        val updatedLoads: Map[Int, LoadMinute] = mergeLoads(incomingLoads.loadMinutes, loadMinutes)
        loadMinutes = purgeExpired(updatedLoads, (lm: LoadMinute) => lm.minute, now, expireAfterMillis)

        val allMinuteMillis = incomingLoads.loadMinutes.map(_.minute)
        val firstMinute = Crunch.getLocalLastMidnight(SDate(allMinuteMillis.min))
        val lastMinute = firstMinute.addMinutes(minutesToCrunch)
        updateSimulations(firstMinute, lastMinute, simulationMinutes.values.toSet, loadMinutes.values.toSet)

        pushStateIfReady()

        pullAll()
      }
    })

    setHandler(inStaffMinutes, new InHandler {
      override def onPush(): Unit = {
        val incomingStaffMinutes: StaffMinutes = grab(inStaffMinutes)

        log.info(s"Grabbed ${incomingStaffMinutes.minutes.length} staff minutes")

        staffMinutes = purgeExpired(updateStaffMinutes(staffMinutes, incomingStaffMinutes), (sm: StaffMinute) => sm.minute, now, expireAfterMillis)

        val firstMinute = SDate(incomingStaffMinutes.minutes.map(_.minute).min)
        val lastMinute = firstMinute.addDays(1)
        updateSimulations(firstMinute, lastMinute, simulationMinutes.values.toSet, loadMinutes.values.toSet)

        pushStateIfReady()

        pullAll()
      }
    })

    setHandler(outSimulationMinutes, new OutHandler {
      override def onPull(): Unit = {
        log.debug(s"outSimulationMinutes onPull called")
        pushStateIfReady()
        pullAll()
      }
    })

    def updateSimulations(firstMinute: SDateLike, lastMinute: SDateLike, existingSimulationMinutes: Set[SimulationMinute], loads: Set[LoadMinute]): Unit = {
      log.info(s"Simulation for ${firstMinute.toLocalDateTimeString()} - ${lastMinute.toLocalDateTimeString()}")

      val newSimulationMinutes: Set[SimulationMinute] = simulateLoads(firstMinute.millisSinceEpoch, lastMinute.millisSinceEpoch, loads)
      val newSimulationMinutesByKey = newSimulationMinutes.map(cm => (cm.key, cm)).toMap

      val diff = newSimulationMinutes -- existingSimulationMinutes

      simulationMinutes = purgeExpired(newSimulationMinutesByKey, (sm: SimulationMinute) => sm.minute, now, expireAfterMillis)

      val mergedSimulationMinutesToPush = mergeSimulationMinutes(diff, simulationMinutesToPush)
      simulationMinutesToPush = purgeExpired(mergedSimulationMinutesToPush, (sm: SimulationMinute) => sm.minute, now, expireAfterMillis)
      log.info(s"Now have ${simulationMinutesToPush.size} simulation minutes to push")
    }

    def updateStaffMinutes(existingStaffMinutes: Map[Int, StaffMinute], incomingStaffMinutes: StaffMinutes): Map[Int, StaffMinute] = incomingStaffMinutes
      .minutes
      .foldLeft(existingStaffMinutes) {
        case (soFar, sm) => soFar.updated(sm.key, sm)
      }

    def simulateLoads(firstMinute: MillisSinceEpoch, lastMinute: MillisSinceEpoch, loads: Set[LoadMinute]): Set[SimulationMinute] = {
      val workload = workloadForPeriod(firstMinute, lastMinute, loads)
      val deployed = deploymentsForMillis(firstMinute, lastMinute, workload)

      val minuteMillis = firstMinute until lastMinute by 60000

      val simulationMinutes = workload.flatMap {
        case (tn, terminalWorkload) => terminalWorkload.flatMap {
          case (qn, queueWorkload) =>
            log.info(s"Simulating $tn $qn")
            simulationForQueue(deployed, minuteMillis, tn, qn, queueWorkload)
        }
      }.toSet

      simulationMinutes
    }

    def simulationForQueue(deployed: Map[TerminalName, Map[String, Map[MillisSinceEpoch, Int]]], minuteMillis: NumericRange[MillisSinceEpoch], tn: TerminalName, qn: QueueName, queueWorkload: Map[MillisSinceEpoch, Double]): Set[SimulationMinute] = {
      val sla = airportConfig.slaByQueue.getOrElse(qn, 15)
      val adjustedWorkloadMinutes = if (qn == Queues.EGate) adjustWorkload(queueWorkload) else queueWorkload
      val fullWorkMinutes = minuteMillis.map(m => adjustedWorkloadMinutes.getOrElse(m, 0d))
      val queueDeployments = deployed.getOrElse(tn, Map()).getOrElse(qn, Map())
      val deployedDesks = minuteMillis.map(m => queueDeployments.getOrElse(m, 0))

      val waits: Seq[Int] = TryRenjin.runSimulationOfWork(fullWorkMinutes, deployedDesks, OptimizerConfig(sla))

      minuteMillis.zipWithIndex.map {
        case (minute, idx) => SimulationMinute(tn, qn, minute, deployedDesks(idx), waits(idx))
      }.toSet
    }

    def deploymentsForMillis(firstMinute: MillisSinceEpoch, lastMinute: MillisSinceEpoch, workload: Map[TerminalName, Map[QueueName, Map[MillisSinceEpoch, Double]]]): Map[TerminalName, Map[String, Map[MillisSinceEpoch, Int]]] = {
      val minuteMillis = firstMinute until lastMinute by 60000
      val availableStaff: Map[TerminalName, Map[MillisSinceEpoch, Int]] = availableStaffForPeriod(firstMinute, lastMinute)
      val minMaxDesks: Map[TerminalName, Map[QueueName, Map[MillisSinceEpoch, (Int, Int)]]] = minMaxDesksForMillis(minuteMillis)

      val deployments: Seq[(TerminalName, String, MillisSinceEpoch, Int)] = airportConfig.terminalNames
        .flatMap(tn => {
          minuteMillis
            .sliding(15, 15)
            .flatMap(slotMillis => {
              val queuesWithoutTransfer = airportConfig.queues(tn).filterNot(_ == Queues.Transfer)
              val queueWl = queuesWithoutTransfer.map(qn => {
                (qn, slotMillis.map(milli => {
                  val workloadForMilli = workload.getOrElse(tn, Map()).getOrElse(qn, Map()).getOrElse(milli, 0d)
                  val slaWeightedWorkload = workloadForMilli * (10d / Math.log(airportConfig.slaByQueue(qn)))
                  val adjustedForEgates = if (qn == Queues.EGate) slaWeightedWorkload / airportConfig.eGateBankSize else slaWeightedWorkload
                  adjustedForEgates
                }).sum)
              })
              val queueMm: Map[QueueName, (Int, Int)] = minMaxDesks.getOrElse(tn, Map()).mapValues(_.getOrElse(slotMillis.min, (0, 0)))
              val available: Int = availableStaff.getOrElse(tn, Map()).getOrElse(slotMillis.min, 0)
              val queuesAndDeployments = deployer(queueWl, available, queueMm)
              queuesAndDeployments.flatMap {
                case (qn, staff) => slotMillis.map(millis => (tn, qn, millis, staff))
              }
            })
        })

      val queueMinuteStaffByTerminal = deployments
        .groupBy {
          case (tn, _, _, _) => tn
        }

      queueMinuteStaffByTerminal.mapValues(qms => {
        val minuteStaffByQueue = qms.groupBy {
          case (_, qn, _, _) => qn
        }
        minuteStaffByQueue.mapValues(minuteStaff => {
          minuteStaff.map {
            case (_, _, m, s) => (m, s)
          }.toMap
        })
      })
    }

    def minMaxDesksForMillis(minuteMillis: Seq[Long]): Map[TerminalName, Map[QueueName, Map[MillisSinceEpoch, (Int, Int)]]] = airportConfig
      .minMaxDesksByTerminalQueue
      .mapValues(qmm => qmm.mapValues {
        case (minDesks, maxDesks) =>
          minuteMillis.map(m => {
            val min = desksForHourOfDayInUKLocalTime(m, minDesks)
            val max = desksForHourOfDayInUKLocalTime(m, maxDesks)
            (m, (min, max))
          }).toMap
      })

    def workloadForPeriod(firstMinute: MillisSinceEpoch, lastMinute: MillisSinceEpoch, loads: Set[LoadMinute]): Map[TerminalName, Map[QueueName, Map[MillisSinceEpoch, Double]]] = loads
      .filter(lm => firstMinute <= lm.minute && lm.minute < lastMinute)
      .groupBy(_.terminalName)
      .mapValues(tls => tls
        .groupBy(_.queueName)
        .mapValues(qlms => qlms.toSeq.map(lm => (lm.minute, lm.workLoad)).toMap))

    def adjustWorkload(workload: Map[MillisSinceEpoch, Double]): Map[MillisSinceEpoch, Double] = workload
      .mapValues(wl => adjustEgateWorkload(airportConfig.eGateBankSize, wl))

    def adjustEgateWorkload(eGateBankSize: Int, wl: Double): Double = wl / eGateBankSize

    def queueRecsToDeployments(round: Double => Int)
                              (queueRecs: Seq[(String, Double)], staffAvailable: Int, minMaxDesks: Map[String, (Int, Int)]): Seq[(String, Int)] = {
      val queueRecsCorrected = if (queueRecs.map(_._2).sum == 0) queueRecs.map(qr => (qr._1, 1d)) else queueRecs

      val totalStaffRec = queueRecsCorrected.map(_._2).sum

      queueRecsCorrected.foldLeft(List[(String, Int)]()) {
        case (agg, (queue, deskRec)) if agg.length < queueRecsCorrected.length - 1 =>
          val ideal = round(staffAvailable * (deskRec.toDouble / totalStaffRec))
          val totalRecommended = agg.map(_._2).sum
          val dr = deploymentWithinBounds(minMaxDesks.getOrElse(queue, (0, 10))._1, minMaxDesks.getOrElse(queue, (0, 10))._2, ideal, staffAvailable - totalRecommended)
          agg :+ Tuple2(queue, dr)
        case (agg, (queue, _)) =>
          val totalRecommended = agg.map(_._2).sum
          val ideal = staffAvailable - totalRecommended
          val dr = deploymentWithinBounds(minMaxDesks.getOrElse(queue, (0, 10))._1, minMaxDesks.getOrElse(queue, (0, 10))._2, ideal, staffAvailable - totalRecommended)
          agg :+ Tuple2(queue, dr)
      }
    }

    val deployer: (Seq[(String, Double)], Int, Map[String, (Int, Int)]) => Seq[(String, Int)] = queueRecsToDeployments(_.toInt)

    def minMaxDesksForQueue(simulationMinutes: Seq[MillisSinceEpoch], tn: TerminalName, qn: QueueName): (Seq[Int], Seq[Int]) = {
      val defaultMinMaxDesks = (Seq.fill(24)(0), Seq.fill(24)(10))
      val queueMinMaxDesks = airportConfig.minMaxDesksByTerminalQueue.getOrElse(tn, Map()).getOrElse(qn, defaultMinMaxDesks)
      val minDesks = simulationMinutes.map(desksForHourOfDayInUKLocalTime(_, queueMinMaxDesks._1))
      val maxDesks = simulationMinutes.map(desksForHourOfDayInUKLocalTime(_, queueMinMaxDesks._2))
      (minDesks, maxDesks)
    }

    def mergeSimulationMinutes(updatedCms: Set[SimulationMinute], existingCms: Map[Int, SimulationMinute]): Map[Int, SimulationMinute] = updatedCms.foldLeft(existingCms) {
      case (soFar, newLoadMinute) => soFar.updated(newLoadMinute.key, newLoadMinute)
    }

    def loadDiff(updatedLoads: Set[LoadMinute], existingLoads: Set[LoadMinute]): Set[LoadMinute] = {
      val loadDiff = updatedLoads -- existingLoads
      log.info(s"${loadDiff.size} updated load minutes")

      loadDiff
    }

    def mergeLoads(incomingLoads: Set[LoadMinute], existingLoads: Map[Int, LoadMinute]): Map[Int, LoadMinute] = incomingLoads.foldLeft(existingLoads) {
      case (soFar, load) =>
        soFar.updated(load.uniqueId, load)
    }

    def pullAll(): Unit = {
      if (!hasBeenPulled(inLoads)) {
        log.info(s"Pulling inFlightsWithSplits")
        pull(inLoads)
      }
      if (!hasBeenPulled(inStaffMinutes)) {
        log.info(s"Pulling inStaffMinutes")
        pull(inStaffMinutes)
      }
    }

    def pushStateIfReady(): Unit = {
      if (simulationMinutesToPush.isEmpty) log.info(s"We have no simulation minutes. Nothing to push")
      else if (isAvailable(outSimulationMinutes)) {
        log.info(s"Pushing ${simulationMinutesToPush.size} simulation minutes")
        push(outSimulationMinutes, SimulationMinutes(simulationMinutesToPush.values.toSet))
        simulationMinutesToPush = Map()
      } else log.info(s"outSimulationMinutes not available to push")
    }

    def availableStaffForPeriod(firstMinute: MillisSinceEpoch, lastMinute: MillisSinceEpoch): Map[TerminalName, Map[MillisSinceEpoch, Int]] = staffMinutes
      .values
      .filter(sm => firstMinute <= sm.minute && sm.minute < lastMinute)
      .groupBy(_.terminalName)
      .mapValues { sms =>
        sms.map(sm => (sm.minute, sm.availableAtPcp)).toMap
      }
  }
}
