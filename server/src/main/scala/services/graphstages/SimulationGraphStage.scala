package services.graphstages

import akka.stream._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import drt.shared.CrunchApi.{MillisSinceEpoch, _}
import drt.shared.FlightsApi.{QueueName, TerminalName}
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import services.graphstages.Crunch._
import services.graphstages.StaffDeploymentCalculator.deploymentWithinBounds
import services.{SDate, _}

import scala.collection.immutable
import scala.collection.immutable.{Map, NumericRange, SortedMap}
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}


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

  type TerminalLoad = Map[QueueName, Map[MillisSinceEpoch, Double]]
  type PortLoad = Map[TerminalName, TerminalLoad]

  val inLoads: Inlet[Loads] = Inlet[Loads]("inLoads.in")
  val inStaffMinutes: Inlet[StaffMinutes] = Inlet[StaffMinutes]("inStaffMinutes.in")
  val outSimulationMinutes: Outlet[SimulationMinutes] = Outlet[SimulationMinutes]("outSimulationMinutes.out")

  override val shape = new FanInShape2[Loads, StaffMinutes, SimulationMinutes](inLoads, inStaffMinutes, outSimulationMinutes)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    var loadMinutes: SortedMap[TQM, LoadMinute] = SortedMap()
    var staffMinutes: SortedMap[TM, StaffMinute] = SortedMap()
    var deployments: SortedMap[TQM, Int] = SortedMap()
    var allSimulationMinutes: SortedMap[TQM, SimulationMinute] = SortedMap()
    var simulationMinutesToPush: SortedMap[TQM, SimulationMinute] = SortedMap()

    val log: Logger = LoggerFactory.getLogger(s"$getClass-$name")

    override def preStart(): Unit = {
      loadMinutes = optionalInitialCrunchMinutes match {
        case None => SortedMap()
        case Some(CrunchMinutes(cms)) =>
          log.info(s"Received ${cms.size} initial crunch minutes")
          SortedMap[TQM, LoadMinute]() ++ cms.map(cm => {
            val lm = LoadMinute(cm.terminalName, cm.queueName, cm.paxLoad, cm.workLoad, cm.minute)
            (lm.uniqueId, lm)
          })
      }

      staffMinutes = optionalInitialStaffMinutes match {
        case None => SortedMap()
        case Some(StaffMinutes(sms)) =>
          log.info(s"Received ${sms.size} initial staff minutes")
          SortedMap[TM, StaffMinute]() ++ sms.map(sm => (sm.key, sm))
      }

      deployments = optionalInitialCrunchMinutes match {
        case None => SortedMap()
        case Some(CrunchMinutes(cms)) => SortedMap[TQM, Int]() ++ cms
          .groupBy(_.terminalName)
          .flatMap {
            case (tn: TerminalName, tCms) => tCms
              .groupBy(_.queueName)
              .flatMap {
                case (qn: QueueName, qCms: Set[CrunchMinute]) => qCms
                  .map(cm => (TQM(tn, qn, cm.minute), cm.deployedDesks.getOrElse(0)))
              }
          }
      }

      super.preStart()
    }

    setHandler(inLoads, new InHandler {
      override def onPush(): Unit = {
        val start = SDate.now()
        val incomingLoads = grab(inLoads)
        log.info(s"Received ${incomingLoads.loadMinutes.size} loads")

        val affectedTerminals = incomingLoads.loadMinutes.map { case (TQM(t, _, _), _) => t }.toSet.toSeq

        val updatedLoads = mergeLoads(incomingLoads.loadMinutes, loadMinutes)
        loadMinutes = purgeExpired(updatedLoads, now, expireAfterMillis.toInt)

        val allMinuteMillis = incomingLoads.loadMinutes.keys.map(_.minute)
        val firstMinute = crunchPeriodStartMillis(SDate(allMinuteMillis.min))
        val lastMinute = firstMinute.addMinutes(minutesToCrunch)

        terminalsWithNonZeroStaff(affectedTerminals, firstMinute, lastMinute) match {
          case affectedTerminalsWithStaff if affectedTerminalsWithStaff.isEmpty =>
            log.info(s"No affected terminals with deployments. Skipping simulations")
          case affectedTerminalsWithStaff =>
            val updatedDeployments = updateDeployments(affectedTerminalsWithStaff, firstMinute, lastMinute, deployments)
            deployments = Crunch.purgeExpired(updatedDeployments, now, expireAfterMillis.toInt)
            updateSimulationsForPeriod(firstMinute, lastMinute, affectedTerminalsWithStaff)
            pushStateIfReady()
        }

        pullAll()
        log.info(s"inLoads Took ${SDate.now().millisSinceEpoch - start.millisSinceEpoch}ms")
      }
    })

    setHandler(inStaffMinutes, new InHandler {
      override def onPush(): Unit = {
        val start = SDate.now()
        val incomingStaffMinutes: StaffMinutes = grab(inStaffMinutes)
        log.info(s"Grabbed ${incomingStaffMinutes.minutes.length} staff minutes")

        val affectedTerminals = incomingStaffMinutes.minutes.map(_.terminalName).distinct

        log.info(s"Staff updates affect ${affectedTerminals.mkString(", ")}")

        staffMinutes = purgeExpired(updateStaffMinutes(staffMinutes, incomingStaffMinutes), now, expireAfterMillis.toInt)

        log.info(s"Purged expired staff minutes")

        val firstMinute = crunchPeriodStartMillis(SDate(incomingStaffMinutes.minutes.map(_.minute).min))
        val lastMinute = firstMinute.addDays(1)

        log.info(s"Got first ${firstMinute.toLocalDateTimeString()} and last minutes ${lastMinute.toLocalDateTimeString()}")

        deployments = updateDeployments(affectedTerminals, firstMinute, lastMinute, deployments)

        log.info(s"Got deployments, updating simulations")
        updateSimulationsForPeriod(firstMinute, lastMinute, affectedTerminals)

        pushStateIfReady()

        pullAll()
        log.info(s"inStaffMinutes Took ${SDate.now().millisSinceEpoch - start.millisSinceEpoch}ms")
      }
    })

    def updateDeployments(affectedTerminals: Seq[TerminalName],
                          firstMinute: SDateLike,
                          lastMinute: SDateLike,
                          existingDeployments: SortedMap[TQM, Int]
                         ): SortedMap[TQM, Int] = {
      val firstMillis = firstMinute.millisSinceEpoch
      val lastMillis = lastMinute.millisSinceEpoch

      val deploymentUpdates = deploymentsForMillis(firstMillis, lastMillis, affectedTerminals)

      log.info(s"Merging updated deployments into existing")
      val updatedDeployments = deploymentUpdates.foldLeft(existingDeployments) {
        case (soFar, (tqm, staff)) => soFar.updated(tqm, staff)
      }

      updatedDeployments
    }

    setHandler(outSimulationMinutes, new OutHandler {
      override def onPull(): Unit = {
        val start = SDate.now()
        log.debug(s"outSimulationMinutes onPull called")
        pushStateIfReady()
        pullAll()
        log.info(s"outSimulationMinutes Took ${SDate.now().millisSinceEpoch - start.millisSinceEpoch}ms")
      }
    })

    def updateSimulationsForPeriod(firstMinute: SDateLike,
                                   lastMinute: SDateLike,
                                   terminalsToUpdate: Seq[TerminalName]
                                  ): Unit = {
      log.info(s"Simulation for ${firstMinute.toLocalDateTimeString()} - ${lastMinute.toLocalDateTimeString()} ${terminalsToUpdate.mkString(", ")}")

      val newSimulationsForPeriod = simulateLoads(firstMinute.millisSinceEpoch, lastMinute.millisSinceEpoch, terminalsToUpdate)

      val existingMinutes = forPeriod(firstMinute.millisSinceEpoch, lastMinute.millisSinceEpoch, terminalsToUpdate, allSimulationMinutes)

      val diff = newSimulationsForPeriod.foldLeft(SortedMap[TQM, SimulationMinute]()) {
        case (soFar, (tqm, newMinute)) =>
          existingMinutes.get(tqm) match {
            case Some(existing) if existing == newMinute => soFar
            case _ => soFar.updated(tqm, newMinute)
          }
      }

      val updatedSims = diff.foldLeft(allSimulationMinutes) {
        case (allSims, (tqm, sm)) => allSims.updated(tqm, sm)
      }

      allSimulationMinutes = purgeExpired(updatedSims, now, expireAfterMillis.toInt)

      val mergedSimulationMinutesToPush = mergeSimulationMinutes(diff, simulationMinutesToPush)
      simulationMinutesToPush = purgeExpired(mergedSimulationMinutesToPush, now, expireAfterMillis.toInt)
      log.info(s"Now have ${simulationMinutesToPush.size} simulation minutes to push")
    }

    def forPeriod[A](firstMinute: MillisSinceEpoch,
                     lastMinute: MillisSinceEpoch,
                     terminalsToUpdate: Seq[TerminalName],
                     itemsToFilter: SortedMap[TQM, A]): SortedMap[TQM, A] = {
      val tqmMinutes = for {
        minute <- firstMinute until lastMinute by 60000
        terminal <- terminalsToUpdate
        queue <- airportConfig.nonTransferQueues(terminal)
      } yield {
        val tqm = TQM(terminal, queue, minute)
        (tqm, itemsToFilter.get(tqm))
      }

      SortedMap[TQM, A]() ++ tqmMinutes.collect {
        case (tqm, Some(thing)) => (tqm, thing)
      }
    }

    def updateStaffMinutes(existingStaffMinutes: SortedMap[TM, StaffMinute], incomingStaffMinutes: StaffMinutes): SortedMap[TM, StaffMinute] = incomingStaffMinutes
      .minutes
      .foldLeft(existingStaffMinutes) {
        case (soFar, sm) => soFar.updated(sm.key, sm)
      }

    def simulateLoads(firstMinute: MillisSinceEpoch, lastMinute: MillisSinceEpoch, terminalsToUpdate: Seq[TerminalName]): SortedMap[TQM, SimulationMinute] = {
      val workload = workloadForPeriod(firstMinute, lastMinute, terminalsToUpdate)
      val minuteMillis = firstMinute until lastMinute by 60000

      SortedMap[TQM, SimulationMinute]() ++ terminalsToUpdate.flatMap { tn =>
        airportConfig.nonTransferQueues(tn).flatMap { qn =>
          val queueWorkload = minuteMillis.map(m => workload.getOrElse(TQM(tn, qn, m), 0d))
          simulationForQueue(minuteMillis, tn, qn, queueWorkload)
        }
      }
    }

    def simulationForQueue(minuteMillis: NumericRange[MillisSinceEpoch], tn: TerminalName, qn: QueueName, queueWorkload: IndexedSeq[Double]): SortedMap[TQM, SimulationMinute] = {
      val sla = airportConfig.slaByQueue.getOrElse(qn, 15)
      val adjustedWorkloads = if (qn == Queues.EGate) adjustEgatesWorkload(queueWorkload) else queueWorkload

      minuteMillis.map(m => deployments.getOrElse(TQM(tn, qn, m), 0)) match {
        case deployedStaff if deployedStaff.sum == 0 =>
          log.info(s"No deployed staff. Skipping simulations")
          SortedMap()
        case deployedStaff =>
          log.info(s"Running $tn, $qn simulation with ${adjustedWorkloads.length} workloads & ${deployedStaff.length} desks")
          runSimulation(minuteMillis, tn, qn, sla, adjustedWorkloads, deployedStaff)
      }
    }

    def deploymentsForMillis(firstMinute: MillisSinceEpoch, lastMinute: MillisSinceEpoch, terminalsToUpdate: Seq[TerminalName]): SortedMap[TQM, Int] = {
      val workload = workloadForPeriod(firstMinute, lastMinute, terminalsToUpdate)

      val minuteMillis = firstMinute until lastMinute by 60000
      val availableStaff: Map[TerminalName, Map[MillisSinceEpoch, Int]] = availableStaffForPeriod(firstMinute, lastMinute, terminalsToUpdate)
      val minMaxDesks: Map[TerminalName, Map[QueueName, Map[MillisSinceEpoch, (Int, Int)]]] = minMaxDesksForMillis(minuteMillis)

      SortedMap[TQM, Int]() ++ terminalsToUpdate
        .flatMap(tn => {
          val queues = airportConfig.nonTransferQueues(tn)
          val terminalWorkloads = queues.map { qn =>
            (qn, minuteMillis.map(m => (m, workload.getOrElse(TQM(tn, qn, m), 0d))).toMap)
          }.toMap

          deploymentsByQueueAndSlot(minuteMillis, minMaxDesks.getOrElse(tn, Map()), tn, terminalWorkloads, queues, availableStaff.getOrElse(tn, Map()))
        })
    }

    def deploymentsByQueueAndSlot(minuteMillis: NumericRange[MillisSinceEpoch],
                                  minMaxDesks: Map[QueueName, Map[MillisSinceEpoch, (Int, Int)]],
                                  tn: TerminalName,
                                  terminalWorkloads: Map[QueueName, Map[MillisSinceEpoch, Double]],
                                  queues: Seq[QueueName],
                                  availableStaff: Map[MillisSinceEpoch, Int]): SortedMap[TQM, Int] = SortedMap[TQM, Int]() ++ minuteMillis
      .sliding(15, 15)
      .flatMap(slotMillis => {
        val queueDeps = availableStaff.getOrElse(slotMillis.min, 0) match {
          case 0 => zeroQueueDeployments(tn)
          case availableForSlot => queueDeployments(availableForSlot, minMaxDesks, tn, queues, terminalWorkloads, slotMillis)
        }
        queueDeps.flatMap { case (qn, staff) => slotMillis.map(millis => (TQM(tn, qn, millis), staff)) }
      })

    def queueDeployments(available: Int,
                         minMaxDesks: Map[QueueName, Map[MillisSinceEpoch, (Int, Int)]],
                         terminalName: TerminalName,
                         queues: Seq[String],
                         terminalWorkloads: Map[QueueName, Map[MillisSinceEpoch, Double]],
                         slotMillis: immutable.IndexedSeq[MillisSinceEpoch]
                        ): Seq[(String, Int)] = {
      val queuesWithoutTransfer = airportConfig.nonTransferQueues(terminalName)
      val queueWl = slaWeightedLoadByQueue(queuesWithoutTransfer, terminalWorkloads, slotMillis)
      val slotStartMilli = slotMillis.min
      val queueMm = minMaxDesks.mapValues(_.getOrElse(slotStartMilli, (0, 0)))

      deployer(queueWl, available, queueMm)
    }

    def slaWeightedLoadByQueue(queuesWithoutTransfer: Seq[QueueName], terminalWorkloads: TerminalLoad, slotMillis: IndexedSeq[Long]): Seq[(QueueName, Double)] = queuesWithoutTransfer
      .map(qn => {
        val queueWorkloads = terminalWorkloads.getOrElse(qn, Map())
        val slaWeight = Math.log(airportConfig.slaByQueue(qn))
        (qn, slotMillis.map(milli => {
          val workloadForMilli = queueWorkloads.getOrElse(milli, 0d)
          val slaWeightedWorkload = workloadForMilli * (10d / slaWeight)
          val adjustedForEgates = if (qn == Queues.EGate) slaWeightedWorkload / airportConfig.eGateBankSize else slaWeightedWorkload
          adjustedForEgates
        }).sum)
      })

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

    def workloadForPeriod(firstMinute: MillisSinceEpoch, lastMinute: MillisSinceEpoch, terminalsToUpdate: Seq[TerminalName]): SortedMap[TQM, Double] =
      forPeriod(firstMinute, lastMinute, terminalsToUpdate, loadMinutes).mapValues {
        case LoadMinute(_, _, _, workLoad, _) => workLoad
      }

    def filterTerminalMinutes[A <: TerminalMinute](firstMinute: MillisSinceEpoch, lastMinute: MillisSinceEpoch, terminalsToUpdate: Seq[TerminalName], toFilter: Map[TM, A]): Seq[A] = {
      val maybeThings = for {
        terminalName <- terminalsToUpdate
        minute <- firstMinute until lastMinute by oneMinuteMillis
      } yield toFilter.get(MinuteHelper.key(terminalName, minute))

      maybeThings.collect { case Some(thing) => thing }
    }

    def adjustEgatesWorkload(workload: IndexedSeq[Double]): IndexedSeq[Double] = workload
      .map(wl => adjustEgateWorkload(airportConfig.eGateBankSize, wl))

    def adjustEgateWorkload(eGateBankSize: Int, wl: Double): Double = wl / eGateBankSize

    var deploymentCache: Map[Int, Seq[(String, Int)]] = Map()

    @scala.annotation.tailrec
    def addOneStaffToQueueAtIndex(deployments: List[(String, Int, Int)], index: Int, numberOfQueues: Int, staffAvailable: Int): List[(String, Int, Int)] = {
      val safeIndex = if (index > numberOfQueues - 1) 0 else index
      val deployedStaff = deployments.map(_._2).sum
      val maxStaff = deployments.map(_._3).sum
      val freeStaff = staffAvailable - deployedStaff

      if (deployedStaff != maxStaff && freeStaff > 0) {
        val (queue, staffDeployments, maxStaff) = deployments(safeIndex)
        val newDeployments = if (staffDeployments < maxStaff) deployments.updated(safeIndex, Tuple3(queue, staffDeployments + 1, maxStaff)) else deployments
        addOneStaffToQueueAtIndex(newDeployments, safeIndex + 1, numberOfQueues, staffAvailable)
      } else {
        deployments
      }
    }

    def queueRecsToDeployments(round: Double => Int)
                              (queueRecs: Seq[(String, Double)], staffAvailable: Int, minMaxDesks: Map[String, (Int, Int)]): Seq[(String, Int)] = {
      val key = (queueRecs, staffAvailable, minMaxDesks).hashCode()

      deploymentCache.get(key) match {
        case Some(deps) => deps
        case None =>
          val queueRecsCorrected = if (queueRecs.map(_._2).sum == 0) queueRecs.map(qr => (qr._1, 1d)) else queueRecs

          val totalStaffRec = queueRecsCorrected.map(_._2).sum

          val deployments = queueRecsCorrected.foldLeft(List[(String, Int, Int)]()) {
            case (agg, (queue, deskRec)) if agg.length < queueRecsCorrected.length - 1 =>
              val ideal = round(staffAvailable * (deskRec.toDouble / totalStaffRec))
              val totalRecommended = agg.map(_._2).sum
              val maxStaff = minMaxDesks.getOrElse(queue, (0, 10))._2
              val staffDeployments = deploymentWithinBounds(minMaxDesks.getOrElse(queue, (0, 10))._1, maxStaff, ideal, staffAvailable - totalRecommended)
              agg :+ Tuple3(queue, staffDeployments, maxStaff)
            case (agg, (queue, _)) =>
              val totalRecommended = agg.map(_._2).sum
              val ideal = staffAvailable - totalRecommended
              val maxStaff = minMaxDesks.getOrElse(queue, (0, 10))._2
              val staffDeployments = deploymentWithinBounds(minMaxDesks.getOrElse(queue, (0, 10))._1, maxStaff, ideal, staffAvailable - totalRecommended)
              agg :+ Tuple3(queue, staffDeployments, maxStaff)
          }
          val newDeployments = addOneStaffToQueueAtIndex(deployments, index = 0, queueRecsCorrected.length, staffAvailable)
          newDeployments.map(tuple3 => Tuple2(tuple3._1, tuple3._2))
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

    def mergeSimulationMinutes(updatedCms: SortedMap[TQM, SimulationMinute], existingCms: SortedMap[TQM, SimulationMinute]): SortedMap[TQM, SimulationMinute] =
      updatedCms.foldLeft(existingCms) {
        case (soFar, (tqm, newLoadMinute)) => soFar.updated(tqm, newLoadMinute)
      }

    def loadDiff(updatedLoads: Set[LoadMinute], existingLoads: Set[LoadMinute]): Set[LoadMinute] = {
      val loadDiff = updatedLoads -- existingLoads
      log.info(s"${loadDiff.size} updated load minutes")

      loadDiff
    }

    def mergeLoads(incomingLoads: SortedMap[TQM, LoadMinute], existingLoads: SortedMap[TQM, LoadMinute]): SortedMap[TQM, LoadMinute] =
      incomingLoads.foldLeft(existingLoads) {
        case (soFar, (tqm, load)) => soFar.updated(tqm, load)
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
        push(outSimulationMinutes, SimulationMinutes(simulationMinutesToPush.values.toSeq))
        simulationMinutesToPush = SortedMap()
      } else log.info(s"outSimulationMinutes not available to push")
    }

    def availableStaffForPeriod(firstMinute: MillisSinceEpoch, lastMinute: MillisSinceEpoch, terminalNames: Seq[TerminalName]): Map[TerminalName, Map[MillisSinceEpoch, Int]] =
      filterTerminalMinutes(firstMinute, lastMinute, terminalNames, staffMinutes)
        .groupBy(_.terminalName)
        .mapValues { sms =>
          sms.map(sm => (sm.minute, sm.availableAtPcp)).toMap
        }

    def terminalsWithNonZeroStaff(allTerminals: Seq[TerminalName], firstMinute: SDateLike, lastMinute: SDateLike): Seq[TerminalName] = {
      availableStaffForPeriod(firstMinute.millisSinceEpoch, lastMinute.millisSinceEpoch, allTerminals)
        .foldLeft(List[TerminalName]()) {
          case (nonZeroTerminals, (terminal, staffByMillis)) =>
            if (staffByMillis.count(_._2 > 0) > 0) terminal :: nonZeroTerminals
            else nonZeroTerminals
        }
    }
  }

  def runSimulation(minuteMillis: NumericRange[MillisSinceEpoch], tn: TerminalName, qn: QueueName, sla: Int, fullWorkMinutes: IndexedSeq[Double], deployedStaff: IndexedSeq[Int]): SortedMap[TQM, SimulationMinute] = {
    Try(simulate(fullWorkMinutes, deployedStaff, OptimizerConfig(sla))) match {
      case Success(waits) =>
        SortedMap[TQM, SimulationMinute]() ++ minuteMillis.zipWithIndex.map {
          case (minute, idx) => (TQM(tn, qn, minute), SimulationMinute(tn, qn, minute, deployedStaff(idx), waits(idx)))
        }
      case Failure(t) =>
        val start = SDate(minuteMillis.min).toLocalDateTimeString()
        val end = SDate(minuteMillis.max).toLocalDateTimeString()
        log.error(s"Failed to run simulations for $tn / $qn - $start -> $end: $t")
        SortedMap[TQM, SimulationMinute]()
    }
  }

  val zeroQueueDeployments: Map[TerminalName, Seq[(QueueName, Int)]] = airportConfig
    .terminalNames
    .map(tn => (tn, airportConfig.nonTransferQueues(tn).map(qn => (qn, 0))))
    .toMap
}

