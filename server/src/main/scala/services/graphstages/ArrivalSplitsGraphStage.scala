package services.graphstages

import akka.stream._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import drt.server.feeds._
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared._
import manifests.passengers.BestAvailableManifest
import manifests.queues.SplitsCalculator
import org.slf4j.{Logger, LoggerFactory}
import services._
import services.graphstages.Crunch.purgeExpired

import scala.collection.immutable.{Map, SortedMap}
import scala.language.postfixOps

case class UpdatedFlights(flights: SortedMap[ArrivalKey, ApiFlightWithSplits], updatesCount: Int, additionsCount: Int)


class ArrivalSplitsGraphStage(name: String = "",
                              portCode: String,
                              optionalInitialFlights: Option[FlightsWithSplits],
                              splitsCalculator: SplitsCalculator, //keep this for now, we'll need to move this into it's own graph stage later..
                              groupFlightsByCodeShares: Seq[ApiFlightWithSplits] => Seq[(ApiFlightWithSplits, Set[Arrival])],
                              expireAfterMillis: Long,
                              now: () => SDateLike,
                              maxDaysToCrunch: Int)
  extends GraphStage[FanInShape3[ArrivalsDiff, ManifestsFeedResponse, ManifestsFeedResponse, FlightsWithSplits]] {

  val log: Logger = LoggerFactory.getLogger(s"$getClass-$name")

  val inArrivalsDiff: Inlet[ArrivalsDiff] = Inlet[ArrivalsDiff]("ArrivalsDiffIn.in")
  val inManifestsLive: Inlet[ManifestsFeedResponse] = Inlet[ManifestsFeedResponse]("ManifestsLiveIn.in")
  val inManifestsHistoric: Inlet[ManifestsFeedResponse] = Inlet[ManifestsFeedResponse]("ManifestsHistoricIn.in")
  val outArrivalsWithSplits: Outlet[FlightsWithSplits] = Outlet[FlightsWithSplits]("ArrivalsWithSplitsOut.out")

  override val shape = new FanInShape3(inArrivalsDiff, inManifestsLive, inManifestsHistoric, outArrivalsWithSplits)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    var flightsByArrivalKey: SortedMap[ArrivalKey, ApiFlightWithSplits] = SortedMap()
    var codeShares: SortedMap[CodeShareKey, Set[ArrivalKey]] = SortedMap()
    var arrivalsWithSplitsDiff: Map[ArrivalKey, ApiFlightWithSplits] = Map()
    var arrivalsToRemove: Set[Arrival] = Set()
    var manifestBuffer: Map[ArrivalKey, BestAvailableManifest] = Map()

    override def preStart(): Unit = {

      optionalInitialFlights match {
        case Some(FlightsWithSplits(initialFlights, _)) =>
          log.info(s"Received initial flights. Setting ${initialFlights.size}")
          val sortedFlightsByKey = SortedMap[ArrivalKey, ApiFlightWithSplits]() ++ initialFlights.map(fws => (ArrivalKey(fws.apiFlight), fws))
          flightsByArrivalKey = purgeExpired(sortedFlightsByKey, now, expireAfterMillis.toInt)
          codeShares = sortedFlightsByKey
            .foldLeft(SortedMap[CodeShareKey, Set[ArrivalKey]]()) { case (codeShareUpdatesSoFar, (arrivalKey, fws)) =>
              val csKey = CodeShareKey(fws.apiFlight.Scheduled, fws.apiFlight.Terminal, fws.apiFlight.Origin, Set())
              val existingEntry: Set[ArrivalKey] = codeShares.getOrElse(csKey, codeShareUpdatesSoFar.getOrElse(csKey, Set()))
              val updatedArrivalKeys = existingEntry + arrivalKey
              codeShareUpdatesSoFar.updated(csKey.copy(arrivalKeys = updatedArrivalKeys), updatedArrivalKeys)
            }
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

        val flightsWithUpdates = applyUpdatesToFlights(arrivalsDiff)
        val codeShareUpdates: SortedMap[CodeShareKey, Set[ArrivalKey]] = codeSharesUpdatesFromDiff(arrivalsDiff)
        codeShares = codeShares ++ codeShareUpdates
        val mergedFlights = updateFlightsFromIncoming(arrivalsDiff, flightsByArrivalKey)
        val mergedMinusExpired = purgeExpired(mergedFlights, now, expireAfterMillis.toInt)

        val uniqueFlightsWithUpdates = flightsWithUpdates.filterKeys(mergedMinusExpired.contains)

        arrivalsWithSplitsDiff = mergeDiffSets(uniqueFlightsWithUpdates, arrivalsWithSplitsDiff)
        arrivalsToRemove = arrivalsToRemove ++ arrivalsDiff.toRemove
        log.info(s"${arrivalsWithSplitsDiff.size} updated arrivals waiting to push")
        flightsByArrivalKey = mergedMinusExpired

        pushStateIfReady()
        pullAll()
        log.info(s"inArrivalsDiff Took ${SDate.now().millisSinceEpoch - start.millisSinceEpoch}ms")
      }
    })

    def applyUpdatesToFlights(arrivalsDiff: ArrivalsDiff): Map[ArrivalKey, ApiFlightWithSplits] = {
      val flightsWithUpdates = arrivalsDiff.toUpdate.foldLeft(List[(ArrivalKey, ApiFlightWithSplits)]()) {
        case (withUpdatesSoFar, (key, newArrival)) => flightsByArrivalKey.get(key) match {
          case None =>
            val splits: Set[Splits] = initialSplits(newArrival, key)
            val newFlightWithSplits: ApiFlightWithSplits = ApiFlightWithSplits(newArrival, splits, nowMillis)
            (key, newFlightWithSplits) :: withUpdatesSoFar
          case Some(existingArrival) =>
            if (!existingArrival.apiFlight.equals(newArrival))
              (key, existingArrival.copy(apiFlight = newArrival, lastUpdated = nowMillis)) :: withUpdatesSoFar
            else withUpdatesSoFar
        }
      }
      flightsWithUpdates.toMap
    }

    setHandler(inManifestsLive, InManifestsHandler(inManifestsLive))

    setHandler(inManifestsHistoric, InManifestsHandler(inManifestsHistoric))

    def InManifestsHandler(inlet: Inlet[ManifestsFeedResponse]): InHandler =
      new InHandler() {
        override def onPush(): Unit = {
          val start = SDate.now()
          log.info(s"inSplits onPush called")

          val incoming: ManifestsFeedResponse = grab(inlet) match {
            case ManifestsFeedSuccess(DqManifests(_, manifests), createdAt) => BestManifestsFeedSuccess(manifests.toSeq.map(vm => BestAvailableManifest(vm)), createdAt)
            case other => other
          }

          incoming match {
            case BestManifestsFeedSuccess(bestAvailableManifests, connectedAt) =>
              log.info(s"Grabbed ${bestAvailableManifests.size} BestAvailableManifests from connection at ${connectedAt.toISOString()}")

              val (mergedFlights, flightsWithUpdates) = updateFlightsWithManifests(bestAvailableManifests)
              log.info(s"We now have ${mergedFlights.size} flights")

              arrivalsWithSplitsDiff = mergeDiffSets(flightsWithUpdates, arrivalsWithSplitsDiff)
              flightsByArrivalKey = purgeExpired(mergedFlights, now, expireAfterMillis.toInt)
              log.info(s"Done diff")

              pushStateIfReady()

            case unexpected => log.error(s"Unexpected feed response: ${unexpected.getClass}")
          }
          pullAll()
          log.info(s"inManifests Took ${SDate.now().millisSinceEpoch - start.millisSinceEpoch}ms")
        }
      }

    def pullAll(): Unit = {
      List(inManifestsLive, inManifestsHistoric, inArrivalsDiff).foreach(in => if (!hasBeenPulled(in)) {
        log.info(s"Pulling ${in.toString}")
        pull(in)
      })
    }

    def updateFlightsFromIncoming(arrivalsDiff: ArrivalsDiff,
                                  existingFlightsById: SortedMap[ArrivalKey, ApiFlightWithSplits]): SortedMap[ArrivalKey, ApiFlightWithSplits] = {
      log.info(s"${arrivalsDiff.toUpdate.size} diff updates, ${existingFlightsById.size} existing flights")

      val afterRemovals = existingFlightsById -- arrivalsDiff.toRemove.map(ArrivalKey(_))

      val updatedFlights = arrivalsDiff.toUpdate.foldLeft(UpdatedFlights(afterRemovals, 0, 0)) {
        case (updatesSoFar, (_, updatedFlight)) => updateWithFlight(updatesSoFar, updatedFlight)
      }

      log.info(s"${updatedFlights.flights.size} flights after updates. ${updatedFlights.updatesCount} updates & ${updatedFlights.additionsCount} additions")

      val codeSharesToRemove = codeShares.foldLeft(Set[ArrivalKey]()) {
        case (removalsSoFar, (_, codeShareArrivalKeys)) =>
          val shares = codeShareArrivalKeys
            .map(arrivalKey => updatedFlights.flights.get(arrivalKey))
            .collect { case Some(fws) => fws}
            .toSeq.sortBy(_.apiFlight.ActPax.getOrElse(0)).reverse
          val toRemove = shares.drop(1)
          val keysToRemove = toRemove.map(fws => ArrivalKey(fws.apiFlight))
          removalsSoFar ++ keysToRemove
      }

      val uniqueFlights = updatedFlights.flights -- codeSharesToRemove

      log.info(s"${uniqueFlights.size} flights after accounting for codeshares")

      uniqueFlights
    }

    def updateWithFlight(updatedFlights: UpdatedFlights, updatedFlight: Arrival): UpdatedFlights = {
      val key = ArrivalKey(updatedFlight)
      updatedFlights.flights.get(key) match {
        case None =>
          val splits: Set[Splits] = initialSplits(updatedFlight, key)
          val newFlightWithSplits: ApiFlightWithSplits = ApiFlightWithSplits(updatedFlight, splits, nowMillis)
          val withNewFlight = updatedFlights.flights.updated(key, newFlightWithSplits.copy(lastUpdated = nowMillis))
          updatedFlights.copy(flights = withNewFlight, additionsCount = updatedFlights.additionsCount + 1)

        case Some(existingFlight) if existingFlight.apiFlight != updatedFlight =>
          val withUpdatedFlight = updatedFlights.flights.updated(key, existingFlight.copy(apiFlight = updatedFlight, lastUpdated = nowMillis))
          updatedFlights.copy(flights = withUpdatedFlight, updatesCount = updatedFlights.updatesCount + 1)

        case _ => updatedFlights
      }
    }

    def initialSplits(updatedFlight: Arrival, key: ArrivalKey): Set[Splits] =
      if (manifestBuffer.contains(key)) {
        val splits = splitsCalculator.portDefaultSplits + splitsFromManifest(updatedFlight, manifestBuffer(key))
        manifestBuffer = manifestBuffer - key
        splits
      }
      else splitsCalculator.portDefaultSplits

    def updateFlightsWithManifests(manifests: Seq[BestAvailableManifest]): (SortedMap[ArrivalKey, ApiFlightWithSplits], Map[ArrivalKey, ApiFlightWithSplits]) = {
      val updatedFlights = manifests.foldLeft(List[(ArrivalKey, ApiFlightWithSplits)]()) {
        case (flightsWithNewSplits, newManifest) =>
          val key = ArrivalKey(newManifest.departurePortCode, newManifest.voyageNumber, newManifest.scheduled.millisSinceEpoch)
          flightsByArrivalKey.get(key) match {
            case Some(flightForManifest) =>
              val manifestSplits: Splits = splitsFromManifest(flightForManifest.apiFlight, newManifest)

              if (isNewManifestForFlight(flightForManifest, manifestSplits)) {
                val flightWithManifestSplits = updateFlightWithSplits(flightForManifest, manifestSplits)
                (key, flightWithManifestSplits)::flightsWithNewSplits
              } else flightsWithNewSplits
            case None =>
              manifestBuffer = manifestBuffer.updated(key, newManifest)
              flightsWithNewSplits
          }
      }
      (flightsByArrivalKey ++ updatedFlights, updatedFlights.toMap)
    }

    def pushStateIfReady(): Unit = {
      if (isAvailable(outArrivalsWithSplits)) {
        if (arrivalsWithSplitsDiff.nonEmpty || arrivalsToRemove.nonEmpty) {
          log.info(s"Pushing ${arrivalsWithSplitsDiff.size} updated arrivals with splits and ${arrivalsToRemove.size} removals")
          push(outArrivalsWithSplits, FlightsWithSplits(arrivalsWithSplitsDiff.values.toSeq, arrivalsToRemove.toSeq))
          arrivalsWithSplitsDiff = Map()
          arrivalsToRemove = Set()
        } else log.info(s"No updated arrivals with splits to push")
      } else log.info(s"outArrivalsWithSplits not available to push")
    }

    def isNewManifestForFlight(flightWithSplits: ApiFlightWithSplits, newSplits: Splits): Boolean = !flightWithSplits.splits.contains(newSplits)

    def updateFlightWithSplits(flightWithSplits: ApiFlightWithSplits,
                               newSplits: Splits): ApiFlightWithSplits = {
      val apiFlight = flightWithSplits.apiFlight
      flightWithSplits.copy(
        apiFlight = apiFlight.copy(FeedSources = apiFlight.FeedSources + ApiFeedSource),
        splits = flightWithSplits.splits.filterNot(_.source == newSplits.source) ++ Set(newSplits)
      )
    }

    def codeSharesUpdatesFromDiff(arrivalsDiff: ArrivalsDiff): SortedMap[CodeShareKey, Set[ArrivalKey]] = arrivalsDiff.toUpdate
      .foldLeft(SortedMap[CodeShareKey, Set[ArrivalKey]]()) { case (codeShareUpdatesSoFar, (arrivalKey, arrival)) =>
        val csKey = CodeShareKey(arrival.Scheduled, arrival.Terminal, arrival.Origin, Set())
        val existingEntry: Set[ArrivalKey] = codeShares.getOrElse(csKey, codeShareUpdatesSoFar.getOrElse(csKey, Set()))
        val updatedArrivalKeys = existingEntry + arrivalKey
        codeShareUpdatesSoFar.updated(csKey.copy(arrivalKeys = updatedArrivalKeys), updatedArrivalKeys)
      }
  }

  def splitsFromManifest(arrival: Arrival, manifest: BestAvailableManifest): Splits = {
    splitsCalculator.bestSplitsForArrival(manifest.copy(carrierCode = arrival.carrierCode), arrival)
  }

  def nowMillis: Option[MillisSinceEpoch] = Option(now().millisSinceEpoch)

  def mergeDiffSets(latestDiff: Map[ArrivalKey, ApiFlightWithSplits],
                    existingDiff: Map[ArrivalKey, ApiFlightWithSplits]
                   ): Map[ArrivalKey, ApiFlightWithSplits] =
    existingDiff ++ latestDiff

}

