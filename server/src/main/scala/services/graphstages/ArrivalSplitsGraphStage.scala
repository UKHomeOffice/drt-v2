package services.graphstages

import akka.stream._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.SplitRatiosNs.SplitSources
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.core.SplitsCalculator
import passengersplits.parsing.VoyageManifestParser.{VoyageManifest, VoyageManifests}
import services._
import services.graphstages.Crunch.{log, _}

import scala.collection.immutable.Map
import scala.language.postfixOps

case class ArrivalsDiff(toUpdate: Set[Arrival], toRemove: Set[Int])

class ArrivalSplitsGraphStage(optionalInitialFlights: Option[FlightsWithSplits],
                              splitsCalculator: SplitsCalculator,
                              expireAfterMillis: Long,
                              now: () => SDateLike,
                              maxDaysToCrunch: Int)
  extends GraphStage[FanInShape3[ArrivalsDiff, VoyageManifests, Seq[(Arrival, Option[ApiSplits])], FlightsWithSplits]] {

  val inArrivalsDiff: Inlet[ArrivalsDiff] = Inlet[ArrivalsDiff]("ArrivalsDiffIn.in")
  val inManifests: Inlet[VoyageManifests] = Inlet[VoyageManifests]("SplitsIn.in")
  val inSplitsPredictions: Inlet[Seq[(Arrival, Option[ApiSplits])]] = Inlet[Seq[(Arrival, Option[ApiSplits])]]("SplitsPredictionsIn.in")
  val arrivalsWithSplitsOut: Outlet[FlightsWithSplits] = Outlet[FlightsWithSplits]("ArrivalsWithSplitsOut.out")

  override val shape = new FanInShape3(inArrivalsDiff, inManifests, inSplitsPredictions, arrivalsWithSplitsOut)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    var flightsByFlightId: Map[Int, ApiFlightWithSplits] = Map()
    var manifestsBuffer: Map[String, Set[VoyageManifest]] = Map()
    var arrivalsWithSplitsDiff: Set[ApiFlightWithSplits] = Set()

    val log: Logger = LoggerFactory.getLogger(getClass)

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

    setHandler(arrivalsWithSplitsOut, new OutHandler {
      override def onPull(): Unit = {
        log.debug(s"arrivalsWithSplitsOut onPull called")
        pushStateIfReady()
        pullAll()
      }
    })

    setHandler(inArrivalsDiff, new InHandler {
      override def onPush(): Unit = {
        log.debug(s"inFlights onPush called")
        val arrivalsDiff = grab(inArrivalsDiff)

        log.info(s"Grabbed ${arrivalsDiff.toUpdate.size} updates, ${arrivalsDiff.toRemove.size} removals")

        val updatedFlights = purgeExpiredArrivals(updateFlightsFromIncoming(arrivalsDiff, flightsByFlightId))
        arrivalsWithSplitsDiff = updatedFlights.values.toSet -- flightsByFlightId.values.toSet
        flightsByFlightId = updatedFlights

        pushStateIfReady()
        pullAll()
      }
    })

    setHandler(inManifests, new InHandler {
      override def onPush(): Unit = {
        log.debug(s"inSplits onPush called")
        val vms = grab(inManifests)

        log.info(s"Grabbed ${vms.manifests.size} manifests")
        val updatedFlights = updateFlightsWithManifests(vms.manifests, flightsByFlightId)
        arrivalsWithSplitsDiff = updatedFlights.values.toSet -- flightsByFlightId.values.toSet
        flightsByFlightId = updatedFlights
        manifestsBuffer = purgeExpiredManifests(manifestsBuffer)

        pushStateIfReady()
        pullAll()
      }
    })

    setHandler(inSplitsPredictions, new InHandler {
      override def onPush(): Unit = {
        log.debug(s"inSplitsPredictions onPush called")
        val predictions = grab(inSplitsPredictions)

        log.info(s"Grabbed ${predictions.length} predictions")
        val updatedFlights = addPredictions(predictions, flightsByFlightId)
        arrivalsWithSplitsDiff = updatedFlights.values.toSet -- flightsByFlightId.values.toSet
        flightsByFlightId = updatedFlights

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

    def addPredictions(predictions: Seq[(Arrival, Option[ApiSplits])], flightsById: Map[Int, ApiFlightWithSplits]): Map[Int, ApiFlightWithSplits] = {
      predictions
        .collect {
          case (arrival, Some(splits)) => (arrival, splits)
        }
        .foldLeft(flightsById) {
          case (existingFlightsByFlightId, (arrivalForPrediction, predictedSplits)) =>
            existingFlightsByFlightId.find {
              case (_, ApiFlightWithSplits(existingArrival, _, _)) => existingArrival.uniqueId == arrivalForPrediction.uniqueId
            } match {
              case Some((id, existingFlightWithSplits)) =>
                val newSplitsSet: Set[ApiSplits] = updateSplitsSet(arrivalForPrediction, predictedSplits, existingFlightWithSplits)
                existingFlightsByFlightId.updated(id, existingFlightWithSplits.copy(splits = newSplitsSet))
              case None =>
                existingFlightsByFlightId
            }
        }
    }

    def updateSplitsSet(arrivalForPrediction: Arrival, predictedSplits: ApiSplits, existingFlightWithSplits: ApiFlightWithSplits): Set[ApiSplits] = {
      val predictedSplitsWithPaxNumbers = predictedSplits.copy(splits = predictedSplits.splits.map(s => s.copy(paxCount = s.paxCount * ArrivalHelper.bestPax(arrivalForPrediction))), splitStyle = PaxNumbers)
      val predictedWithEgatesAndFt = predictedSplitsWithPaxNumbers.copy(splits = splitsCalculator.addEgatesAndFastTrack(arrivalForPrediction, predictedSplitsWithPaxNumbers.splits))
      val newSplitsSet = existingFlightWithSplits.splits.filterNot {
        case ApiSplits(_, SplitSources.PredictedSplitsWithHistoricalEGateAndFTPercentages, _, _) => true
        case _ => false
      } + predictedWithEgatesAndFt
      newSplitsSet
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

    def pushStateIfReady(): Unit = {
      if (arrivalsWithSplitsDiff.nonEmpty) {
        log.info(s"Pushing ${arrivalsWithSplitsDiff.size} updated arrivals with splits")
        push(arrivalsWithSplitsOut, arrivalsWithSplitsDiff)
        arrivalsWithSplitsDiff = Set()
      } else log.info(s"No updated arrivals with splits to push")
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

