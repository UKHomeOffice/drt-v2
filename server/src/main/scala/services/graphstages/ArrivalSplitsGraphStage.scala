package services.graphstages

import akka.stream._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared._
import manifests.passengers.BestAvailableManifest
import manifests.queues.SplitsCalculator
import org.slf4j.{Logger, LoggerFactory}
import server.feeds._
import services._

import scala.collection.immutable.Map
import scala.language.postfixOps

case class UpdatedFlights(flights: Map[ArrivalKey, ApiFlightWithSplits], updatesCount: Int, additionsCount: Int)


class ArrivalSplitsGraphStage(name: String = "",
                              portCode: String,
                              optionalInitialFlights: Option[FlightsWithSplits],
                              splitsCalculator: SplitsCalculator, //keep this for now, we'll need to move this into it's own graph stage later..
                              groupFlightsByCodeShares: Seq[ApiFlightWithSplits] => Seq[(ApiFlightWithSplits, Set[Arrival])],
                              expireAfterMillis: Long,
                              now: () => SDateLike,
                              maxDaysToCrunch: Int)
  extends GraphStage[FanInShape2[ArrivalsDiff, ManifestsFeedResponse, FlightsWithSplits]] {

  val log: Logger = LoggerFactory.getLogger(s"$getClass-$name")

  val inArrivalsDiff: Inlet[ArrivalsDiff] = Inlet[ArrivalsDiff]("ArrivalsDiffIn.in")
  val inManifests: Inlet[ManifestsFeedResponse] = Inlet[ManifestsFeedResponse]("SplitsIn.in")
  val outArrivalsWithSplits: Outlet[FlightsWithSplits] = Outlet[FlightsWithSplits]("ArrivalsWithSplitsOut.out")

  override val shape = new FanInShape2(inArrivalsDiff, inManifests, outArrivalsWithSplits)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    var flightsByFlightId: Map[ArrivalKey, ApiFlightWithSplits] = Map()
    var arrivalsWithSplitsDiff: Set[ApiFlightWithSplits] = Set()
    var arrivalsToRemove: Set[Int] = Set()


    override def preStart(): Unit = {

      optionalInitialFlights match {
        case Some(FlightsWithSplits(flights, _)) =>
          log.info(s"Received initial flights. Setting ${flights.size}")
          flightsByFlightId = purgeExpiredArrivals(
            flights.map(fws => (ArrivalKey(fws.apiFlight), fws)).toMap
          )
          flights
            .foldLeft(Map[(String, String, Long), Int]()) {
              case (soFar, fws) =>
                val id = (fws.apiFlight.Origin, fws.apiFlight.voyageNumberPadded, fws.apiFlight.Scheduled)
                soFar.get(id) match {
                  case Some(count) => soFar.updated(id, count + 1)
                  case None => soFar.updated(id, 1)
                }
            }
            .foreach {
              case (id, count) => if (count > 1) log.info(s"***** id $id has $count matches")
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

        val updatedFlights = purgeExpiredArrivals(updateFlightsFromIncoming(arrivalsDiff, flightsByFlightId))
        val latestDiff = updatedFlights.values.toSet -- flightsByFlightId.values.toSet
        arrivalsWithSplitsDiff = mergeDiffSets(latestDiff, arrivalsWithSplitsDiff)
        arrivalsToRemove = arrivalsToRemove ++ arrivalsDiff.toRemove
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
        log.info(s"inSplits onPush called")

        val incoming: ManifestsFeedResponse = grab(inManifests) match {
          case ManifestsFeedSuccess(DqManifests(_, manifests), createdAt) =>
            BestManifestsFeedSuccess(manifests.toSeq.map(vm => BestAvailableManifest(vm, portCode)), createdAt)
          case ManifestsFeedFailure(msg, createdAt) => BestManifestsFeedFailure(msg, createdAt)
          case bms: BestManifestsFeedSuccess => bms
          case bmf: BestManifestsFeedFailure => bmf
        }

        incoming match {
          case BestManifestsFeedSuccess(bestAvailableManifests, connectedAt) =>
            log.info(s"Grabbed ${bestAvailableManifests.size} BestAvailableManifests from connection at ${connectedAt.toISOString()}")

            val updatedFlights = purgeExpiredArrivals(updateFlightsWithManifests(bestAvailableManifests, flightsByFlightId))
            log.info(s"We now have ${updatedFlights.size}")

            val latestDiff = updatedFlights.values.toSet -- flightsByFlightId.values.toSet
            arrivalsWithSplitsDiff = mergeDiffSets(latestDiff, arrivalsWithSplitsDiff)
            flightsByFlightId = updatedFlights
            log.info(s"Done diff")

            pushStateIfReady()

          case BestManifestsFeedFailure(message, failedAt) =>
            log.info(s"$inManifests failed at ${failedAt.toISOString()}: $message")

          case unexpected => log.error(s"Unexpected feed response: ${unexpected.getClass}")
        }
        pullAll()
        log.info(s"inManifests Took ${SDate.now().millisSinceEpoch - start.millisSinceEpoch}ms")
      }
    })

    def pullAll(): Unit = {
      List(inManifests, inArrivalsDiff).foreach(in => if (!hasBeenPulled(in)) {
        log.info(s"Pulling ${in.toString}")
        pull(in)
      })
    }

    def updateFlightsFromIncoming(arrivalsDiff: ArrivalsDiff,
                                  existingFlightsById: Map[ArrivalKey, ApiFlightWithSplits]): Map[ArrivalKey, ApiFlightWithSplits] = {
      log.info(s"${arrivalsDiff.toUpdate.size} diff updates, ${existingFlightsById.size} existing flights")
      val afterRemovals = existingFlightsById.filterNot {
        case (_, ApiFlightWithSplits(arrival, _, _)) => arrivalsDiff.toRemove.contains(arrival.uniqueId)
      }

      val lastMilliToCrunch = Crunch.getLocalLastMidnight(now().addDays(maxDaysToCrunch)).millisSinceEpoch

      val updatedFlights = arrivalsDiff.toUpdate.foldLeft(UpdatedFlights(afterRemovals, 0, 0)) {
        case (updatesSoFar, updatedFlight) =>
          if (updatedFlight.PcpTime.getOrElse(0L) <= lastMilliToCrunch) updateWithFlight(updatesSoFar, updatedFlight)
          else updatesSoFar
      }

      log.info(s"${updatedFlights.flights.size} flights after updates. ${updatedFlights.updatesCount} updates & ${updatedFlights.additionsCount} additions")

      val minusExpired = purgeExpiredArrivals(updatedFlights.flights)

      val uniqueFlights = groupFlightsByCodeShares(minusExpired.values.toSeq)
        .map { case (fws, _) => (ArrivalKey(fws.apiFlight), fws) }
        .toMap

      log.info(s"${uniqueFlights.size} flights after removing expired and accounting for codeshares")

      uniqueFlights
    }

    def updateWithFlight(updatedFlights: UpdatedFlights, updatedFlight: Arrival): UpdatedFlights = {
      val key = ArrivalKey(updatedFlight)
      updatedFlights.flights.get(key) match {
        case None =>
          val newFlightSplits: ApiFlightWithSplits = ApiFlightWithSplits(updatedFlight, splitsCalculator.portDefaultSplits, nowMillis)
          val withNewFlight = updatedFlights.flights.updated(key, newFlightSplits.copy(lastUpdated = nowMillis))
          updatedFlights.copy(flights = withNewFlight, additionsCount = updatedFlights.additionsCount + 1)

        case Some(existingFlight) if existingFlight.apiFlight != updatedFlight =>
          val withUpdatedFlight = updatedFlights.flights.updated(key, existingFlight.copy(apiFlight = updatedFlight, lastUpdated = nowMillis))
          updatedFlights.copy(flights = withUpdatedFlight, updatesCount = updatedFlights.updatesCount + 1)

        case _ => updatedFlights
      }
    }

    def updateFlightsWithManifests(manifests: Seq[BestAvailableManifest],
                                   flightsById: Map[ArrivalKey, ApiFlightWithSplits]): Map[ArrivalKey, ApiFlightWithSplits] = {
      manifests.foldLeft[Map[ArrivalKey, ApiFlightWithSplits]](flightsByFlightId) {
        case (flightsSoFar, newManifest) =>
          val key = ArrivalKey(newManifest.departurePortCode, newManifest.voyageNumber, newManifest.scheduled.millisSinceEpoch)
          //          val maybeFlightForManifest_: Option[ApiFlightWithSplits] = flightsSoFar.values
//            .find { flightToCheck =>
//              val vnMatches = flightToCheck.apiFlight.voyageNumberPadded == newManifest.voyageNumber
//              val schMatches = newManifest.scheduled.millisSinceEpoch == flightToCheck.apiFlight.Scheduled
//              vnMatches && schMatches
//            }

          flightsSoFar.get(key) match {
            case Some(flightForManifest) =>
              val flightWithManifestSplits = updateFlightWithManifest(flightForManifest, newManifest)
              flightsSoFar.updated(ArrivalKey(flightWithManifestSplits.apiFlight), flightWithManifestSplits)
            case None =>
              log.debug(s"Got a manifest with no flight")
              flightsSoFar
          }
      }
    }

    def pushStateIfReady(): Unit = {
      if (isAvailable(outArrivalsWithSplits)) {
        if (arrivalsWithSplitsDiff.nonEmpty || arrivalsToRemove.nonEmpty) {
          log.info(s"Pushing ${arrivalsWithSplitsDiff.size} updated arrivals with splits and ${arrivalsToRemove.size} removals")
          push(outArrivalsWithSplits, FlightsWithSplits(arrivalsWithSplitsDiff.toSeq, arrivalsToRemove))
          arrivalsWithSplitsDiff = Set()
          arrivalsToRemove = Set()
        } else log.info(s"No updated arrivals with splits to push")
      } else log.info(s"outArrivalsWithSplits not available to push")
    }

    def updateFlightWithManifest(flightWithSplits: ApiFlightWithSplits,
                                 manifest: BestAvailableManifest): ApiFlightWithSplits = {
      val splitsFromManifest: Splits = splitsCalculator.bestSplitsForArrival(manifest, flightWithSplits.apiFlight)

      val apiFlight = flightWithSplits.apiFlight
      flightWithSplits.copy(
        apiFlight = apiFlight.copy(FeedSources = apiFlight.FeedSources + ApiFeedSource),
        splits = flightWithSplits.splits.filterNot(_.source == splitsFromManifest.source) ++ Set(splitsFromManifest)
      )
    }
  }

  def nowMillis: Option[MillisSinceEpoch] = {
    Option(now().millisSinceEpoch)
  }

  def mergeDiffSets(latestDiff: Set[ApiFlightWithSplits],
                    existingDiff: Set[ApiFlightWithSplits]): Set[ApiFlightWithSplits] = {
    val existingDiffById = existingDiff.map(a => (a.apiFlight.uniqueId, a)).toMap
    latestDiff
      .foldLeft(existingDiffById) {
        case (soFar, updatedArrival) => soFar.updated(updatedArrival.apiFlight.uniqueId, updatedArrival)
      }
      .values.toSet
  }

  def purgeExpiredArrivals(arrivals: Map[ArrivalKey, ApiFlightWithSplits]): Map[ArrivalKey, ApiFlightWithSplits] = {
    val expired = hasExpiredForType((a: ApiFlightWithSplits) => a.apiFlight.PcpTime.getOrElse(0L))
    val updated = arrivals.filterNot { case (_, a) => expired(a) }

    val numPurged = arrivals.size - updated.size
    if (numPurged > 0) log.info(s"Purged $numPurged expired arrivals")

    updated
  }

  def hasExpiredForType[A](toMillis: A => MillisSinceEpoch): A => Boolean = Crunch.hasExpired[A](now(), expireAfterMillis, toMillis)

  def twoDaysAgo: MillisSinceEpoch = now().millisSinceEpoch - (2 * Crunch.oneDayMillis)
}

