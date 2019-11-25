package services.graphstages

import akka.stream._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import drt.shared.CrunchApi.{MillisSinceEpoch, _}
import drt.shared.Queues.Queue
import drt.shared.Terminals.Terminal
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import services.graphstages.Crunch._
import services.graphstages.StaffDeploymentCalculator.deploymentWithinBounds
import services.metrics.{Metrics, StageTimer}
import services.{SDate, _}

import scala.collection.immutable.{Map, NumericRange, SortedMap}
import scala.collection.{immutable, mutable}
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

  type TerminalLoad = Map[Queue, Map[MillisSinceEpoch, Double]]
  type PortLoad = Map[Terminal, TerminalLoad]

  val inLoads: Inlet[Loads] = Inlet[Loads]("Loads.in")
  val inStaffMinutes: Inlet[StaffMinutes] = Inlet[StaffMinutes]("StaffMinutes.in")
  val outSimulationMinutes: Outlet[SimulationMinutes] = Outlet[SimulationMinutes]("SimulationMinutes.out")
  val stageName = "simulation"

  override val shape = new FanInShape2[Loads, StaffMinutes, SimulationMinutes](inLoads, inStaffMinutes, outSimulationMinutes)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    val loadMinutes: mutable.SortedMap[TQM, LoadMinute] = mutable.SortedMap()
    val staffMinutes: mutable.SortedMap[TM, StaffMinute] = mutable.SortedMap()
    val deployments: mutable.SortedMap[TQM, Int] = mutable.SortedMap()
    val allSimulationMinutes: mutable.SortedMap[TQM, SimulationMinute] = mutable.SortedMap()
    val simulationMinutesToPush: mutable.SortedMap[TQM, SimulationMinute] = mutable.SortedMap()

    val log: Logger = LoggerFactory.getLogger(s"$getClass-$name")

    override def preStart(): Unit = {
      val initialMinutesCount = optionalInitialCrunchMinutes.getOrElse(CrunchMinutes(Set())).crunchMinutes.size
      log.info(s"Received $initialMinutesCount initial crunch minutes")

      optionalInitialStaffMinutes.foreach(_.minutes.foreach(sm => staffMinutes += (sm.key -> sm)))

      optionalInitialCrunchMinutes.foreach(_.crunchMinutes.foreach { cm =>
        val lm = LoadMinute(cm.terminal, cm.queue, cm.paxLoad, cm.workLoad, cm.minute)
        loadMinutes += (lm.uniqueId -> lm)
        deployments += (TQM(cm.terminal, cm.queue, cm.minute) -> cm.deployedDesks.getOrElse(0))
      })

      super.preStart()
    }

    setHandler(inLoads, new InHandler {
      override def onPush(): Unit = {
        val timer = StageTimer(stageName, inLoads)
        val incomingLoads = grab(inLoads)
        log.debug(s"Received ${incomingLoads.loadMinutes.size} loads")

        val affectedTerminals = incomingLoads.loadMinutes.map { case (TQM(t, _, _), _) => t }.toSet.toSeq

        loadMinutes ++= incomingLoads.loadMinutes
        purgeExpired(loadMinutes, TQM.atTime, now, expireAfterMillis.toInt)

        val allMinuteMillis = incomingLoads.loadMinutes.keys.map(_.minute)
        val firstMinute = crunchPeriodStartMillis(SDate(allMinuteMillis.min))
        val lastMinute = firstMinute.addMinutes(minutesToCrunch)

        terminalsWithNonZeroStaff(affectedTerminals, firstMinute, lastMinute) match {
          case affectedTerminalsWithStaff if affectedTerminalsWithStaff.isEmpty =>
            log.debug(s"No affected terminals with deployments. Skipping simulations")
          case affectedTerminalsWithStaff =>
            updateDeployments(affectedTerminalsWithStaff, firstMinute, lastMinute)
            purgeExpired(deployments, TQM.atTime, now, expireAfterMillis.toInt)
            updateSimulationsForPeriod(firstMinute, lastMinute, affectedTerminalsWithStaff)
            pushStateIfReady()
        }

        pullAll()
        timer.stopAndReport()
      }
    })

    setHandler(inStaffMinutes, new InHandler {
      override def onPush(): Unit = {
        val timer = StageTimer(stageName, inStaffMinutes)
        val incomingStaffMinutes: StaffMinutes = grab(inStaffMinutes)
        log.info(s"Grabbed ${incomingStaffMinutes.minutes.length} staff minutes")

        val affectedTerminals = incomingStaffMinutes.minutes.map(_.terminal).distinct

        log.info(s"Staff updates affect ${affectedTerminals.mkString(", ")}")

        updateStaffMinutes(incomingStaffMinutes)
        purgeExpired(staffMinutes, TM.atTime, now, expireAfterMillis.toInt)

        log.info(s"Purged expired staff minutes")

        val firstMinute = crunchPeriodStartMillis(SDate(incomingStaffMinutes.minutes.map(_.minute).min))
        val lastMinute = firstMinute.addDays(1)

        log.info(s"Got first ${firstMinute.toLocalDateTimeString()} and last minutes ${lastMinute.toLocalDateTimeString()}")

        updateDeployments(affectedTerminals, firstMinute, lastMinute)

        log.info(s"Got deployments, updating simulations")
        updateSimulationsForPeriod(firstMinute, lastMinute, affectedTerminals)

        pushStateIfReady()

        pullAll()
        timer.stopAndReport()
      }
    })

    def updateDeployments(affectedTerminals: Seq[Terminal],
                          firstMinute: SDateLike,
                          lastMinute: SDateLike): Unit = {
      val firstMillis = firstMinute.millisSinceEpoch
      val lastMillis = lastMinute.millisSinceEpoch

      val deploymentUpdates = deploymentsForMillis(firstMillis, lastMillis, affectedTerminals)

      deployments ++= deploymentUpdates
    }

    setHandler(outSimulationMinutes, new OutHandler {
      override def onPull(): Unit = {
        val timer = StageTimer(stageName, outSimulationMinutes)
        log.debug(s"outSimulationMinutes onPull called")
        pushStateIfReady()
        pullAll()
        timer.stopAndReport()
      }
    })

    def updateSimulationsForPeriod(firstMinute: SDateLike,
                                   lastMinute: SDateLike,
                                   terminalsToUpdate: Seq[Terminal]
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

      diff.foreach {
        case (tqm, sm) => allSimulationMinutes += (tqm -> sm)
      }

      purgeExpired(allSimulationMinutes, TQM.atTime, now, expireAfterMillis.toInt)

      simulationMinutesToPush ++= diff
      purgeExpired(simulationMinutesToPush, TQM.atTime, now, expireAfterMillis.toInt)
      log.info(s"Now have ${simulationMinutesToPush.size} simulation minutes to push")
    }

    def forPeriod[A](firstMinute: MillisSinceEpoch,
                     lastMinute: MillisSinceEpoch,
                     terminalsToUpdate: Seq[Terminal],
                     itemsToFilter: mutable.SortedMap[TQM, A]): SortedMap[TQM, A] = {
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

    def updateStaffMinutes(incomingStaffMinutes: StaffMinutes): Unit = incomingStaffMinutes.minutes
      .foreach(sm => staffMinutes += (sm.key -> sm))

    def simulateLoads(firstMinute: MillisSinceEpoch, lastMinute: MillisSinceEpoch, terminalsToUpdate: Seq[Terminal]): SortedMap[TQM, SimulationMinute] = {
      val workload = workloadForPeriod(firstMinute, lastMinute, terminalsToUpdate)
      val minuteMillis = firstMinute until lastMinute by 60000

      SortedMap[TQM, SimulationMinute]() ++ terminalsToUpdate.flatMap { tn =>
        airportConfig.nonTransferQueues(tn).flatMap { qn =>
          val queueWorkload = minuteMillis.map(m => workload.getOrElse(TQM(tn, qn, m), 0d))
          simulationForQueue(minuteMillis, tn, qn, queueWorkload)
        }
      }
    }

    def simulationForQueue(minuteMillis: NumericRange[MillisSinceEpoch], tn: Terminal, qn: Queue, queueWorkload: IndexedSeq[Double]): SortedMap[TQM, SimulationMinute] = {
      val sla = airportConfig.slaByQueue.getOrElse(qn, 15)
      val adjustedWorkloads = if (qn == Queues.EGate) adjustEgatesWorkload(queueWorkload) else queueWorkload

      minuteMillis.map(m => deployments.getOrElse(TQM(tn, qn, m), 0)) match {
        case deployedStaff if deployedStaff.sum == 0 =>
          log.debug(s"No deployed staff. Skipping simulations")
          SortedMap()
        case deployedStaff =>
          log.info(s"Running $tn, $qn simulation with ${adjustedWorkloads.length} workloads & ${deployedStaff.length} desks")
          runSimulation(minuteMillis, tn, qn, sla, adjustedWorkloads, deployedStaff)
      }
    }

    def deploymentsForMillis(firstMinute: MillisSinceEpoch, lastMinute: MillisSinceEpoch, terminalsToUpdate: Seq[Terminal]): SortedMap[TQM, Int] = {
      val workload = workloadForPeriod(firstMinute, lastMinute, terminalsToUpdate)

      val minuteMillis = firstMinute until lastMinute by 60000
      val availableStaff: Map[Terminal, Map[MillisSinceEpoch, Int]] = availableStaffForPeriod(firstMinute, lastMinute, terminalsToUpdate)
      val minMaxDesks: Map[Terminal, Map[Queue, Map[MillisSinceEpoch, (Int, Int)]]] = minMaxDesksForMillis(minuteMillis)

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
                                  minMaxDesks: Map[Queue, Map[MillisSinceEpoch, (Int, Int)]],
                                  tn: Terminal,
                                  terminalWorkloads: Map[Queue, Map[MillisSinceEpoch, Double]],
                                  queues: Seq[Queue],
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
                         minMaxDesks: Map[Queue, Map[MillisSinceEpoch, (Int, Int)]],
                         terminalName: Terminal,
                         queues: Seq[Queue],
                         terminalWorkloads: Map[Queue, Map[MillisSinceEpoch, Double]],
                         slotMillis: immutable.IndexedSeq[MillisSinceEpoch]
                        ): Seq[(Queue, Int)] = {
      val queuesWithoutTransfer = airportConfig.nonTransferQueues(terminalName)
      val queueWl = slaWeightedLoadByQueue(queuesWithoutTransfer, terminalWorkloads, slotMillis)
      val slotStartMilli = slotMillis.min
      val queueMm = minMaxDesks.mapValues(_.getOrElse(slotStartMilli, (0, 0)))

      deployer(queueWl, available, queueMm)
    }

    def slaWeightedLoadByQueue(queuesWithoutTransfer: Seq[Queue], terminalWorkloads: TerminalLoad, slotMillis: IndexedSeq[Long]): Seq[(Queue, Double)] = queuesWithoutTransfer
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

    def minMaxDesksForMillis(minuteMillis: Seq[Long]): Map[Terminal, Map[Queue, Map[MillisSinceEpoch, (Int, Int)]]] = airportConfig
      .minMaxDesksByTerminalQueue
      .mapValues(qmm => qmm.mapValues {
        case (minDesks, maxDesks) =>
          minuteMillis.map(m => {
            val min = desksForHourOfDayInUKLocalTime(m, minDesks)
            val max = desksForHourOfDayInUKLocalTime(m, maxDesks)
            (m, (min, max))
          }).toMap
      })

    def workloadForPeriod(firstMinute: MillisSinceEpoch, lastMinute: MillisSinceEpoch, terminalsToUpdate: Seq[Terminal]): SortedMap[TQM, Double] =
      forPeriod(firstMinute, lastMinute, terminalsToUpdate, loadMinutes).mapValues {
        case LoadMinute(_, _, _, workLoad, _) => workLoad
      }

    def filterTerminalMinutes(firstMinute: MillisSinceEpoch, lastMinute: MillisSinceEpoch, terminalsToUpdate: Seq[Terminal]): Seq[StaffMinute] = {
      val maybeThings = for {
        terminalName <- terminalsToUpdate
        minute <- firstMinute until lastMinute by Crunch.oneMinuteMillis
      } yield staffMinutes.get(MinuteHelper.key(terminalName, minute))

      maybeThings.collect { case Some(thing) => thing }
    }

    def adjustEgatesWorkload(workload: IndexedSeq[Double]): IndexedSeq[Double] = workload
      .map(wl => adjustEgateWorkload(airportConfig.eGateBankSize, wl))

    def adjustEgateWorkload(eGateBankSize: Int, wl: Double): Double = wl / eGateBankSize

    val deploymentCache: Map[Int, Seq[(Queue, Int)]] = Map()

    @scala.annotation.tailrec
    def addOneStaffToQueueAtIndex(deployments: List[(Queue, Int, Int)], index: Int, numberOfQueues: Int, staffAvailable: Int): List[(Queue, Int, Int)] = {
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
                              (queueRecs: Seq[(Queue, Double)], staffAvailable: Int, minMaxDesks: Map[Queue, (Int, Int)]): Seq[(Queue, Int)] = {
      val key = (queueRecs, staffAvailable, minMaxDesks).hashCode()

      deploymentCache.get(key) match {
        case Some(deps) => deps
        case None =>
          val queueRecsCorrected = if (queueRecs.map(_._2).sum == 0) queueRecs.map(qr => (qr._1, 1d)) else queueRecs

          val totalStaffRec = queueRecsCorrected.map(_._2).sum

          val deployments = queueRecsCorrected.foldLeft(List[(Queue, Int, Int)]()) {
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

    val deployer: (Seq[(Queue, Double)], Int, Map[Queue, (Int, Int)]) => Seq[(Queue, Int)] = queueRecsToDeployments(_.toInt)

    def pullAll(): Unit = {
      if (!hasBeenPulled(inLoads)) {
        log.debug(s"Pulling inFlightsWithSplits")
        pull(inLoads)
      }
      if (!hasBeenPulled(inStaffMinutes)) {
        log.debug(s"Pulling inStaffMinutes")
        pull(inStaffMinutes)
      }
    }

    def pushStateIfReady(): Unit = {
      if (simulationMinutesToPush.isEmpty) log.debug(s"We have no simulation minutes. Nothing to push")
      else if (isAvailable(outSimulationMinutes)) {
        Metrics.counter(s"$stageName.simulation-minutes", simulationMinutesToPush.size)
        push(outSimulationMinutes, SimulationMinutes(simulationMinutesToPush.values.toSeq))
        simulationMinutesToPush.clear()
      } else log.debug(s"outSimulationMinutes not available to push")
    }

    def availableStaffForPeriod(firstMinute: MillisSinceEpoch, lastMinute: MillisSinceEpoch, terminalNames: Seq[Terminal]): Map[Terminal, Map[MillisSinceEpoch, Int]] =
      filterTerminalMinutes(firstMinute, lastMinute, terminalNames)
        .groupBy(_.terminal)
        .mapValues { sms =>
          sms.map(sm => (sm.minute, sm.availableAtPcp)).toMap
        }

    def terminalsWithNonZeroStaff(allTerminals: Seq[Terminal], firstMinute: SDateLike, lastMinute: SDateLike): Seq[Terminal] = {
      availableStaffForPeriod(firstMinute.millisSinceEpoch, lastMinute.millisSinceEpoch, allTerminals)
        .foldLeft(List[Terminal]()) {
          case (nonZeroTerminals, (terminal, staffByMillis)) =>
            if (staffByMillis.count(_._2 > 0) > 0) terminal :: nonZeroTerminals
            else nonZeroTerminals
        }
    }
  }

  def runSimulation(minuteMillis: NumericRange[MillisSinceEpoch], tn: Terminal, qn: Queue, sla: Int, fullWorkMinutes: IndexedSeq[Double], deployedStaff: IndexedSeq[Int]): SortedMap[TQM, SimulationMinute] = {
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

  val zeroQueueDeployments: Map[Terminal, Seq[(Queue, Int)]] = airportConfig
    .terminals
    .map(tn => (tn, airportConfig.nonTransferQueues(tn).map(qn => (qn, 0))))
    .toMap
}

case class SimulationMinute(terminalName: Terminal,
                            queueName: Queue,
                            minute: MillisSinceEpoch,
                            desks: Int,
                            waitTime: Int) extends SimulationMinuteLike with MinuteComparison[CrunchMinute] {
  lazy val key: TQM = MinuteHelper.key(terminalName, queueName, minute)

  override def maybeUpdated(existing: CrunchMinute, now: MillisSinceEpoch): Option[CrunchMinute] =
    if (existing.deployedDesks.isEmpty || existing.deployedDesks.get != desks || existing.deployedWait.isEmpty || existing.deployedWait.get != waitTime) Option(existing.copy(
      deployedDesks = Option(desks), deployedWait = Option(waitTime), lastUpdated = Option(now)
    ))
    else None
}

case class SimulationMinutes(minutes: Seq[SimulationMinute]) extends PortStateMinutes {
  def applyTo(portState: PortStateMutable, now: MillisSinceEpoch): PortStateDiff = {
    val minutesDiff = minutes.foldLeft(List[CrunchMinute]()) { case (soFar, dm) =>
      addIfUpdated(portState.crunchMinutes.getByKey(dm.key), now, soFar, dm, () => CrunchMinute(dm, now))
    }
    portState.crunchMinutes +++= minutesDiff
    PortStateDiff(Seq(), Seq(), Seq(), minutesDiff, Seq())
  }
}
