package services.graphstages

import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.stream._
import drt.shared.CrunchApi.{CrunchMinute, MillisSinceEpoch, PortState}
import drt.shared.FlightsApi.{FlightsWithSplits, QueueName, TerminalName}
import drt.shared.SplitRatiosNs.SplitSources
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.core.SplitsCalculator
import passengersplits.parsing.VoyageManifestParser.{VoyageManifest, VoyageManifests}
import services._
import services.graphstages.Crunch.{log, _}
import services.workloadcalculator.PaxLoadCalculator.Load

import scala.collection.immutable.Map
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

case class ArrivalsDiff(toUpdate: Set[Arrival], toRemove: Set[Int])

class CrunchGraphStage(name: String,
                       optionalInitialFlights: Option[FlightsWithSplits],
                       airportConfig: AirportConfig,
                       natProcTimes: Map[String, Double],
                       groupFlightsByCodeShares: (Seq[ApiFlightWithSplits]) => List[(ApiFlightWithSplits, Set[Arrival])],
                       splitsCalculator: SplitsCalculator,
                       crunchStartFromFirstPcp: (SDateLike) => SDateLike = getLocalLastMidnight,
                       crunchEndFromLastPcp: (SDateLike) => SDateLike = (_) => getLocalNextMidnight(SDate.now()),
                       earliestAndLatestAffectedPcpTime: (Set[ApiFlightWithSplits], Set[ApiFlightWithSplits]) => Option[(SDateLike, SDateLike)],
                       expireAfterMillis: Long,
                       now: () => SDateLike,
                       maxDaysToCrunch: Int,
                       waitForManifests: Boolean = true,
                       minutesToCrunch: Int,
                       warmUpMinutes: Int,
                       useNationalityBasedProcessingTimes: Boolean)
  extends GraphStage[FanInShape3[ArrivalsDiff, VoyageManifests, Seq[(Arrival, Option[ApiSplits])], PortState]] {

  val inArrivalsDiff: Inlet[ArrivalsDiff] = Inlet[ArrivalsDiff]("ArrivalsDiffIn.in")
  val inManifests: Inlet[VoyageManifests] = Inlet[VoyageManifests]("SplitsIn.in")
  val inSplitsPredictions: Inlet[Seq[(Arrival, Option[ApiSplits])]] = Inlet[Seq[(Arrival, Option[ApiSplits])]]("SplitsPredictionsIn.in")
  val outCrunch: Outlet[PortState] = Outlet[PortState]("PortStateOut.out")

  override val shape = new FanInShape3(inArrivalsDiff, inManifests, inSplitsPredictions, outCrunch)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    var flightsByFlightId: Map[Int, ApiFlightWithSplits] = Map()
    var manifestsBuffer: Map[String, Set[VoyageManifest]] = Map()
    var waitingForArrivals: Boolean = true
    var waitingForManifests: Boolean = waitForManifests

    var portStateOption: Option[PortState] = None

    val log: Logger = LoggerFactory.getLogger(s"$getClass-$name")

    override def preStart(): Unit = {
      optionalInitialFlights match {
        case Some(FlightsWithSplits(flights)) =>
          log.info(s"Received initial flights. Setting ${flights.size}")
          flightsByFlightId = purgeExpiredArrivals(
            flights
              .map(f => Tuple2(f.apiFlight.uniqueId, f))
              .toMap)
        case _ =>
          log.warn(s"Did not receive any flights to initialise with")
      }

      super.preStart()
    }

    setHandler(outCrunch, new OutHandler {
      override def onPull(): Unit = {
        log.debug(s"crunchOut onPull called")
        pushStateIfReady()
        pullAll()
      }
    })

    def pullAll(): Unit = {
      List(inManifests, inArrivalsDiff, inSplitsPredictions).foreach(in => if (!hasBeenPulled(in)) {
        log.info(s"Pulling ${in.toString}")
        pull(in)
      })
    }

    setHandler(inArrivalsDiff, new InHandler {
      override def onPush(): Unit = {
        log.debug(s"inFlights onPush called")
        val arrivalsDiff = grab(inArrivalsDiff)
        waitingForArrivals = false

        log.info(s"Grabbed ${arrivalsDiff.toUpdate.size} updates, ${arrivalsDiff.toRemove.size} removals")

        val updatedFlights = purgeExpiredArrivals(updateFlightsFromIncoming(arrivalsDiff, flightsByFlightId))

        if (flightsByFlightId != updatedFlights) {
          crunchIfAppropriate(updatedFlights, flightsByFlightId)
          flightsByFlightId = updatedFlights
        } else log.info(s"No updates due to flights")

        pullAll()
      }
    })

    setHandler(inManifests, new InHandler {
      override def onPush(): Unit = {
        log.debug(s"inSplits onPush called")
        val vms = grab(inManifests)
        waitingForManifests = false

        log.info(s"Grabbed ${vms.manifests.size} manifests")
        val updatedFlights = updateFlightsWithManifests(vms.manifests, flightsByFlightId)

        manifestsBuffer = purgeExpiredManifests(manifestsBuffer)

        if (flightsByFlightId != updatedFlights) {
          crunchIfAppropriate(updatedFlights, flightsByFlightId)
          flightsByFlightId = updatedFlights
        } else log.info(s"No updates due to API splits")

        pullAll()
      }
    })

    setHandler(inSplitsPredictions, new InHandler {
      override def onPush(): Unit = {
        log.debug(s"inSplitsPredictions onPush called")
        val predictions = grab(inSplitsPredictions)

        log.info(s"Grabbed ${predictions.length} predictions")
        val updatedFlights = predictions
          .collect {
            case (arrival, Some(splits)) => (arrival, splits)
          }
          .foldLeft(flightsByFlightId) {
            case (existingFlightsByFlightId, (arrivalForPrediction, predictedSplits)) =>
              existingFlightsByFlightId.find {
                case (_, ApiFlightWithSplits(existingArrival, _, _)) => existingArrival.uniqueId == arrivalForPrediction.uniqueId
              } match {
                case Some((id, existingFlightWithSplits)) =>
                  val predictedSplitsWithPaxNumbers = predictedSplits.copy(splits = predictedSplits.splits.map(s => s.copy(paxCount = s.paxCount * ArrivalHelper.bestPax(arrivalForPrediction))), splitStyle = PaxNumbers)
                  val predictedWithEgatesAndFt = predictedSplitsWithPaxNumbers.copy(splits = splitsCalculator.addEgatesAndFastTrack(arrivalForPrediction, predictedSplitsWithPaxNumbers.splits))
                  val newSplitsSet = existingFlightWithSplits.splits.filterNot {
                    case ApiSplits(_, SplitSources.PredictedSplitsWithHistoricalEGateAndFTPercentages, _, _) => true
                    case _ => false
                  } + predictedWithEgatesAndFt

                  existingFlightsByFlightId.updated(id, existingFlightWithSplits.copy(splits = newSplitsSet))

                case None => existingFlightsByFlightId
              }
          }

        if (flightsByFlightId != updatedFlights) {
          crunchIfAppropriate(updatedFlights, flightsByFlightId)
          flightsByFlightId = updatedFlights
        } else log.info(s"No updates due to splits predictions")

        pullAll()
      }
    })

    def crunchIfAppropriate(updatedFlights: Map[Int, ApiFlightWithSplits], existingFlights: Map[Int, ApiFlightWithSplits]): Unit = {
      val earliestAndLatest = earliestAndLatestAffectedPcpTime(existingFlights.values.toSet, updatedFlights.values.toSet)
      log.info(s"Latest PCP times: $earliestAndLatest")
      earliestAndLatest.foreach {
        case (earliest, latest) =>
          val crunchStart = crunchStartFromFirstPcp(earliest)
          val crunchEnd = crunchEndFromLastPcp(latest)
          log.info(s"Crunch period ${crunchStart.toLocalDateTimeString()} to ${crunchEnd.toLocalDateTimeString()}")
          val lastCrunchPortState = portStateOption
          val newCrunchPortState = crunch(updatedFlights, crunchStart, crunchEnd)
          portStateOption = Crunch.mergeMaybePortStates(lastCrunchPortState, newCrunchPortState)
          pushStateIfReady()
      }
    }

    def updateFlightsFromIncoming(arrivalsDiff: ArrivalsDiff, existingFlightsById: Map[Int, ApiFlightWithSplits]): Map[Int, ApiFlightWithSplits] = {
      log.info(s"${arrivalsDiff.toUpdate.size} diff updates, ${existingFlightsById.size} existing flights")
      val afterRemovals = existingFlightsById.filterNot {
        case (id, _) => arrivalsDiff.toRemove.contains(id)
      }
      val latestCrunchMinute = getLocalLastMidnight(now().addDays(maxDaysToCrunch))
      val (updatesCount, additionsCount, updatedFlights) = arrivalsDiff.toUpdate.foldLeft((0, 0, afterRemovals)) {
        case ((updates, additions, flightsSoFar), updatedFlight) if updatedFlight.PcpTime > latestCrunchMinute.millisSinceEpoch =>
          val pcpTime = SDate(updatedFlight.PcpTime).toLocalDateTimeString()
          log.debug(s"Ignoring arrival with PCP time ($pcpTime) beyond latest crunch minute (${latestCrunchMinute.toLocalDateTimeString()})")
          (updates, additions, flightsSoFar)
        case ((updates, additions, flightsSoFar), updatedFlight) =>
          flightsSoFar.get(updatedFlight.uniqueId) match {
            case None =>
              val ths = splitsCalculator.terminalAndHistoricSplits(updatedFlight)
              val newFlightWithSplits = ApiFlightWithSplits(updatedFlight, ths, Option(SDate.now().millisSinceEpoch))
              val newFlightWithAvailableSplits = addApiSplitsIfAvailable(newFlightWithSplits)
              (updates, additions + 1, flightsSoFar.updated(updatedFlight.uniqueId, newFlightWithAvailableSplits))

            case Some(existingFlight) if existingFlight.apiFlight != updatedFlight =>
              (updates + 1, additions, flightsSoFar.updated(updatedFlight.uniqueId, existingFlight.copy(apiFlight = updatedFlight)))
            case _ =>
              (updates, additions, flightsSoFar)
          }
      }

      log.info(s"${updatedFlights.size} flights after updates. $updatesCount updates & $additionsCount additions")

      val minusExpired = purgeExpiredArrivals(updatedFlights)
      log.debug(s"${minusExpired.size} flights after removing expired")

      minusExpired
    }

    def addApiSplitsIfAvailable(newFlightWithSplits: ApiFlightWithSplits): ApiFlightWithSplits = {
      val arrival = newFlightWithSplits.apiFlight
      val vmIdx = s"${Crunch.flightVoyageNumberPadded(arrival)}-${arrival.Scheduled}"

      val newFlightWithAvailableSplits = manifestsBuffer.get(vmIdx) match {
        case None => newFlightWithSplits
        case Some(vm) =>
          manifestsBuffer = manifestsBuffer.filterNot { case (idx, _) => idx == vmIdx }
          log.debug(s"Found buffered manifest to apply to new flight, and removed from buffer")
          removeManifestsOlderThan(twoDaysAgo)
          updateFlightWithManifests(vm, newFlightWithSplits)
      }
      newFlightWithAvailableSplits
    }

    def removeManifestsOlderThan(thresholdMillis: MillisSinceEpoch): Unit = {
      manifestsBuffer = manifestsBuffer.filter {
        case (_, vmsInBuffer) =>
          val vmsToKeep = vmsInBuffer.filter(vm => isNewerThan(thresholdMillis, vm))
          vmsToKeep match {
            case vms if vms.nonEmpty => true
            case _ => false
          }
      }
    }

    def updateFlightsWithManifests(manifests: Set[VoyageManifest], flightsById: Map[Int, ApiFlightWithSplits]): Map[Int, ApiFlightWithSplits] = {
      manifests.foldLeft[Map[Int, ApiFlightWithSplits]](flightsByFlightId) {
        case (flightsSoFar, newManifest) =>
          val vmMillis = newManifest.scheduleArrivalDateTime.map(_.millisSinceEpoch).getOrElse(0L)
          val matchingFlight: Option[(Int, ApiFlightWithSplits)] = flightsSoFar
            .find {
              case (_, f) =>
                val vnMatches = Crunch.flightVoyageNumberPadded(f.apiFlight) == newManifest.VoyageNumber
                val schMatches = vmMillis == f.apiFlight.Scheduled
                vnMatches && schMatches
              case _ => false
            }

          matchingFlight match {
            case None =>
              log.debug(s"Stashing VoyageManifest in case flight is seen later")
              val idx = s"${newManifest.VoyageNumber}-$vmMillis"
              val existingManifests = manifestsBuffer.getOrElse(idx, Set())
              val updatedManifests = existingManifests + newManifest
              manifestsBuffer = manifestsBuffer.updated(idx, updatedManifests)
              flightsSoFar
            case Some(Tuple2(id, f)) =>
              val updatedFlight = updateFlightWithManifest(f, newManifest)
              flightsSoFar.updated(id, updatedFlight)
          }
      }
    }

    def crunch(flights: Map[Int, ApiFlightWithSplits], crunchStart: SDateLike, crunchEnd: SDateLike): Option[PortState] = {
      val start = crunchStart.addMinutes(-1 * warmUpMinutes)

      val scheduledFlightsInCrunchWindow = flights
        .values
        .toList
        .filter(_.apiFlight.Status != "Cancelled")
        .filter(f => isFlightInTimeWindow(f, start, crunchEnd))

      log.info(s"Requesting crunch for ${scheduledFlightsInCrunchWindow.length} flights after flights update")
      val uniqueFlights = groupFlightsByCodeShares(scheduledFlightsInCrunchWindow).map(_._1)
      val newFlightSplitMinutesByFlight = flightsToFlightSplitMinutes(airportConfig.defaultProcessingTimes.head._2, useNationalityBasedProcessingTimes)(uniqueFlights)
      val earliestMinute: MillisSinceEpoch = newFlightSplitMinutesByFlight.values.flatMap(_.map(identity)).toList match {
        case fsm if fsm.nonEmpty => fsm.map(_.minute).min
        case _ => 0L
      }
      log.debug(s"Earliest flight split minute: ${SDate(earliestMinute).toLocalDateTimeString()}")
      val numberOfMinutes = ((crunchEnd.millisSinceEpoch - crunchStart.millisSinceEpoch) / 60000).toInt
      log.debug(s"Crunching $numberOfMinutes minutes")
      val crunchMinutes = crunchFlightSplitMinutes(crunchStart.millisSinceEpoch, numberOfMinutes, newFlightSplitMinutesByFlight)

      Option(PortState(flights = flights, crunchMinutes = crunchMinutes, staffMinutes = Map()))
    }

    def pushStateIfReady(): Unit = {
      if (!waitingForManifests && !waitingForArrivals) {
        portStateOption match {
          case None => log.info(s"We have no PortState yet. Nothing to push")
          case Some(portState) =>
            if (isAvailable(outCrunch)) {
              log.info(s"Pushing PortState: ${portState.crunchMinutes.size} cms, ${portState.staffMinutes.size} sms, ${portState.flights.size} fts")
              push(outCrunch, portState)
              portStateOption = None
            } else log.info(s"outCrunch not available to push")
        }
      } else {
        if (waitingForArrivals) log.info(s"Waiting for arrivals")
        if (waitingForManifests) log.info(s"Waiting for manifests")
      }
    }

    def crunchFlightSplitMinutes(crunchStart: MillisSinceEpoch, numberOfMinutes: Int, flightSplitMinutesByFlight: Map[Int, Set[FlightSplitMinute]]): Map[Int, CrunchMinute] = {
      val qlm: Set[LoadMinute] = flightSplitMinutesToQueueLoadMinutes(flightSplitMinutesByFlight)
      val wlByQueue: Map[TerminalName, Map[QueueName, Map[MillisSinceEpoch, (Load, Load)]]] = indexQueueWorkloadsByMinute(qlm)

      val fullWlByTerminalAndQueue = queueMinutesForPeriod(crunchStart - warmUpMinutes * oneMinuteMillis, numberOfMinutes + warmUpMinutes)(wlByQueue)
      val eGateBankSize = 5

      workloadsToCrunchMinutes(warmUpMinutes, fullWlByTerminalAndQueue, airportConfig.slaByQueue, airportConfig.minMaxDesksByTerminalQueue, eGateBankSize)
    }

    def workloadsToCrunchMinutes(warmUpMinutes: Int, portWorkloads: Map[TerminalName, Map[QueueName, List[(MillisSinceEpoch, (Load, Load))]]], slas: Map[QueueName, Int], minMaxDesks: Map[TerminalName, Map[QueueName, (List[Int], List[Int])]], eGateBankSize: Int): Map[Int, CrunchMinute] = {
      portWorkloads.flatMap {
        case (tn, terminalWorkloads) =>
          val terminalCrunchMinutes = terminalWorkloads.flatMap {
            case (qn, queueWorkloads) =>
              crunchQueueWorkloads(warmUpMinutes, slas, minMaxDesks, eGateBankSize, tn, qn, queueWorkloads)
          }
          terminalCrunchMinutes
      }
    }

    def crunchQueueWorkloads(warmUpMinutes: Int, slas: Map[QueueName, Int], minMaxDesks: Map[TerminalName, Map[QueueName, (List[Int], List[Int])]], eGateBankSize: Int, tn: TerminalName, qn: QueueName, queueWorkloads: List[(MillisSinceEpoch, (Load, Load))]): Map[Int, CrunchMinute] = {
      val minutesToCrunchWithWarmUp = minutesToCrunch + warmUpMinutes

      val queueWorkloadsByCrunchPeriod: Seq[List[(MillisSinceEpoch, (Load, Load))]] = queueWorkloads
        .sortBy(_._1)
        .sliding(minutesToCrunchWithWarmUp, minutesToCrunch)
        .toList

      val queueCrunchMinutes: Map[Int, CrunchMinute] = queueWorkloadsByCrunchPeriod
        .flatMap(wl => {
          crunchMinutes(slas, minMaxDesks, eGateBankSize, tn, qn, wl)
            .toList
            .sortBy(_._2.minute)
            .drop(warmUpMinutes)
        })
        .toMap
      queueCrunchMinutes
    }

    def crunchMinutes(slas: Map[QueueName, Int], minMaxDesks: Map[TerminalName, Map[QueueName, (List[Int], List[Int])]], eGateBankSize: Int, tn: TerminalName, qn: QueueName, queueWorkloads: List[(MillisSinceEpoch, (Load, Load))]): Map[Int, CrunchMinute] = {
      val numberOfMinutes = queueWorkloads.length
      val loadByMillis = queueWorkloads.toMap
      val triedResult: Try[OptimizerCrunchResult] = optimiserCrunchResult(slas, minMaxDesks, eGateBankSize, tn, qn, queueWorkloads)

      val crunchStartMillis = queueWorkloads.map(_._1).min
      val queueCrunchMinutes = triedResult match {
        case Success(OptimizerCrunchResult(deskRecs, waitTimes)) =>
          val crunchMinutes = (0 until numberOfMinutes).map(minuteIdx => {
            val minuteMillis = crunchStartMillis + (minuteIdx * oneMinuteMillis)
            val paxLoad = loadByMillis.mapValues(_._1).getOrElse(minuteMillis, 0d)
            val workLoad = loadByMillis.mapValues(_._2).getOrElse(minuteMillis, 0d)
            CrunchMinute(tn, qn, minuteMillis, paxLoad, workLoad, deskRecs(minuteIdx), waitTimes(minuteIdx))
          })
          crunchMinutes.map(cm => (cm.key, cm)).toMap
        case Failure(t) =>
          log.warn(s"Crunch failed: $t")
          Map[Int, CrunchMinute]()
      }
      queueCrunchMinutes
    }

    def optimiserCrunchResult(slas: Map[QueueName, Int], minMaxDesks: Map[TerminalName, Map[QueueName, (List[Int], List[Int])]], eGateBankSize: Int, tn: TerminalName, qn: QueueName, queueWorkloads: List[(MillisSinceEpoch, (Load, Load))]): Try[OptimizerCrunchResult] = {
      val crunchStartMillis = queueWorkloads.map(_._1).min
      val crunchEnd = queueWorkloads.map(_._1).max
      val crunchMinutes = crunchStartMillis to crunchEnd by oneMinuteMillis

      log.info(s"Crunching $tn/$qn ${crunchMinutes.length} minutes: ${SDate(crunchStartMillis).toLocalDateTimeString()} to ${SDate(crunchEnd).toLocalDateTimeString()}")

      val workloadMinutes = qn match {
        case Queues.EGate => queueWorkloads.map(_._2._2 / eGateBankSize)
        case _ => queueWorkloads.map(_._2._2)
      }
      val defaultMinMaxDesks = (Seq.fill(24)(0), Seq.fill(24)(10))
      val sla = slas.getOrElse(qn, 0)
      val queueMinMaxDesks = minMaxDesks.getOrElse(tn, Map()).getOrElse(qn, defaultMinMaxDesks)
      val minDesks = crunchMinutes.map(desksForHourOfDayInUKLocalTime(_, queueMinMaxDesks._1))
      val maxDesks = crunchMinutes.map(desksForHourOfDayInUKLocalTime(_, queueMinMaxDesks._2))
      val triedResult = TryRenjin.crunch(workloadMinutes, minDesks, maxDesks, OptimizerConfig(sla))
      triedResult
    }

    def queueMinutesForPeriod(startTime: Long, numberOfMinutes: Int)
                             (terminal: Map[TerminalName, Map[QueueName, Map[MillisSinceEpoch, (Double, Double)]]]): Map[TerminalName, Map[QueueName, List[(MillisSinceEpoch, (Double, Double))]]] = {
      val endTime = startTime + numberOfMinutes * oneMinuteMillis

      terminal.mapValues(queue => {
        queue.mapValues(queueWorkloadMinutes => {
          (startTime until endTime by oneMinuteMillis).map(minuteMillis =>
            (minuteMillis, queueWorkloadMinutes.getOrElse(minuteMillis, (0d, 0d)))).toList
        })
      })
    }

    def indexQueueWorkloadsByMinute(queueWorkloadMinutes: Set[LoadMinute]): Map[TerminalName, Map[QueueName, Map[MillisSinceEpoch, (Double, Double)]]] = {
      val portLoads = queueWorkloadMinutes.groupBy(_.terminalName)

      portLoads.mapValues(terminalLoads => {
        val queueLoads = terminalLoads.groupBy(_.queueName)
        queueLoads
          .mapValues(_.map(qwl =>
            (qwl.minute, (qwl.paxLoad, qwl.workLoad))
          ).toMap)
      })
    }

    def flightsToFlightSplitMinutes(portProcTimes: Map[PaxTypeAndQueue, Double], useNationalityBasedProcessingTimes: Boolean)(flightsWithSplits: List[ApiFlightWithSplits]): Map[Int, Set[FlightSplitMinute]] = {
      flightsWithSplits.map {
        case flightWithSplits: ApiFlightWithSplits =>
          (flightWithSplits.apiFlight.uniqueId, WorkloadCalculator.flightToFlightSplitMinutes(flightWithSplits, portProcTimes, natProcTimes, useNationalityBasedProcessingTimes))
      }.toMap
    }

    def flightSplitMinutesToQueueLoadMinutes(flightToFlightSplitMinutes: Map[Int, Set[FlightSplitMinute]]): Set[LoadMinute] = {
      flightToFlightSplitMinutes
        .values
        .flatten
        .groupBy(s => (s.terminalName, s.queueName, s.minute)).map {
        case ((terminalName, queueName, minute), fsms) =>
          val paxLoad = fsms.map(_.paxLoad).sum
          val workLoad = fsms.map(_.workLoad).sum
          LoadMinute(terminalName, queueName, paxLoad, workLoad, minute)
      }.toSet
    }

    def updateFlightWithManifests(manifests: Set[VoyageManifest], f: ApiFlightWithSplits): ApiFlightWithSplits = {
      manifests.foldLeft(f) {
        case (flightSoFar, manifest) => updateFlightWithManifest(flightSoFar, manifest)
      }
    }

    def updateFlightWithManifest(flightWithSplits: ApiFlightWithSplits, manifest: VoyageManifest): ApiFlightWithSplits = {
      val splitsFromManifest = splitsCalculator.splitsForArrival(manifest, flightWithSplits.apiFlight)

      val updatedSplitsSet = flightWithSplits.splits.filterNot {
        case ApiSplits(_, SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages, Some(manifest.EventCode), _) => true
        case _ => false
      } + splitsFromManifest

      flightWithSplits.copy(splits = updatedSplitsSet)
    }
  }

  def purgeExpiredManifests(manifests: Map[String, Set[VoyageManifest]]): Map[String, Set[VoyageManifest]] = {
    val expired = hasExpiredForType((m: VoyageManifest) => m.scheduleArrivalDateTime.getOrElse(SDate.now()).millisSinceEpoch)
    val updated = manifests
      .mapValues(_.filterNot(expired))
      .filterNot { case (_, ms) => ms.isEmpty }

    val numPurged = manifests.size - updated.size
    if (numPurged > 0) log.info(s"Purged $numPurged expired manifests")

    updated
  }

  def purgeExpiredArrivals(arrivals: Map[Int, ApiFlightWithSplits]): Map[Int, ApiFlightWithSplits] = {
    val expired = hasExpiredForType((a: ApiFlightWithSplits) => a.apiFlight.PcpTime)
    val updated = arrivals.filterNot { case (_, a) => expired(a) }

    val numPurged = arrivals.size - updated.size
    if (numPurged > 0) log.info(s"Purged $numPurged expired arrivals")

    updated
  }

  def hasExpiredForType[A](toMillis: A => MillisSinceEpoch): A => Boolean = {
    Crunch.hasExpired[A](now(), expireAfterMillis, toMillis)
  }

  def isFlightInTimeWindow(f: ApiFlightWithSplits, crunchStart: SDateLike, crunchEnd: SDateLike): Boolean = {
    val startPcpTime = f.apiFlight.PcpTime
    val endPcpTime = f.apiFlight.PcpTime + (ArrivalHelper.bestPax(f.apiFlight) / 20) * oneMinuteMillis
    crunchStart.millisSinceEpoch <= endPcpTime && startPcpTime < crunchEnd.millisSinceEpoch
  }

  def isNewerThan(thresholdMillis: MillisSinceEpoch, vm: VoyageManifest): Boolean = {
    vm.scheduleArrivalDateTime match {
      case None => false
      case Some(sch) => sch.millisSinceEpoch > thresholdMillis
    }
  }

  def twoDaysAgo: MillisSinceEpoch = {
    SDate.now().millisSinceEpoch - (2 * oneDayMillis)
  }
}

