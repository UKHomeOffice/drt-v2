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


case class UpdatedFlights(flights: Map[Int, ApiFlightWithSplits], updatesCount: Int, additionsCount: Int)

class ArrivalSplitsGraphStage(name: String = "",
                              optionalInitialFlights: Option[FlightsWithSplits],
                              splitsCalculator: SplitsCalculator,
                              groupFlightsByCodeShares: (Seq[ApiFlightWithSplits]) => Seq[(ApiFlightWithSplits, Set[Arrival])],
                              expireAfterMillis: Long,
                              now: () => SDateLike,
                              maxDaysToCrunch: Int)
  extends GraphStage[FanInShape3[ArrivalsDiff, VoyageManifests, Seq[(Arrival, Option[ApiSplits])], FlightsWithSplits]] {

  val inArrivalsDiff: Inlet[ArrivalsDiff] = Inlet[ArrivalsDiff]("ArrivalsDiffIn.in")
  val inManifests: Inlet[VoyageManifests] = Inlet[VoyageManifests]("SplitsIn.in")
  val inSplitsPredictions: Inlet[Seq[(Arrival, Option[ApiSplits])]] = Inlet[Seq[(Arrival, Option[ApiSplits])]]("SplitsPredictionsIn.in")
  val outArrivalsWithSplits: Outlet[FlightsWithSplits] = Outlet[FlightsWithSplits]("ArrivalsWithSplitsOut.out")

  override val shape = new FanInShape3(inArrivalsDiff, inManifests, inSplitsPredictions, outArrivalsWithSplits)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    var flightsByFlightId: Map[Int, ApiFlightWithSplits] = Map()
    var manifestsBuffer: Map[Int, Set[VoyageManifest]] = Map()
    var arrivalsWithSplitsDiff: Set[ApiFlightWithSplits] = Set()

    val log: Logger = LoggerFactory.getLogger(s"$getClass-$name")

    override def preStart(): Unit = {
      optionalInitialFlights match {
        case Some(FlightsWithSplits(flights)) =>
          log.info(s"Received initial flights. Setting ${flights.size}")
          flightsByFlightId = purgeExpiredArrivals(
            flights
              .map(flightWithSplits => {
                val maybeOldSplits = flightWithSplits.splits.find(s => s.source == SplitSources.Historical)
                val maybeNewSplits = splitsCalculator.historicalSplits(flightWithSplits.apiFlight)
                val withUpdatedHistoricalSplits = if (maybeNewSplits != maybeOldSplits) {
                  log.debug(s"Updating historical splits for ${flightWithSplits.apiFlight.IATA}")
                  updateFlightWithHistoricalSplits(flightWithSplits.copy(lastUpdated = nowMillis), maybeNewSplits)
                } else flightWithSplits
                Tuple2(flightWithSplits.apiFlight.uniqueId, withUpdatedHistoricalSplits)
              })
              .toMap)
        case _ =>
          log.warn(s"Did not receive any flights to initialise with")
      }

      super.preStart()
    }

    setHandler(outArrivalsWithSplits, new OutHandler {
      override def onPull(): Unit = {
        val start = SDate.now()
        log.debug(s"arrivalsWithSplitsOut onPull called")
        pushStateIfReady()
        pullAll()
        log.info(s"outArrivalsWithSplits Took ${SDate.now().millisSinceEpoch - start.millisSinceEpoch}ms")
      }
    })

    setHandler(inArrivalsDiff, new InHandler {
      override def onPush(): Unit = {
        val start = SDate.now()
        log.debug(s"inFlights onPush called")
        val arrivalsDiff = grab(inArrivalsDiff)

        log.info(s"Grabbed ${arrivalsDiff.toUpdate.size} updates, ${arrivalsDiff.toRemove.size} removals")

        val updatedFlights = purgeExpiredArrivals(updateFlightsFromIncoming(arrivalsDiff, flightsByFlightId))
        val latestDiff = updatedFlights.values.toSet -- flightsByFlightId.values.toSet
        arrivalsWithSplitsDiff = mergeDiffSets(latestDiff, arrivalsWithSplitsDiff)
        log.info(s"${arrivalsWithSplitsDiff.size} updated arrivals waiting to push")
        flightsByFlightId = updatedFlights

        pushStateIfReady()
        pullAll()
        log.info(s"inArrivalsDiff Took ${SDate.now().millisSinceEpoch - start.millisSinceEpoch}ms")
      }
    })

    setHandler(inManifests, new InHandler {
      override def onPush(): Unit = {
        val start = SDate.now()
        log.debug(s"inSplits onPush called")
        val vms = grab(inManifests)

        log.info(s"Grabbed ${vms.manifests.size} manifests")
        val updatedFlights = purgeExpiredArrivals(updateFlightsWithManifests(vms.manifests, flightsByFlightId))
        log.info(s"We now have ${updatedFlights.size} arrivals")
        val latestDiff = updatedFlights.values.toSet -- flightsByFlightId.values.toSet
        arrivalsWithSplitsDiff = mergeDiffSets(latestDiff, arrivalsWithSplitsDiff)
        flightsByFlightId = updatedFlights
        manifestsBuffer = purgeExpiredManifests(manifestsBuffer)

        pushStateIfReady()
        pullAll()
        log.info(s"inManifests Took ${SDate.now().millisSinceEpoch - start.millisSinceEpoch}ms")
      }
    })

    setHandler(inSplitsPredictions, new InHandler {
      override def onPush(): Unit = {
        val start = SDate.now()
        log.debug(s"inSplitsPredictions onPush called")
        val predictions = grab(inSplitsPredictions)

        log.info(s"Grabbed ${predictions.length} predictions")
        val updatedFlights = purgeExpiredArrivals(addPredictions(predictions, flightsByFlightId))
        val latestDiff = updatedFlights.values.toSet -- flightsByFlightId.values.toSet
        arrivalsWithSplitsDiff = mergeDiffSets(latestDiff, arrivalsWithSplitsDiff)
        flightsByFlightId = updatedFlights

        pushStateIfReady()
        pullAll()
        log.info(s"inSplitsPredictions Took ${SDate.now().millisSinceEpoch - start.millisSinceEpoch}ms")
      }
    })

    def pullAll(): Unit = {
      List(inManifests, inArrivalsDiff, inSplitsPredictions).foreach(in => if (!hasBeenPulled(in)) {
        log.info(s"Pulling ${in.toString}")
        pull(in)
      })
    }

    def addPredictions(predictions: Seq[(Arrival, Option[ApiSplits])], flightsById: Map[Int, ApiFlightWithSplits]): Map[Int, ApiFlightWithSplits] = {
      val arrivalsAndSplits = predictions
        .collect {
          case (arrival, Some(splits)) => (arrival, splits)
        }

      arrivalsAndSplits.foldLeft(flightsById) {
        case (existingFlightsByFlightId, (arrivalForPrediction, predictedSplits)) =>
          updatePredictionIfFlightExists(existingFlightsByFlightId, arrivalForPrediction, predictedSplits)
      }
    }

    def updatePredictionIfFlightExists(existingFlightsByFlightId: Map[Int, ApiFlightWithSplits], arrivalForPrediction: Arrival, predictedSplits: ApiSplits): Map[Int, ApiFlightWithSplits] = {
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

    def updateFlightWithHistoricalSplits(flightWithSplits: ApiFlightWithSplits, maybeNewHistorical: Option[Set[ApiPaxTypeAndQueueCount]]): ApiFlightWithSplits = {
      val newSplits = maybeNewHistorical.map(newHistorical =>
        ApiSplits(newHistorical, SplitSources.Historical, None, Percentage)
      ).toSet
      val updatedSplitsSet = flightWithSplits.splits.filterNot {
        case ApiSplits(_, SplitSources.Historical, _, _) => true
        case _ => false
      } ++ newSplits

      flightWithSplits.copy(splits = updatedSplitsSet)
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

      val lastMilliToCrunch = getLocalLastMidnight(now().addDays(maxDaysToCrunch)).millisSinceEpoch

      val updatedFlights = arrivalsDiff.toUpdate.foldLeft(UpdatedFlights(afterRemovals, 0, 0)) {
        case (updatesSoFar, updatedFlight) =>
          if (updatedFlight.PcpTime.getOrElse(0L) <= lastMilliToCrunch) updateWithFlight(updatesSoFar, updatedFlight)
          else updatesSoFar
      }

      log.info(s"${updatedFlights.flights.size} flights after updates. ${updatedFlights.updatesCount} updates & ${updatedFlights.additionsCount} additions")

      val minusExpired = purgeExpiredArrivals(updatedFlights.flights)

      val uniqueFlights = groupFlightsByCodeShares(minusExpired.values.toSeq)
        .map { case (fws, _) => (fws.apiFlight.uniqueId, fws) }
        .toMap

      log.info(s"${uniqueFlights.size} flights after removing expired and accounting for codeshares")

      uniqueFlights
    }

    def updateWithFlight(updatedFlights: UpdatedFlights, updatedFlight: Arrival): UpdatedFlights = {
      updatedFlights.flights.get(updatedFlight.uniqueId) match {
        case None =>
          val newFlightWithAvailableSplits: ApiFlightWithSplits = addSplitsToFlight(updatedFlight)
          val withNewFlight = updatedFlights.flights.updated(updatedFlight.uniqueId, newFlightWithAvailableSplits.copy(lastUpdated = nowMillis))
          updatedFlights.copy(flights = withNewFlight, additionsCount = updatedFlights.additionsCount + 1)

        case Some(existingFlight) if existingFlight.apiFlight != updatedFlight =>
          val withUpdatedFlight = updatedFlights.flights.updated(updatedFlight.uniqueId, existingFlight.copy(apiFlight = updatedFlight, lastUpdated = nowMillis))
          updatedFlights.copy(flights = withUpdatedFlight, updatesCount = updatedFlights.updatesCount + 1)

        case _ => updatedFlights
      }
    }

    def addSplitsToFlight(updatedFlight: Arrival): ApiFlightWithSplits = {
      val ths = splitsCalculator.terminalAndHistoricSplits(updatedFlight)
      val newFlightWithSplits = ApiFlightWithSplits(updatedFlight, ths, nowMillis)
      val newFlightWithAvailableSplits = addApiSplitsIfAvailable(newFlightWithSplits)
      newFlightWithAvailableSplits
    }

    def addApiSplitsIfAvailable(newFlightWithSplits: ApiFlightWithSplits): ApiFlightWithSplits = {
      val arrivalManifestKey = newFlightWithSplits.apiFlight.manifestKey

      val newFlightWithAvailableSplits = manifestsBuffer.get(arrivalManifestKey) match {
        case None => newFlightWithSplits
        case Some(vm) =>
          manifestsBuffer = manifestsBuffer.filterNot { case (manifestKey, _) => manifestKey == arrivalManifestKey }
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
          val maybeFlightForManifest: Option[ApiFlightWithSplits] = flightsSoFar.values
            .find { flightToCheck =>
              val vnMatches = flightToCheck.apiFlight.voyageNumberPadded == newManifest.VoyageNumber
              val schMatches = newManifest.millis == flightToCheck.apiFlight.Scheduled
              vnMatches && schMatches
            }

          maybeFlightForManifest match {
            case None =>
              addManifestToBuffer(newManifest)
              flightsSoFar
            case Some(flightForManifest) =>
              val flightWithManifestSplits = updateFlightWithManifest(flightForManifest, newManifest)
              flightsSoFar.updated(flightWithManifestSplits.apiFlight.uniqueId, flightWithManifestSplits)
          }
      }
    }

    def addManifestToBuffer(newManifest: VoyageManifest): Unit = {
      val existingManifests = manifestsBuffer.getOrElse(newManifest.key, Set())
      val updatedManifests = existingManifests + newManifest
      manifestsBuffer = manifestsBuffer.updated(newManifest.key, updatedManifests)
    }

    def pushStateIfReady(): Unit = {
      if (isAvailable(outArrivalsWithSplits)) {
        if (arrivalsWithSplitsDiff.nonEmpty) {
          log.info(s"Pushing ${arrivalsWithSplitsDiff.size} updated arrivals with splits")
          push(outArrivalsWithSplits, FlightsWithSplits(arrivalsWithSplitsDiff.toSeq))
          arrivalsWithSplitsDiff = Set()
        } else log.info(s"No updated arrivals with splits to push")
      } else log.info(s"outArrivalsWithSplits not available to push")
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

  def nowMillis: Option[MillisSinceEpoch] = {
    Option(SDate.now().millisSinceEpoch)
  }

  def mergeDiffSets(latestDiff: Set[ApiFlightWithSplits], existingDiff: Set[ApiFlightWithSplits]): Set[ApiFlightWithSplits] = {
    val existingDiffById = existingDiff.map(a => (a.apiFlight.uniqueId, a)).toMap
    latestDiff
      .foldLeft(existingDiffById) {
        case (soFar, updatedArrival) => soFar.updated(updatedArrival.apiFlight.uniqueId, updatedArrival)
      }
      .values.toSet
  }

  def purgeExpiredManifests(manifests: Map[Int, Set[VoyageManifest]]): Map[Int, Set[VoyageManifest]] = {
    val expired = hasExpiredForType((m: VoyageManifest) => m.scheduleArrivalDateTime.getOrElse(SDate.now()).millisSinceEpoch)
    val updated = manifests
      .mapValues(_.filterNot(expired))
      .filterNot { case (_, ms) => ms.isEmpty }

    val numPurged = manifests.size - updated.size
    if (numPurged > 0) log.info(s"Purged $numPurged expired manifests")

    updated
  }

  def purgeExpiredArrivals(arrivals: Map[Int, ApiFlightWithSplits]): Map[Int, ApiFlightWithSplits] = {
    val expired = hasExpiredForType((a: ApiFlightWithSplits) => a.apiFlight.PcpTime.getOrElse(0L))
    val updated = arrivals.filterNot { case (_, a) => expired(a) }

    val numPurged = arrivals.size - updated.size
    if (numPurged > 0) log.info(s"Purged $numPurged expired arrivals")

    updated
  }

  def hasExpiredForType[A](toMillis: A => MillisSinceEpoch): A => Boolean = Crunch.hasExpired[A](now(), expireAfterMillis, toMillis)

  def isNewerThan(thresholdMillis: MillisSinceEpoch, vm: VoyageManifest): Boolean = {
    vm.scheduleArrivalDateTime match {
      case None => false
      case Some(sch) => sch.millisSinceEpoch > thresholdMillis
    }
  }

  def twoDaysAgo: MillisSinceEpoch = SDate.now().millisSinceEpoch - (2 * oneDayMillis)
}

