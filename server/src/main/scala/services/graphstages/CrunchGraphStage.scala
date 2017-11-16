package services.graphstages

import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.stream.{Attributes, FanInShape2, Inlet, Outlet}
import controllers.SystemActors.SplitsProvider
import drt.shared.CrunchApi.{CrunchMinute, MillisSinceEpoch, PortState}
import drt.shared.FlightsApi.{FlightsWithSplits, QueueName, TerminalName}
import drt.shared.PassengerSplits.{PaxTypeAndQueueCounts, SplitsPaxTypeAndQueueCount}
import drt.shared.PaxTypes.{EeaMachineReadable, NonVisaNational, VisaNational}
import drt.shared.Queues.{EGate, EeaDesk}
import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios, SplitSources}
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.core.PassengerQueueCalculator
import passengersplits.parsing.VoyageManifestParser.{VoyageManifest, VoyageManifests}
import services._
import services.graphstages.Crunch.{log, _}
import services.workloadcalculator.PaxLoadCalculator.{Load, minutesForHours, paxDeparturesPerMinutes, paxOffFlowRate}

import scala.collection.immutable.{Map, Seq}
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

case class ArrivalsDiff(toUpdate: Set[Arrival], toRemove: Set[Int])

class CrunchGraphStage(name: String,
                       optionalInitialFlights: Option[FlightsWithSplits],
                       portCode: String,
                       slas: Map[QueueName, Int],
                       minMaxDesks: Map[TerminalName, Map[QueueName, (List[Int], List[Int])]],
                       procTimes: Map[PaxTypeAndQueue, Double],
                       groupFlightsByCodeShares: (Seq[ApiFlightWithSplits]) => List[(ApiFlightWithSplits, Set[Arrival])],
                       portSplits: SplitRatios,
                       csvSplitsProvider: SplitsProvider,
                       crunchStartFromFirstPcp: (SDateLike) => SDateLike = getLocalLastMidnight,
                       crunchEndFromLastPcp: (SDateLike) => SDateLike = (_) => getLocalNextMidnight(SDate.now()),
                       earliestAndLatestAffectedPcpTime: (Set[ApiFlightWithSplits], Set[ApiFlightWithSplits]) => Option[(SDateLike, SDateLike)],
                       expireAfterMillis: Long,
                       now: () => SDateLike,
                       maxDaysToCrunch: Int,
                       manifestsUsed: Boolean = true,
                       minutesToCrunch: Int,
                       warmUpMinutes: Int)
  extends GraphStage[FanInShape2[ArrivalsDiff, VoyageManifests, PortState]] {

  val inArrivalsDiff: Inlet[ArrivalsDiff] = Inlet[ArrivalsDiff]("ArrivalsDiffIn.in")
  val inManifests: Inlet[VoyageManifests] = Inlet[VoyageManifests]("SplitsIn.in")
  val outCrunch: Outlet[PortState] = Outlet[PortState]("PortStateOut.out")
  override val shape = new FanInShape2(inArrivalsDiff, inManifests, outCrunch)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    var flightsByFlightId: Map[Int, ApiFlightWithSplits] = Map()
    var manifestsBuffer: Map[String, Set[VoyageManifest]] = Map()
    var waitingForArrivals: Boolean = true
    var waitingForManifests: Boolean = manifestsUsed

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
        if (!waitingForManifests && !waitingForArrivals) {
          portStateOption match {
            case Some(portState) =>
              log.info(s"Pushing PortState: ${portState.crunchMinutes.size} cms, ${portState.staffMinutes.size} sms, ${portState.flights.size} fts")
              push(outCrunch, portState)
              portStateOption = None
            case None =>
              log.debug(s"No PortState to push")
          }
        } else {
          if (waitingForArrivals) log.info(s"Waiting for arrivals")
          if (waitingForManifests) log.info(s"Waiting for manifests")
        }
        if (!hasBeenPulled(inManifests)) pull(inManifests)
        if (!hasBeenPulled(inArrivalsDiff)) pull(inArrivalsDiff)
      }
    })

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
        } else log.info(s"No flight updates")

        if (!hasBeenPulled(inArrivalsDiff)) pull(inArrivalsDiff)
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
        } else log.info(s"No splits updates")

        if (!hasBeenPulled(inManifests)) pull(inManifests)
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
          portStateOption = crunch(updatedFlights, crunchStart, crunchEnd)
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
              val ths = terminalAndHistoricSplits(updatedFlight)
              val newFlightWithSplits = ApiFlightWithSplits(updatedFlight, ths, Option(SDate.now().millisSinceEpoch))
              val newFlightWithAvailableSplits = addApiSplitsIfAvailable(newFlightWithSplits)
              (updates, additions + 1, flightsSoFar.updated(updatedFlight.uniqueId, newFlightWithAvailableSplits))

            case Some(existingFlight) if existingFlight.apiFlight != updatedFlight =>
              (updates + 1, additions, flightsSoFar.updated(updatedFlight.uniqueId, existingFlight.copy(apiFlight = updatedFlight)))
          }
      }

      log.info(s"${updatedFlights.size} flights after updates. $updatesCount updates & $additionsCount additions")

      val minusExpired = purgeExpiredArrivals(updatedFlights)
      log.info(s"${minusExpired.size} flights after removing expired")

      minusExpired
    }

    def addApiSplitsIfAvailable(newFlightWithSplits: ApiFlightWithSplits): ApiFlightWithSplits = {
      val arrival = newFlightWithSplits.apiFlight
      val vmIdx = s"${Crunch.flightVoyageNumberPadded(arrival)}-${arrival.Scheduled}"

      val newFlightWithAvailableSplits = manifestsBuffer.get(vmIdx) match {
        case None => newFlightWithSplits
        case Some(vm) =>
          manifestsBuffer = manifestsBuffer.filterNot { case (idx, _) => idx == vmIdx }
          log.info(s"Found buffered manifest to apply to new flight, and removed from buffer")
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
          val vmMillis = newManifest.scheduleArrivalDateTime match {
            case None => 0L
            case Some(scheduled) => scheduled.millisSinceEpoch
          }
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
              log.info(s"Stashing VoyageManifest in case flight is seen later")
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
      val flightsInCrunchWindow = flights.values.toList.filter(f => isFlightInTimeWindow(f, start, crunchEnd))
      log.info(s"Requesting crunch for ${flightsInCrunchWindow.length} flights after flights update")
      val uniqueFlights = groupFlightsByCodeShares(flightsInCrunchWindow).map(_._1)
      log.info(s"${uniqueFlights.length} unique flights after filtering for code shares")
      val newFlightSplitMinutesByFlight = flightsToFlightSplitMinutes(procTimes)(uniqueFlights)
      val earliestMinute: FlightSplitMinute = newFlightSplitMinutesByFlight.values.flatMap(_.map(identity)).toList.minBy(_.minute)
      log.info(s"Earliest flight split minute: ${SDate(earliestMinute.minute).toLocalDateTimeString()}")
      val numberOfMinutes = ((crunchEnd.millisSinceEpoch - crunchStart.millisSinceEpoch) / 60000).toInt
      log.info(s"Crunching $numberOfMinutes minutes")
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
            }
        }
      } else {
        if (waitingForArrivals) log.info(s"Waiting for arrivals")
        if (waitingForManifests) log.info(s"Waiting for manifests")
      }
    }

    def crunchFlightSplitMinutes(crunchStart: MillisSinceEpoch, numberOfMinutes: Int, flightSplitMinutesByFlight: Map[Int, Set[FlightSplitMinute]]): Map[Int, CrunchMinute] = {
      val qlm: Set[QueueLoadMinute] = flightSplitMinutesToQueueLoadMinutes(flightSplitMinutesByFlight)
      val wlByQueue: Map[TerminalName, Map[QueueName, Map[MillisSinceEpoch, (Load, Load)]]] = indexQueueWorkloadsByMinute(qlm)

      val fullWlByTerminalAndQueue = queueMinutesForPeriod(crunchStart - warmUpMinutes * oneMinuteMillis, numberOfMinutes + warmUpMinutes)(wlByQueue)
      val eGateBankSize = 5

      workloadsToCrunchMinutes(warmUpMinutes, fullWlByTerminalAndQueue, slas, minMaxDesks, eGateBankSize)
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
          log.info(s"Crunch failed: $t")
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

    def indexQueueWorkloadsByMinute(queueWorkloadMinutes: Set[QueueLoadMinute]): Map[TerminalName, Map[QueueName, Map[MillisSinceEpoch, (Double, Double)]]] = {
      val portLoads = queueWorkloadMinutes.groupBy(_.terminalName)

      portLoads.mapValues(terminalLoads => {
        val queueLoads = terminalLoads.groupBy(_.queueName)
        queueLoads
          .mapValues(_.map(qwl =>
            (qwl.minute, (qwl.paxLoad, qwl.workLoad))
          ).toMap)
      })
    }

    def flightsToFlightSplitMinutes(procTimes: Map[PaxTypeAndQueue, Double])(flightsWithSplits: List[ApiFlightWithSplits]): Map[Int, Set[FlightSplitMinute]] = {
      flightsWithSplits.map {
        case ApiFlightWithSplits(flight, splits, _) => (flight.uniqueId, flightToFlightSplitMinutes(flight, splits, procTimes))
      }.toMap
    }

    def flightToFlightSplitMinutes(flight: Arrival,
                                   splits: Set[ApiSplits],
                                   procTimes: Map[PaxTypeAndQueue, Double]): Set[FlightSplitMinute] = {
      val apiSplitsDc = splits.find(s => s.source == SplitSources.ApiSplitsWithCsvPercentage && s.eventType.contains(DqEventCodes.DepartureConfirmed))
      val apiSplitsCi = splits.find(s => s.source == SplitSources.ApiSplitsWithCsvPercentage && s.eventType.contains(DqEventCodes.CheckIn))
      val historicalSplits = splits.find(_.source == SplitSources.Historical)
      val terminalSplits = splits.find(_.source == SplitSources.TerminalAverage)

      val splitsToUseOption: Option[ApiSplits] = List(apiSplitsDc, apiSplitsCi, historicalSplits, terminalSplits).find {
        case Some(_) => true
        case _ => false
      }.getOrElse {
        log.error(s"Couldn't find terminal splits from AirportConfig to fall back on...")
        None
      }

      splitsToUseOption.map(splitsToUse => {
        val totalPax = splitsToUse.splitStyle match {
          case UndefinedSplitStyle => 0
          case _ => ArrivalHelper.bestPax(flight)
        }
        val splitRatios: Set[ApiPaxTypeAndQueueCount] = splitsToUse.splitStyle match {
          case UndefinedSplitStyle => splitsToUse.splits.map(qc => qc.copy(paxCount = 0))
          case PaxNumbers => {
            val splitsWithoutTransit = splitsToUse.splits.filter(_.queueType != Queues.Transfer)
            val totalSplitsPax = splitsWithoutTransit.toList.map(_.paxCount).sum
            splitsWithoutTransit.map(qc => qc.copy(paxCount = qc.paxCount / totalSplitsPax))
          }
          case _ => splitsToUse.splits.map(qc => qc.copy(paxCount = qc.paxCount / 100))
        }

        minutesForHours(flight.PcpTime, 1)
          .zip(paxDeparturesPerMinutes(totalPax.toInt, paxOffFlowRate))
          .flatMap {
            case (minuteMillis, flightPaxInMinute) =>
              splitRatios
                .filterNot(_.queueType == Queues.Transfer)
                .map(apiSplit => flightSplitMinute(flight, procTimes, minuteMillis, flightPaxInMinute, apiSplit, Percentage))
          }.toSet
      }).getOrElse(Set())
    }

    def flightSplitMinute(flight: Arrival,
                          procTimes: Map[PaxTypeAndQueue, Load],
                          minuteMillis: MillisSinceEpoch,
                          flightPaxInMinute: Int,
                          apiSplitRatio: ApiPaxTypeAndQueueCount,
                          splitStyle: SplitStyle): FlightSplitMinute = {
      val splitPaxInMinute = apiSplitRatio.paxCount * flightPaxInMinute
      val splitWorkLoadInMinute = splitPaxInMinute * procTimes(PaxTypeAndQueue(apiSplitRatio.passengerType, apiSplitRatio.queueType))
      FlightSplitMinute(flight.uniqueId, apiSplitRatio.passengerType, flight.Terminal, apiSplitRatio.queueType, splitPaxInMinute, splitWorkLoadInMinute, minuteMillis)
    }

    def flightSplitMinutesToQueueLoadMinutes(flightToFlightSplitMinutes: Map[Int, Set[FlightSplitMinute]]): Set[QueueLoadMinute] = {
      flightToFlightSplitMinutes
        .values
        .flatten
        .groupBy(s => (s.terminalName, s.queueName, s.minute)).map {
        case ((terminalName, queueName, minute), fsms) =>
          val paxLoad = fsms.map(_.paxLoad).sum
          val workLoad = fsms.map(_.workLoad).sum
          QueueLoadMinute(terminalName, queueName, paxLoad, workLoad, minute)
      }.toSet
    }

    def terminalAndHistoricSplits(fs: Arrival): Set[ApiSplits] = {
      val historical: Option[Set[ApiPaxTypeAndQueueCount]] = historicalSplits(fs)
      val splitRatios: Set[SplitRatio] = portSplits.splits.toSet
      val portDefault: Set[ApiPaxTypeAndQueueCount] = splitRatios.map {
        case SplitRatio(ptqc, ratio) => ApiPaxTypeAndQueueCount(ptqc.passengerType, ptqc.queueType, ratio)
      }

      val defaultSplits = Set(ApiSplits(portDefault.map(aptqc => aptqc.copy(paxCount = aptqc.paxCount * 100)), SplitSources.TerminalAverage, None, Percentage))

      historical match {
        case None => defaultSplits
        case Some(h) => Set(ApiSplits(h, SplitSources.Historical, None, Percentage)) ++ defaultSplits
      }
    }

    def historicalSplits(fs: Arrival): Option[Set[ApiPaxTypeAndQueueCount]] = {
      csvSplitsProvider(fs).map(ratios => {
        val splitRatios: Set[SplitRatio] = ratios.splits.toSet
        splitRatios.map {
          case SplitRatio(ptqc, ratio) => ApiPaxTypeAndQueueCount(ptqc.passengerType, ptqc.queueType, ratio * 100)
        }
      })
    }

    def fastTrackPercentagesFromSplit(splitOpt: Option[SplitRatios], defaultVisaPct: Double, defaultNonVisaPct: Double): FastTrackPercentages = {
      val visaNational = splitOpt
        .map {
          ratios =>

            val splits = ratios.splits
            val visaNationalSplits = splits.filter(s => s.paxType.passengerType == PaxTypes.VisaNational)

            val totalVisaNationalSplit = visaNationalSplits.map(_.ratio).sum

            splits
              .find(p => p.paxType.passengerType == PaxTypes.VisaNational && p.paxType.queueType == Queues.FastTrack)
              .map(_.ratio / totalVisaNationalSplit).getOrElse(defaultVisaPct)
        }.getOrElse(defaultVisaPct)

      val nonVisaNational = splitOpt
        .map {
          ratios =>
            val splits = ratios.splits
            val totalNonVisaNationalSplit = splits.filter(s => s.paxType.passengerType == PaxTypes.NonVisaNational).map(_.ratio).sum

            splits
              .find(p => p.paxType.passengerType == PaxTypes.NonVisaNational && p.paxType.queueType == Queues.FastTrack)
              .map(_.ratio / totalNonVisaNationalSplit).getOrElse(defaultNonVisaPct)
        }.getOrElse(defaultNonVisaPct)
      FastTrackPercentages(visaNational, nonVisaNational)
    }

    def egatePercentageFromSplit(splitOpt: Option[SplitRatios], defaultPct: Double): Double = {
      splitOpt
        .map { x =>
          val splits = x.splits
          val interestingSplits = splits.filter(s => s.paxType.passengerType == PaxTypes.EeaMachineReadable)
          val interestingSplitsTotal = interestingSplits.map(_.ratio).sum
          splits
            .find(p => p.paxType.queueType == Queues.EGate)
            .map(_.ratio / interestingSplitsTotal).getOrElse(defaultPct)
        }.getOrElse(defaultPct)
    }

    def applyEgatesSplits(ptaqc: Set[ApiPaxTypeAndQueueCount], egatePct: Double): Set[ApiPaxTypeAndQueueCount] = {
      ptaqc.flatMap {
        case s@ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, count) =>
          val eeaDeskPax = Math.round(count * (1 - egatePct)).toInt
          s.copy(queueType = EGate, paxCount = count - eeaDeskPax) ::
            s.copy(queueType = EeaDesk, paxCount = eeaDeskPax) :: Nil
        case s => s :: Nil
      }
    }

    def applyFastTrackSplits(ptaqc: Set[ApiPaxTypeAndQueueCount], fastTrackPercentages: FastTrackPercentages): Set[ApiPaxTypeAndQueueCount] = {
      val results = ptaqc.flatMap {
        case s@ApiPaxTypeAndQueueCount(NonVisaNational, Queues.NonEeaDesk, count) if fastTrackPercentages.nonVisaNational != 0 =>
          val nonVisaNationalNonEeaDesk = Math.round(count * (1 - fastTrackPercentages.nonVisaNational)).toInt
          s.copy(queueType = Queues.FastTrack, paxCount = count - nonVisaNationalNonEeaDesk) ::
            s.copy(paxCount = nonVisaNationalNonEeaDesk) :: Nil
        case s@ApiPaxTypeAndQueueCount(VisaNational, Queues.NonEeaDesk, count) if fastTrackPercentages.visaNational != 0 =>
          val visaNationalNonEeaDesk = Math.round(count * (1 - fastTrackPercentages.visaNational)).toInt
          s.copy(queueType = Queues.FastTrack, paxCount = count - visaNationalNonEeaDesk) ::
            s.copy(paxCount = visaNationalNonEeaDesk) :: Nil
        case s => s :: Nil
      }
      log.debug(s"applied fastTrack $fastTrackPercentages got $ptaqc")
      results
    }

    def updateFlightWithManifests(manifests: Set[VoyageManifest], f: ApiFlightWithSplits): ApiFlightWithSplits = {
      manifests.foldLeft(f) {
        case (flightSoFar, manifest) => updateFlightWithManifest(flightSoFar, manifest)
      }
    }

    def updateFlightWithManifest(flightSoFar: ApiFlightWithSplits, manifest: VoyageManifest): ApiFlightWithSplits = {
      val splitsFromManifest = paxTypeAndQueueCounts(manifest, flightSoFar)

      val updatedSplitsSet = flightSoFar.splits.filterNot {
        case ApiSplits(_, SplitSources.ApiSplitsWithCsvPercentage, Some(manifest.EventCode), _) => true
        case _ => false
      } + splitsFromManifest

      flightSoFar.copy(splits = updatedSplitsSet)
    }

    def paxTypeAndQueueCounts(manifest: VoyageManifest, f: ApiFlightWithSplits): ApiSplits = {
      val paxTypeAndQueueCounts: PaxTypeAndQueueCounts = PassengerQueueCalculator.convertVoyageManifestIntoPaxTypeAndQueueCounts(portCode, manifest)
      val sptqc: Set[SplitsPaxTypeAndQueueCount] = paxTypeAndQueueCounts.toSet
      val apiPaxTypeAndQueueCounts: Set[ApiPaxTypeAndQueueCount] = sptqc.map(ptqc => ApiPaxTypeAndQueueCount(ptqc.passengerType, ptqc.queueType, ptqc.paxCount))
      val withEgateAndFastTrack = addEgatesAndFastTrack(f, apiPaxTypeAndQueueCounts)
      val splitsFromManifest = ApiSplits(withEgateAndFastTrack, SplitSources.ApiSplitsWithCsvPercentage, Some(manifest.EventCode), PaxNumbers)

      splitsFromManifest
    }

    def addEgatesAndFastTrack(f: ApiFlightWithSplits, apiPaxTypeAndQueueCounts: Set[ApiPaxTypeAndQueueCount]): Set[ApiPaxTypeAndQueueCount] = {
      val csvSplits = csvSplitsProvider(f.apiFlight)
      val egatePercentage: Load = egatePercentageFromSplit(csvSplits, 0.6)
      val fastTrackPercentages: FastTrackPercentages = fastTrackPercentagesFromSplit(csvSplits, 0d, 0d)
      val ptqcWithCsvEgates = applyEgatesSplits(apiPaxTypeAndQueueCounts, egatePercentage)
      val ptqcwithCsvEgatesFastTrack = applyFastTrackSplits(ptqcWithCsvEgates, fastTrackPercentages)
      ptqcwithCsvEgatesFastTrack
    }
  }

  def purgeExpiredManifests(manifests: Map[String, Set[VoyageManifest]]): Map[String, Set[VoyageManifest]] = {
    val expired = hasExpiredForType((m: VoyageManifest) => m.scheduleArrivalDateTime.getOrElse(SDate.now()).millisSinceEpoch)
    val updated = manifests
      .mapValues(_.filterNot(expired))
      .filterNot { case (_, ms) => ms.isEmpty }
    log.info(s"Purged ${manifests.size - updated.size} expired manifests")
    updated
  }

  def purgeExpiredArrivals(arrivals: Map[Int, ApiFlightWithSplits]): Map[Int, ApiFlightWithSplits] = {
    val expired = hasExpiredForType((a: ApiFlightWithSplits) => a.apiFlight.PcpTime)
    val updated = arrivals.filterNot { case (_, a) => expired(a) }
    log.info(s"Purged ${arrivals.size - updated.size} expired arrivals")
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
