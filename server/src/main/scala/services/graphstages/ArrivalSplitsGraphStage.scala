package services.graphstages

import akka.stream._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.SplitRatiosNs.SplitSources
import drt.shared._
import manifests.passengers.BestAvailableManifest
import manifests.queues.SplitsCalculator
import org.slf4j.{Logger, LoggerFactory}
import services.graphstages.Crunch.purgeExpired
import services.metrics.{Metrics, StageTimer}

import scala.collection.immutable.Map
import scala.collection.mutable

case class UpdateStats(updatesCount: Int, additionsCount: Int)


class ArrivalSplitsGraphStage(name: String = "",
                              optionalInitialFlights: Option[FlightsWithSplits],
                              splitsCalculator: SplitsCalculator,
                              expireAfterMillis: Long,
                              now: () => SDateLike,
                              useApiPaxNos: Boolean
                             )
  extends GraphStage[FanInShape3[ArrivalsDiff, List[BestAvailableManifest], List[BestAvailableManifest], FlightsWithSplits]] {

  val log: Logger = LoggerFactory.getLogger(s"$getClass-$name")

  val inArrivalsDiff: Inlet[ArrivalsDiff] = Inlet[ArrivalsDiff]("ArrivalsDiffIn.in")
  val inManifestsLive: Inlet[List[BestAvailableManifest]] = Inlet[List[BestAvailableManifest]]("ManifestsLiveIn.in")
  val inManifestsHistoric: Inlet[List[BestAvailableManifest]] = Inlet[List[BestAvailableManifest]]("ManifestsHistoricIn.in")
  val outArrivalsWithSplits: Outlet[FlightsWithSplits] = Outlet[FlightsWithSplits]("FlightsWithSplitsOut.out")

  val stageName = "arrival-splits"

  override val shape = new FanInShape3(inArrivalsDiff, inManifestsLive, inManifestsHistoric, outArrivalsWithSplits)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    val flightsByFlightId: mutable.SortedMap[ArrivalKey, ApiFlightWithSplits] = mutable.SortedMap()
    val codeShares: mutable.SortedMap[CodeShareKeyOrderedByDupes[ArrivalKey], Set[ArrivalKey]] = mutable.SortedMap()
    var arrivalsWithSplitsDiff: Map[ArrivalKey, ApiFlightWithSplits] = Map()
    var arrivalsToRemove: Set[Arrival] = Set()
    val manifestBuffer: mutable.Map[ArrivalKey, BestAvailableManifest] = mutable.Map()

    override def preStart(): Unit = {

      optionalInitialFlights match {
        case Some(FlightsWithSplits(flights, _)) =>
          log.info(s"Received initial flights. Setting ${flights.size}")
          flights.foreach(fws => flightsByFlightId += (ArrivalKey(fws.apiFlight) -> fws))
          purgeExpired(flightsByFlightId, ArrivalKey.atTime, now, expireAfterMillis.toInt)

          flightsByFlightId.foreach { case (arrivalKey, fws) =>
            val csKey = CodeShareKeyOrderedByDupes[ArrivalKey](fws.apiFlight.Scheduled, fws.apiFlight.Terminal, fws.apiFlight.Origin, Set())
            val existingEntry: Set[ArrivalKey] = codeShares.getOrElse(csKey, Set())
            val updatedArrivalKeys = existingEntry + arrivalKey
            codeShares += (csKey.copy(arrivalKeys = updatedArrivalKeys) -> updatedArrivalKeys)
          }

        case _ =>
          log.warn(s"Did not receive any flights to initialise with")
      }

      super.preStart()
    }

    setHandler(outArrivalsWithSplits, new OutHandler {
      override def onPull(): Unit = {
        val timer = StageTimer(stageName, outArrivalsWithSplits)
        log.debug(s"arrivalsWithSplitsOut onPull called")
        pushStateIfReady()
        pullAll()
        timer.stopAndReport()
      }
    })

    setHandler(inArrivalsDiff, new InHandler {
      override def onPush(): Unit = {
        val timer = StageTimer(stageName, inArrivalsDiff)
        log.debug(s"inFlights onPush called")
        val arrivalsDiff: ArrivalsDiff = grab(inArrivalsDiff)

        log.info(s"Grabbed ${arrivalsDiff.toUpdate.size} updates, ${arrivalsDiff.toRemove.size} removals")

        val flightsWithUpdates: Map[ArrivalKey, ApiFlightWithSplits] = applyUpdatesToFlights(arrivalsDiff)

        updateCodeSharesFromDiff(arrivalsDiff)
        updateFlightsFromIncoming(arrivalsDiff)

        purgeExpired(flightsByFlightId, ArrivalKey.atTime, now, expireAfterMillis.toInt)

        val uniqueFlightsWithUpdates = flightsWithUpdates.filterKeys(flightsByFlightId.contains)

        arrivalsWithSplitsDiff = mergeDiffSets(uniqueFlightsWithUpdates, arrivalsWithSplitsDiff)
        arrivalsToRemove = arrivalsToRemove ++ arrivalsDiff.toRemove
        log.info(s"${arrivalsWithSplitsDiff.size} updated arrivals waiting to push")

        pushStateIfReady()
        pullAll()
        timer.stopAndReport()
      }
    })

    def applyUpdatesToFlights(arrivalsDiff: ArrivalsDiff): Map[ArrivalKey, ApiFlightWithSplits] = {
      val flightsWithUpdates = arrivalsDiff.toUpdate.foldLeft(Map[ArrivalKey, ApiFlightWithSplits]()) {
        case (withUpdatesSoFar: Map[ArrivalKey, ApiFlightWithSplits], (_, newArrival)) =>
          val arrivalKey = ArrivalKey(newArrival)
          flightsByFlightId.get(arrivalKey) match {
            case None =>
              val splits: Set[Splits] = initialSplits(newArrival, arrivalKey)
              val newFlightWithSplits: ApiFlightWithSplits = makeFlightWithSplits(newArrival, splits)
              withUpdatesSoFar.updated(arrivalKey, newFlightWithSplits)
            case Some(existingArrival) =>
              if (!existingArrival.apiFlight.equals(newArrival))
                withUpdatesSoFar.updated(arrivalKey, makeFlightWithSplits(newArrival, existingArrival.splits))
              else withUpdatesSoFar
          }
      }
      flightsWithUpdates
    }

    def makeFlightWithSplits(flight: Arrival, splits: Set[Splits]): ApiFlightWithSplits = {
      val liveApiSplits: Option[Splits] = splits.find {
        case Splits(_, source, _, _) if source == SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages => true
        case _ => false
      }

      val flightWithAvailableApiData = liveApiSplits match {
        case Some(splits) =>
          if (useApiPaxNos)
            flight.copy(
              FeedSources = flight.FeedSources + ApiFeedSource,
              ApiPax = Option(Math.round(splits.totalExcludingTransferPax).toInt)
            )
          else
            flight.copy(FeedSources = flight.FeedSources + ApiFeedSource)
        case _ => flight
      }

      ApiFlightWithSplits(
        flightWithAvailableApiData,
        splits,
        nowMillis
      )
    }

    setHandler(inManifestsLive, InManifestsHandler(inManifestsLive))

    setHandler(inManifestsHistoric, InManifestsHandler(inManifestsHistoric))

    def InManifestsHandler(inlet: Inlet[List[BestAvailableManifest]]): InHandler =
      new InHandler() {
        override def onPush(): Unit = {
          val timer = StageTimer(stageName, inlet)

          val incoming: List[BestAvailableManifest] = grab(inlet)

          log.info(s"Grabbed ${incoming.size} BestAvailableManifests from connection")

          val flightsWithUpdates = updateFlightsWithManifests(incoming)

          arrivalsWithSplitsDiff = mergeDiffSets(flightsWithUpdates, arrivalsWithSplitsDiff)
          purgeExpired(flightsByFlightId, ArrivalKey.atTime, now, expireAfterMillis.toInt)

          pushStateIfReady()

          pullAll()
          timer.stopAndReport()
        }
      }

    def pullAll(): Unit = {
      List(inManifestsLive, inManifestsHistoric, inArrivalsDiff).foreach(in => if (!hasBeenPulled(in)) {
        log.debug(s"Pulling ${in.toString}")
        pull(in)
      })
    }

    def updateFlightsFromIncoming(arrivalsDiff: ArrivalsDiff): Unit = {
      log.debug(s"${arrivalsDiff.toUpdate.size} diff updates, ${flightsByFlightId.size} existing flights")

      flightsByFlightId --= arrivalsDiff.toRemove.map(ArrivalKey(_))

      val updateStats = arrivalsDiff.toUpdate.foldLeft(UpdateStats(0, 0)) {
        case (statsSoFar, (_, updatedFlight)) => updateWithFlight(statsSoFar, updatedFlight)
      }

      log.debug(s"${flightsByFlightId.size} flights after updates. ${updateStats.updatesCount} updates & ${updateStats.additionsCount} additions")

      val codeSharesToRemove = codeShares.foldLeft(Set[ArrivalKey]()) {
        case (removalsSoFar, (_, codeShareArrivalKeys)) =>
          val shares = codeShareArrivalKeys
            .map(arrivalKey => flightsByFlightId.get(arrivalKey))
            .collect { case Some(fws) => fws }
            .toSeq.sortBy(_.apiFlight.ActPax.getOrElse(0)).reverse
          val toRemove = shares.drop(1)
          val keysToRemove = toRemove.map(fws => ArrivalKey(fws.apiFlight))
          removalsSoFar ++ keysToRemove
      }

      flightsByFlightId --= codeSharesToRemove

      log.debug(s"${flightsByFlightId.size} flights after accounting for codeshares")
    }

    def updateWithFlight(updatedFlights: UpdateStats, updatedFlight: Arrival): UpdateStats = {
      val key = ArrivalKey(updatedFlight)
      flightsByFlightId.get(key) match {
        case None =>
          val splits: Set[Splits] = initialSplits(updatedFlight, key)
          val newFlightWithSplits: ApiFlightWithSplits = makeFlightWithSplits(updatedFlight, splits)
          flightsByFlightId += (key -> newFlightWithSplits.copy(lastUpdated = nowMillis))
          updatedFlights.copy(additionsCount = updatedFlights.additionsCount + 1)

        case Some(existingFlight) if existingFlight.apiFlight != updatedFlight =>
          flightsByFlightId += (key -> existingFlight.copy(apiFlight = updatedFlight, lastUpdated = nowMillis))
          updatedFlights.copy(updatesCount = updatedFlights.updatesCount + 1)

        case _ => updatedFlights
      }
    }

    def initialSplits(updatedFlight: Arrival, key: ArrivalKey): Set[Splits] = {
      val terminalDefault = splitsCalculator.terminalDefaultSplits(updatedFlight.Terminal)
      if (manifestBuffer.contains(key)) {
        val splits = terminalDefault + splitsFromManifest(updatedFlight, manifestBuffer(key))
        manifestBuffer -= key
        splits
      } else terminalDefault
    }

    def updateFlightsWithManifests(manifests: Seq[BestAvailableManifest]): Map[ArrivalKey, ApiFlightWithSplits] = {
      manifests.foldLeft(Map[ArrivalKey, ApiFlightWithSplits]()) {
        case (flightsWithNewSplits, newManifest) =>
          newManifest.voyageNumber match {
            case InvalidVoyageNumber(_) => flightsWithNewSplits
            case vn: VoyageNumber =>
              val key = ArrivalKey(newManifest.departurePortCode, vn, newManifest.scheduled.millisSinceEpoch)
              flightsByFlightId.get(key) match {
                case Some(flightForManifest) =>
                  val manifestSplits: Splits = splitsFromManifest(flightForManifest.apiFlight, newManifest)

                  if (isNewManifestForFlight(flightForManifest, manifestSplits)) {
                    val flightWithManifestSplits: ApiFlightWithSplits = makeFlightWithSplits(
                      flightForManifest.apiFlight,
                      updateSplitsWithNewManifest(manifestSplits, flightForManifest.splits)
                    )
                    flightsByFlightId += (key -> flightWithManifestSplits)
                    flightsWithNewSplits.updated(key, flightWithManifestSplits)
                  } else flightsWithNewSplits
                case None =>
                  manifestBuffer += (key -> newManifest)
                  flightsWithNewSplits
              }
          }

      }
    }

    def pushStateIfReady(): Unit = {
      if (isAvailable(outArrivalsWithSplits)) {
        if (arrivalsWithSplitsDiff.nonEmpty || arrivalsToRemove.nonEmpty) {
          Metrics.counter(s"$stageName.arrivals-with-splits.updates", arrivalsWithSplitsDiff.values.size)
          Metrics.counter(s"$stageName.arrivals-with-splits.removals", arrivalsToRemove.size)

          push(outArrivalsWithSplits, FlightsWithSplits(arrivalsWithSplitsDiff.values.toList, arrivalsToRemove.toList))
          arrivalsWithSplitsDiff = Map()
          arrivalsToRemove = Set()
        } else log.debug(s"No updated arrivals with splits to push")
      } else log.debug(s"outArrivalsWithSplits not available to push")
    }

    def isNewManifestForFlight(flightWithSplits: ApiFlightWithSplits, newSplits: Splits): Boolean = !flightWithSplits.splits.contains(newSplits)

    def updateCodeSharesFromDiff(arrivalsDiff: ArrivalsDiff): Unit = arrivalsDiff.toUpdate
      .foreach { case (_, arrival) =>
        val csKey = CodeShareKeyOrderedByDupes[ArrivalKey](arrival.Scheduled, arrival.Terminal, arrival.Origin, Set())
        val existingEntry: Set[ArrivalKey] = codeShares.getOrElse(csKey, Set())
        val updatedArrivalKeys = existingEntry + ArrivalKey(arrival)
        codeShares += (csKey.copy(arrivalKeys = updatedArrivalKeys) -> updatedArrivalKeys)
      }
  }

  def updateSplitsWithNewManifest(manifestSplits: Splits, existingSplits: Set[Splits]): Set[Splits] = {
    existingSplits.filterNot(_.source == manifestSplits.source) ++ Set(manifestSplits)
  }

  def splitsFromManifest(arrival: Arrival, manifest: BestAvailableManifest): Splits = {
    splitsCalculator.bestSplitsForArrival(manifest.copy(carrierCode = arrival.CarrierCode), arrival)
  }

  def nowMillis: Option[MillisSinceEpoch] = {
    Option(now().millisSinceEpoch)
  }

  def mergeDiffSets(latestDiff: Map[ArrivalKey, ApiFlightWithSplits],
                    existingDiff: Map[ArrivalKey, ApiFlightWithSplits]
                   ): Map[ArrivalKey, ApiFlightWithSplits] = latestDiff
    .foldLeft(existingDiff) {
      case (diffSoFar, (newArrivalKey, newFws)) => diffSoFar.updated(newArrivalKey, newFws)
    }
}

