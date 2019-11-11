package services.graphstages

import akka.stream._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.FlightsWithSplits
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
                              portCode: String,
                              optionalInitialFlights: Option[FlightsWithSplits],
                              splitsCalculator: SplitsCalculator,
                              groupFlightsByCodeShares: Seq[ApiFlightWithSplits] => Seq[(ApiFlightWithSplits, Set[Arrival])],
                              expireAfterMillis: Long,
                              now: () => SDateLike)
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
        val arrivalsDiff = grab(inArrivalsDiff)

        log.info(s"Grabbed ${arrivalsDiff.toUpdate.size} updates, ${arrivalsDiff.toRemove.size} removals")

        val flightsWithUpdates = applyUpdatesToFlights(arrivalsDiff)

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
        case (withUpdatesSoFar, (_, newArrival)) =>
          val arrivalKey = ArrivalKey(newArrival)
          flightsByFlightId.get(arrivalKey) match {
            case None =>
              val splits: Set[Splits] = initialSplits(newArrival, arrivalKey)
              val newFlightWithSplits: ApiFlightWithSplits = ApiFlightWithSplits(newArrival, splits, nowMillis)
              withUpdatesSoFar.updated(arrivalKey, newFlightWithSplits)
            case Some(existingArrival) =>
              if (!existingArrival.apiFlight.equals(newArrival))
                withUpdatesSoFar.updated(arrivalKey, existingArrival.copy(apiFlight = newArrival, lastUpdated = nowMillis))
              else withUpdatesSoFar
          }
      }
      flightsWithUpdates
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
          val newFlightWithSplits: ApiFlightWithSplits = ApiFlightWithSplits(updatedFlight, splits, nowMillis)
          flightsByFlightId += (key -> newFlightWithSplits.copy(lastUpdated = nowMillis))
          updatedFlights.copy(additionsCount = updatedFlights.additionsCount + 1)

        case Some(existingFlight) if existingFlight.apiFlight != updatedFlight =>
          flightsByFlightId += (key -> existingFlight.copy(apiFlight = updatedFlight, lastUpdated = nowMillis))
          updatedFlights.copy(updatesCount = updatedFlights.updatesCount + 1)

        case _ => updatedFlights
      }
    }

    def initialSplits(updatedFlight: Arrival, key: ArrivalKey): Set[Splits] =
      if (manifestBuffer.contains(key)) {
        val splits = splitsCalculator.portDefaultSplits + splitsFromManifest(updatedFlight, manifestBuffer(key))
        manifestBuffer -= key
        splits
      }
      else splitsCalculator.portDefaultSplits

    def updateFlightsWithManifests(manifests: Seq[BestAvailableManifest]): Map[ArrivalKey, ApiFlightWithSplits] = {
      manifests.foldLeft(Map[ArrivalKey, ApiFlightWithSplits]()) {
        case (flightsWithNewSplits, newManifest) =>
          val key = ArrivalKey(newManifest.departurePortCode, newManifest.voyageNumber, newManifest.scheduled.millisSinceEpoch)
          flightsByFlightId.get(key) match {
            case Some(flightForManifest) =>
              val manifestSplits: Splits = splitsFromManifest(flightForManifest.apiFlight, newManifest)

              if (isNewManifestForFlight(flightForManifest, manifestSplits)) {
                val flightWithManifestSplits = updateFlightWithSplits(flightForManifest, manifestSplits)
                flightsByFlightId += (key -> flightWithManifestSplits)
                flightsWithNewSplits.updated(key, flightWithManifestSplits)
              } else flightsWithNewSplits
            case None =>
              manifestBuffer += (key -> newManifest)
              flightsWithNewSplits
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

    def updateFlightWithSplits(flightWithSplits: ApiFlightWithSplits,
                               newSplits: Splits): ApiFlightWithSplits = {
      val apiFlight = flightWithSplits.apiFlight
      flightWithSplits.copy(
        apiFlight = apiFlight.copy(FeedSources = apiFlight.FeedSources + ApiFeedSource),
        splits = flightWithSplits.splits.filterNot(_.source == newSplits.source) ++ Set(newSplits)
      )
    }

    def updateCodeSharesFromDiff(arrivalsDiff: ArrivalsDiff): Unit = arrivalsDiff.toUpdate
      .foreach { case (_, arrival) =>
        val csKey = CodeShareKeyOrderedByDupes[ArrivalKey](arrival.Scheduled, arrival.Terminal, arrival.Origin, Set())
        val existingEntry: Set[ArrivalKey] = codeShares.getOrElse(csKey, Set())
        val updatedArrivalKeys = existingEntry + ArrivalKey(arrival)
        codeShares += (csKey.copy(arrivalKeys = updatedArrivalKeys) -> updatedArrivalKeys)
      }
  }

  def splitsFromManifest(arrival: Arrival, manifest: BestAvailableManifest): Splits = {
    splitsCalculator.bestSplitsForArrival(manifest.copy(carrierCode = arrival.carrierCode), arrival)
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

