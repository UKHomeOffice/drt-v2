package services

import akka.stream.{Attributes, FanInShape2, Inlet, Outlet}
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import controllers.SystemActors.SplitsProvider
import drt.shared.FlightsApi.{Flights, QueueName, TerminalName}
import drt.shared.PassengerQueueTypes.PaxTypeAndQueueCounts
import drt.shared.PassengerSplits.SplitsPaxTypeAndQueueCount
import drt.shared.PaxTypes.{EeaMachineReadable, NonVisaNational, VisaNational}
import drt.shared.Queues.{EGate, EeaDesk}
import drt.shared._
import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios, SplitSources}
import org.joda.time.{DateTime, DateTimeZone}
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.core.PassengerQueueCalculator
import passengersplits.parsing.VoyageManifestParser.{VoyageManifest, VoyageManifests}
import services.CSVPassengerSplitsProvider.log
import services.Crunch._
import services.workloadcalculator.PaxLoadCalculator.{Load, MillisSinceEpoch}

import scala.Option
import scala.concurrent.ExecutionContext.Implicits.global
import scala.collection.immutable.{Map, Seq}
import scala.concurrent.Future

class CrunchGraphStage(initialFlightsFuture: Future[List[ApiFlightWithSplits]],
                       slas: Map[QueueName, Int],
                       minMaxDesks: Map[TerminalName, Map[QueueName, (List[Int], List[Int])]],
                       procTimes: Map[PaxTypeAndQueue, Double],
                       groupFlightsByCodeShares: (Seq[ApiFlightWithSplits]) => List[(ApiFlightWithSplits, Set[Arrival])],
                       validPortTerminals: Set[String],
                       portSplits: SplitRatios,
                       csvSplitsProvider: SplitsProvider,
                       pcpArrivalTime: (Arrival) => MilliDate,
                       crunchStartDateProvider: () => MillisSinceEpoch = midnightThisMorning,
                       minutesToCrunch: Int
                      )
  extends GraphStage[FanInShape2[Flights, VoyageManifests, CrunchState]] {

  val inFlights: Inlet[Flights] = Inlet[Flights]("Flights.in")
  val inSplits: Inlet[VoyageManifests] = Inlet[VoyageManifests]("Splits.in")
  val out: Outlet[CrunchState] = Outlet[CrunchState]("CrunchState.out")
  override val shape = new FanInShape2(inFlights, inSplits, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    var initialised: Boolean = false
    var flightsByFlightId: Map[Int, ApiFlightWithSplits] = Map()
    var flightSplitMinutesByFlight: Map[Int, Set[FlightSplitMinute]] = Map()
    var manifestsBuffer: Map[String, Set[VoyageManifest]] = Map()

    var crunchStateOption: Option[CrunchState] = None
    var crunchRunning = false

    val log: Logger = LoggerFactory.getLogger("CrunchGraphStage")

    initialFlightsFuture.onSuccess {
      case flights =>
        log.info(s"Received initial flights. Setting ${flights.size}")
        flightsByFlightId = flights.map(f => Tuple2(f.apiFlight.FlightID, f)).toMap
        initialised = true
    }

    setHandler(out, new OutHandler {
      override def onPull(): Unit = {
        log.info(s"onPull called")
        if (initialised) {
          crunchStateOption match {
            case Some(crunchState) =>
              push(out, crunchState)
              crunchStateOption = None
            case None =>
              log.info(s"No CrunchState to push")
          }
          if (!hasBeenPulled(inSplits)) pull(inSplits)
          if (!hasBeenPulled(inFlights)) pull(inFlights)
        } else log.info("Waiting to be initialised")
      }
    })

    setHandler(inFlights, new InHandler {
      override def onPush(): Unit = {
        val incomingFlights = grab(inFlights)

        log.info(s"Grabbed flights $incomingFlights")
        val updatedFlights = updateFlightsFromIncoming(incomingFlights, flightsByFlightId)

        if (flightsByFlightId != updatedFlights) {
          flightsByFlightId = updatedFlights
          log.info(s"Requesting crunch for ${flightsByFlightId.size} flights after flights update")
          crunchAndUpdateState(flightsByFlightId.values.toList)
        }
        else {
          log.info(s"No flight updates")
        }

        if (!hasBeenPulled(inFlights)) pull(inFlights)
      }
    })

    def updateFlightsFromIncoming(incomingFlights: Flights, existingFlightsById: Map[Int, ApiFlightWithSplits]): Map[Int, ApiFlightWithSplits] = {
      val updatedFlights = incomingFlights.flights.foldLeft[Map[Int, ApiFlightWithSplits]](existingFlightsById) {
        case (flightsSoFar, updatedFlight) =>
          val updatedFlightWithPcp = updatedFlight.copy(PcpTime = pcpArrivalTime(updatedFlight).millisSinceEpoch)
          flightsSoFar.get(updatedFlightWithPcp.FlightID) match {
            case None =>
              log.info(s"Adding new flight ${updatedFlightWithPcp.IATA}")

              val ths = terminalAndHistoricSplits(updatedFlightWithPcp)
              val newFlightWithSplits = ApiFlightWithSplits(updatedFlightWithPcp, ths)
              val newFlightWithAvailableSplits = addApiSplitsIfAvailable(newFlightWithSplits)
              flightsSoFar.updated(updatedFlightWithPcp.FlightID, newFlightWithAvailableSplits)
            case Some(existingFlight) if existingFlight.apiFlight != updatedFlightWithPcp =>
              log.info(s"Updating flight ${updatedFlightWithPcp.IATA}")
              flightsSoFar.updated(updatedFlightWithPcp.FlightID, existingFlight.copy(apiFlight = updatedFlightWithPcp))
            case _ =>
              log.info(s"No update to flight ${updatedFlightWithPcp.IATA}")
              flightsSoFar
          }
      }
      updatedFlights
    }

    def addApiSplitsIfAvailable(newFlightWithSplits: ApiFlightWithSplits) = {
      val arrival = newFlightWithSplits.apiFlight
      val vmIdx = s"${Crunch.flightVoyageNumberPadded(arrival)}-${arrival.Scheduled}"

      val newFlightWithAvailableSplits = manifestsBuffer.get(vmIdx) match {
        case None => newFlightWithSplits
        case Some(vm) =>
          log.info(s"Found buffered manifest to apply to new flight")
          manifestsBuffer = manifestsBuffer.filterNot { case (idx, _) => idx == vmIdx }
          log.info(s"Removed applied manifest from buffer")
          removeManifestsOlderThan(twoDaysAgo)
          updateFlightWithManifests(vm, newFlightWithSplits)
      }
      newFlightWithAvailableSplits
    }

    private def removeManifestsOlderThan(thresholdMillis: MillisSinceEpoch) = {
      manifestsBuffer = manifestsBuffer.filter {
        case (_, vmsInBuffer) =>
          val vmsToKeep = vmsInBuffer.filter { case vm => isNewerThan(thresholdMillis, vm) }
          vmsToKeep match {
            case vms if vms.nonEmpty => true
            case _ => false
          }
      }
    }

    setHandler(inSplits, new InHandler {
      override def onPush(): Unit = {
        val vms = grab(inSplits)

        log.info(s"Grabbed manifests $vms")
        val updatedFlights = updateFlightsWithManifests(vms.manifests, flightsByFlightId)

        if (flightsByFlightId != updatedFlights) {
          flightsByFlightId = updatedFlights
          log.info(s"Requesting crunch for ${flightsByFlightId.size} flights after splits update")
          crunchAndUpdateState(flightsByFlightId.values.toList)
        } else log.info(s"No splits updates")

        if (!hasBeenPulled(inSplits)) pull(inSplits)
      }
    })

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

    def crunchAndUpdateState(flightsToCrunch: List[ApiFlightWithSplits]): Unit = {
      crunchRunning = true

      val crunchRequest = CrunchRequest(flightsToCrunch, crunchStartDateProvider(), minutesToCrunch)

      log.info(s"processing crunchRequest - ${crunchRequest.flights.length} flights")
      val newCrunchStateOption = crunch(crunchRequest)

      log.info(s"setting crunchStateOption")
      crunchStateOption = newCrunchStateOption

      pushStateIfReady()

      crunchRunning = false
    }

    def crunch(crunchRequest: CrunchRequest): Option[CrunchState] = {
      log.info(s"CrunchRequestFlights: ${crunchRequest.flights.length}")
      val relevantFlights = crunchRequest.flights.filter {
        case ApiFlightWithSplits(flight, _) =>
          validPortTerminals.contains(flight.Terminal) &&
            (flight.PcpTime) >= crunchRequest.crunchStart &&
            !domesticPorts.contains(flight.Origin)
      }
      val uniqueFlights = groupFlightsByCodeShares(relevantFlights).map(_._1)
      log.info(s"${uniqueFlights.length} unique flights to crunch")
      val newFlightsById = uniqueFlights.map(f => (f.apiFlight.FlightID, f)).toMap
      val newFlightSplitMinutesByFlight = flightsToFlightSplitMinutes(procTimes)(uniqueFlights)
      val crunchStart = crunchRequest.crunchStart
      val numberOfMinutes = crunchRequest.numberOfMinutes
      val crunchEnd = crunchStart + (numberOfMinutes * Crunch.oneMinuteMillis)
      val flightSplitDiffs = flightsToSplitDiffs(flightSplitMinutesByFlight, newFlightSplitMinutesByFlight)
        .filter {
          case FlightSplitDiff(_, _, _, _, _, _, minute) =>
            crunchStart <= minute && minute < crunchEnd
        }

      val crunchState = flightSplitDiffs match {
        case fsd if fsd.isEmpty =>
          log.info("No changes to flight workloads")
          None
        case _ =>
          log.info("Flight workloads changed, triggering crunch")
          val newCrunchState = crunchStateFromFlightSplitMinutes(crunchStart, numberOfMinutes, newFlightsById, newFlightSplitMinutesByFlight)
          Option(newCrunchState)
      }

      flightsByFlightId = newFlightsById
      flightSplitMinutesByFlight = newFlightSplitMinutesByFlight

      crunchState
    }

    def pushStateIfReady(): Unit = {
      crunchStateOption match {
        case None =>
          log.info(s"We have no state yet. Nothing to push")
        case Some(crunchState) =>
          if (isAvailable(out)) {
            log.info(s"pushing csd ${crunchState.crunchFirstMinuteMillis}")
            push(out, crunchState)
            crunchStateOption = None
          }
      }
    }

    def crunchStateFromFlightSplitMinutes(crunchStart: MillisSinceEpoch,
                                          numberOfMinutes: Int,
                                          flightsById: Map[Int, ApiFlightWithSplits],
                                          fsmsByFlightId: Map[Int, Set[FlightSplitMinute]]): CrunchState = {
      val crunchResults: Set[CrunchMinute] = crunchFlightSplitMinutes(crunchStart, numberOfMinutes, fsmsByFlightId)

      CrunchState(crunchStart, numberOfMinutes, flightsById.values.toSet, crunchResults)
    }

    def crunchFlightSplitMinutes(crunchStart: MillisSinceEpoch, numberOfMinutes: Int, flightSplitMinutesByFlight: Map[Int, Set[FlightSplitMinute]]): Set[CrunchMinute] = {
      val qlm: Set[QueueLoadMinute] = flightSplitMinutesToQueueLoadMinutes(flightSplitMinutesByFlight)
      val wlByQueue: Map[TerminalName, Map[QueueName, Map[MillisSinceEpoch, (Load, Load)]]] = indexQueueWorkloadsByMinute(qlm)

      val fullWlByQueue: Map[TerminalName, Map[QueueName, List[(MillisSinceEpoch, (Load, Load))]]] = queueMinutesForPeriod(crunchStart, numberOfMinutes)(wlByQueue)
      val eGateBankSize = 5

      val crunchResults: Set[CrunchMinute] = workloadsToCrunchMinutes(crunchStart, numberOfMinutes, fullWlByQueue, slas, minMaxDesks, eGateBankSize)
      crunchResults
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

    def updateFlightWithManifest(flightSoFar: ApiFlightWithSplits, manifest: VoyageManifest) = {
      val splitsFromManifest = paxTypeAndQueueCounts(manifest, flightSoFar)

      val updatedSplitsSet = flightSoFar.splits.filterNot {
        case ApiSplits(_, SplitSources.ApiSplitsWithCsvPercentage, Some(manifest.EventCode), _) => true
        case _ => false
      } + splitsFromManifest

      flightSoFar.copy(splits = updatedSplitsSet)
    }

    def paxTypeAndQueueCounts(manifest: VoyageManifest, f: ApiFlightWithSplits) = {
      val paxTypeAndQueueCounts: PaxTypeAndQueueCounts = PassengerQueueCalculator.convertVoyageManifestIntoPaxTypeAndQueueCounts(manifest)
      val sptqc: Set[SplitsPaxTypeAndQueueCount] = paxTypeAndQueueCounts.toSet
      val apiPaxTypeAndQueueCounts: Set[ApiPaxTypeAndQueueCount] = sptqc.map(ptqc => ApiPaxTypeAndQueueCount(ptqc.passengerType, ptqc.queueType, ptqc.paxCount))
      val withEgateAndFastTrack = addEgatesAndFastTrack(f, apiPaxTypeAndQueueCounts)
      val splitsFromManifest = ApiSplits(withEgateAndFastTrack, SplitSources.ApiSplitsWithCsvPercentage, Some(manifest.EventCode), PaxNumbers)

      splitsFromManifest
    }

    def addEgatesAndFastTrack(f: ApiFlightWithSplits, apiPaxTypeAndQueueCounts: Set[ApiPaxTypeAndQueueCount]) = {
      val csvSplits = csvSplitsProvider(f.apiFlight)
      val egatePercentage: Load = egatePercentageFromSplit(csvSplits, 0.6)
      val fastTrackPercentages: FastTrackPercentages = fastTrackPercentagesFromSplit(csvSplits, 0d, 0d)
      val ptqcWithCsvEgates = applyEgatesSplits(apiPaxTypeAndQueueCounts, egatePercentage)
      val ptqcwithCsvEgatesFastTrack = applyFastTrackSplits(ptqcWithCsvEgates, fastTrackPercentages)
      ptqcwithCsvEgatesFastTrack
    }
  }

  def isNewerThan(thresholdMillis: MillisSinceEpoch, vm: VoyageManifest) = {
    vm.scheduleArrivalDateTime match {
      case None => false
      case Some(sch) => sch.millisSinceEpoch > thresholdMillis
    }
  }

  def twoDaysAgo = {
    SDate.now().millisSinceEpoch - (2 * oneDayMillis)
  }
}
