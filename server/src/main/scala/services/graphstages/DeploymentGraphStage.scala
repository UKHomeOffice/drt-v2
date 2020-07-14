package services.graphstages

import akka.stream._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import drt.shared.CrunchApi.{MillisSinceEpoch, _}
import drt.shared.Queues.Queue
import drt.shared.Terminals.Terminal
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import services.SDate
import services.crunch.desklimits.PortDeskLimits
import services.crunch.desklimits.PortDeskLimits.StaffToDeskLimits
import services.crunch.deskrecs.DesksAndWaitsPortProvider
import services.graphstages.Crunch._
import services.metrics.StageTimer

import scala.collection.immutable.{Map, SortedMap}
import scala.collection.mutable


class DeploymentGraphStage(name: String = "",
                           optionalInitialCrunchMinutes: Option[CrunchMinutes],
                           optionalInitialStaffMinutes: Option[StaffMinutes],
                           airportConfig: AirportConfig,
                           expireAfterMillis: Int,
                           now: () => SDateLike,
                           crunchPeriodStartMillis: SDateLike => SDateLike,
                           portDeskRecs: DesksAndWaitsPortProvider)
  extends GraphStage[FanInShape2[Loads, StaffMinutes, SimulationMinutes]] {

  type TerminalLoad = Map[Queue, Map[MillisSinceEpoch, Double]]
  type PortLoad = Map[Terminal, TerminalLoad]

  val inLoads: Inlet[Loads] = Inlet[Loads]("Loads.in")
  val inStaffMinutes: Inlet[StaffMinutes] = Inlet[StaffMinutes]("StaffMinutes.in")
  val outSimulationMinutes: Outlet[SimulationMinutes] = Outlet[SimulationMinutes]("SimulationMinutes.out")
  val stageName = "simulation"

  val staffToDeskLimits: StaffToDeskLimits = PortDeskLimits.flexedByAvailableStaff(airportConfig)

  override val shape = new FanInShape2[Loads, StaffMinutes, SimulationMinutes](inLoads, inStaffMinutes, outSimulationMinutes)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    val loadMinutes: mutable.SortedMap[TQM, LoadMinute] = mutable.SortedMap()
    val staffMinutes: mutable.SortedMap[TM, StaffMinute] = mutable.SortedMap()
    val deployments: mutable.SortedMap[TQM, Int] = mutable.SortedMap()
    val allSimulationMinutes: mutable.SortedMap[TQM, SimulationMinute] = mutable.SortedMap()
    val simulationMinutesToPush: mutable.SortedMap[TQM, SimulationMinute] = mutable.SortedMap()

    val log: Logger = LoggerFactory.getLogger(s"$getClass-$name")

    override def preStart(): Unit = {
      val initialMinutesCount = optionalInitialCrunchMinutes.getOrElse(CrunchMinutes(Set())).minutes.size
      log.info(s"Received $initialMinutesCount initial crunch minutes")

      optionalInitialStaffMinutes.foreach(_.minutes.foreach(sm => staffMinutes += (sm.key -> sm)))

      optionalInitialCrunchMinutes.foreach(_.minutes.foreach { cm =>
        val lm = LoadMinute(cm.terminal, cm.queue, cm.paxLoad, cm.workLoad, cm.minute)
        loadMinutes += (lm.uniqueId -> lm)
        deployments += (TQM(cm.terminal, cm.queue, cm.minute) -> cm.deployedDesks.getOrElse(0))
      })

      super.preStart()
    }

    setHandler(inLoads, new InHandler {
      override def onPush(): Unit = {
        val timer = StageTimer(stageName, inLoads)
        grab(inLoads) match {
          case incomingLoads if incomingLoads.loadMinutes.isEmpty =>
            log.debug("Received empty load minutes")
          case incomingLoads =>
            log.info(s"Received ${incomingLoads.loadMinutes.size} load minutes")

            val affectedTerminals = incomingLoads.loadMinutes.map { case (TQM(t, _, _), _) => t }.toSet.toSeq

            loadMinutes ++= incomingLoads.loadMinutes
            purgeExpired(loadMinutes, TQM.atTime, now, expireAfterMillis.toInt)

            val allMinuteMillis: Iterable[MillisSinceEpoch] = incomingLoads.loadMinutes.keys.map(_.minute)
            val firstMinute = crunchPeriodStartMillis(SDate(allMinuteMillis.min))
            val lastMinute = firstMinute.addMinutes(airportConfig.minutesToCrunch)

            terminalsWithNonZeroStaff(affectedTerminals, firstMinute, lastMinute) match {
              case affectedTerminalsWithStaff: Seq[Terminal] =>
                purgeExpired(deployments, TQM.atTime, now, expireAfterMillis.toInt)
                val affectedTerminalsWithNoStaff: Seq[Terminal] = affectedTerminals.diff(affectedTerminalsWithStaff)
                val updatedMinutes: Map[TQM, SimulationMinute] = updateSimulationsForPeriod(firstMinute, lastMinute, affectedTerminalsWithStaff)
                val resetMinutes: Seq[(TQM, SimulationMinute)] = resetSimulationsForPeriod(affectedTerminalsWithNoStaff, firstMinute, lastMinute)
                setDeployments(updatedMinutes ++ resetMinutes)
                pushStateIfReady()
            }
        }

        pullAll()
        timer.stopAndReport()
      }
    })

    setHandler(inStaffMinutes, new InHandler {
      override def onPush(): Unit = {
        val timer = StageTimer(stageName, inStaffMinutes)

        grab(inStaffMinutes) match {
          case StaffMinutes(minutes) if minutes.isEmpty =>
            log.debug(s"Received empty staff minutes")
          case StaffMinutes(minutes) if minutes.nonEmpty =>
            val affectedTerminals = minutes.map(_.terminal).distinct
            log.debug(s"Received ${minutes.length} staff minutes affecting ${affectedTerminals.mkString(", ")}")

            updateStaffMinutes(StaffMinutes(minutes))
            purgeExpired(staffMinutes, TM.atTime, now, expireAfterMillis.toInt)

            val firstMinute = crunchPeriodStartMillis(SDate(minutes.map(_.minute).min))
            val lastMinute = firstMinute.addMinutes(airportConfig.minutesToCrunch)

            log.debug(s"Got first ${firstMinute.toLocalDateTimeString()} and last minutes ${lastMinute.toLocalDateTimeString()}")

            val updates = updateSimulationsForPeriod(firstMinute, lastMinute, affectedTerminals)

            setDeployments(updates)

            pushStateIfReady()
        }

        pullAll()
        timer.stopAndReport()
      }
    })

    def setDeployments(simulationMinutes: Map[TQM, SimulationMinute]): Unit = {
      deployments ++= simulationMinutes.mapValues(_.desks)
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
                                  ): Map[TQM, SimulationMinute] = {
      log.debug(s"Simulation for ${firstMinute.toLocalDateTimeString()} - ${lastMinute.toLocalDateTimeString()} ${terminalsToUpdate.mkString(", ")}")

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

      diff
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

    def simulateLoads(firstMinute: MillisSinceEpoch,
                      lastMinute: MillisSinceEpoch,
                      terminalsToUpdate: Seq[Terminal]): SortedMap[TQM, SimulationMinute] = {
      val workload: Map[TQM, LoadMinute] = forPeriod(firstMinute, lastMinute, terminalsToUpdate, loadMinutes)
      val minuteMillis = firstMinute until lastMinute by 60000
      val staff = availableStaff(firstMinute, lastMinute, terminalsToUpdate)

      val deskLimitsProvider = staffToDeskLimits(staff)

      SortedMap[TQM, SimulationMinute]() ++ portDeskRecs.loadsToSimulations(minuteMillis, workload, deskLimitsProvider)
    }

    def filterTerminalMinutes(firstMinute: MillisSinceEpoch,
                              lastMinute: MillisSinceEpoch,
                              terminalsToUpdate: Seq[Terminal]): Seq[StaffMinute] = for {
      terminal <- terminalsToUpdate
      minute <- firstMinute until lastMinute by MilliTimes.oneMinuteMillis
    } yield staffMinutes.getOrElse(MinuteHelper.key(terminal, minute), StaffMinute(terminal, minute, 0, 0, 0))

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
        log.info(s"Pushing ${simulationMinutesToPush.size} deployments")
        push(outSimulationMinutes, SimulationMinutes(simulationMinutesToPush.values.toSeq))
        simulationMinutesToPush.clear()
      } else log.debug(s"outSimulationMinutes not available to push")
    }

    def availableStaff(firstMinute: MillisSinceEpoch,
                       lastMinute: MillisSinceEpoch,
                       terminalNames: Seq[Terminal]): Map[Terminal, List[Int]] =
      filterTerminalMinutes(firstMinute, lastMinute, terminalNames)
        .groupBy(_.terminal)
        .mapValues {
          _.map(_.availableAtPcp).toList
        }

    def availableStaffForPeriod(firstMinute: MillisSinceEpoch,
                                lastMinute: MillisSinceEpoch,
                                terminalNames: Seq[Terminal]): Map[Terminal, Map[MillisSinceEpoch, Int]] =
      filterTerminalMinutes(firstMinute, lastMinute, terminalNames)
        .groupBy(_.terminal)
        .mapValues { sms =>
          sms.map(sm => (sm.minute, sm.availableAtPcp)).toMap
        }

    def terminalsWithNonZeroStaff(allTerminals: Seq[Terminal],
                                  firstMinute: SDateLike,
                                  lastMinute: SDateLike): Seq[Terminal] = {
      availableStaffForPeriod(firstMinute.millisSinceEpoch, lastMinute.millisSinceEpoch, allTerminals)
        .foldLeft(List[Terminal]()) {
          case (nonZeroTerminals, (terminal, staffByMillis)) =>
            if (staffByMillis.count(_._2 > 0) > 0) terminal :: nonZeroTerminals
            else nonZeroTerminals
        }
    }

    def resetSimulationsForPeriod(terminals: Seq[Terminal],
                                  firstMinute: SDateLike,
                                  lastMinute: SDateLike): Seq[(TQM, SimulationMinute)] = {
      val resetMinutes = for {
        terminal <- terminals
        queue <- airportConfig.queuesByTerminal(terminal)
        minute <- firstMinute.millisSinceEpoch to lastMinute.millisSinceEpoch by MilliTimes.oneMinuteMillis
      } yield {
        val sm = SimulationMinute(terminal, queue, minute, 0, 0)
        (sm.key, sm)
      }

      simulationMinutesToPush ++= resetMinutes

      resetMinutes
    }
  }
}