case class SimulationMinute(terminalName: TerminalName,
                            queueName: QueueName,
                            minute: MillisSinceEpoch,
                            desks: Int,
                            waitTime: Int) extends SimulationMinuteLike {
  lazy val key: TQM = MinuteHelper.key(terminalName, queueName, minute)
}

case class SimulationMinutes(minutes: Seq[SimulationMinute]) extends PortStateMinutes {
  def applyTo(maybePortState: Option[PortState], now: MillisSinceEpoch): (PortState, PortStateDiff) = {
    val portState = maybePortState match {
      case None => PortState.empty
      case Some(ps) => ps
    }

    val minutesDiff = minutes.foldLeft(List[(TQM, CrunchMinute)]()) {
      case (updatesSoFar, updatedCm) =>
        val maybeMinute: Option[CrunchMinute] = portState.crunchMinutes.get(updatedCm.key)
        val mergedCm: CrunchMinute = mergeMinute(maybeMinute, updatedCm, now)
        (mergedCm.key, mergedCm) :: updatesSoFar
    }

    val newPortState = portState.copy(crunchMinutes = portState.crunchMinutes ++ minutesDiff.toMap)
    val newDiff = PortStateDiff(Seq(), Map[Int, ApiFlightWithSplits](), minutesDiff.toMap, Map[TM, StaffMinute]())
    
    (newPortState, newDiff)
  }

  def newCrunchMinutes: SortedMap[TQM, CrunchMinute] = SortedMap[TQM, CrunchMinute]() ++ minutes
    .map(CrunchMinute(_))
    .map(cm => (cm.key, cm))

  def mergeMinute(maybeMinute: Option[CrunchMinute], updatedSm: SimulationMinute, now: MillisSinceEpoch): CrunchMinute = maybeMinute
    .map(existingCm => existingCm.copy(
      deployedDesks = Option(updatedSm.desks),
      deployedWait = Option(updatedSm.waitTime)
    ))
    .getOrElse(CrunchMinute(updatedSm))
    .copy(lastUpdated = Option(now))
}
