package services.graphstages

import akka.stream._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import drt.shared.Terminals.{InvalidTerminal, Terminal}
import drt.shared._
import drt.shared.api.Arrival
import org.slf4j.{Logger, LoggerFactory}
import services.SDate
import services.arrivals.{ArrivalDataSanitiser, ArrivalsAdjustmentsLike, LiveArrivalsUtil}
import services.graphstages.ApproximateScheduleMatch.{mergeApproxIfFoundElseNone, mergeApproxIfFoundElseOriginal}
import services.metrics.{Metrics, StageTimer}

import scala.collection.immutable.SortedMap
import scala.language.postfixOps

sealed trait ArrivalsSourceType

case object LiveArrivals extends ArrivalsSourceType

case object LiveBaseArrivals extends ArrivalsSourceType

case object ForecastArrivals extends ArrivalsSourceType

case object BaseArrivals extends ArrivalsSourceType

class ArrivalsGraphStage(name: String = "",
                         initialForecastBaseArrivals: SortedMap[UniqueArrivalWithOrigin, Arrival],
                         initialForecastArrivals: SortedMap[UniqueArrivalWithOrigin, Arrival],
                         initialLiveBaseArrivals: SortedMap[UniqueArrivalWithOrigin, Arrival],
                         initialLiveArrivals: SortedMap[UniqueArrivalWithOrigin, Arrival],
                         initialMergedArrivals: SortedMap[UniqueArrivalWithOrigin, Arrival],
                         pcpArrivalTime: Arrival => MilliDate,
                         validPortTerminals: Set[Terminal],
                         arrivalDataSanitiser: ArrivalDataSanitiser,
                         arrivalsAdjustments: ArrivalsAdjustmentsLike,
                         expireAfterMillis: Int,
                         now: () => SDateLike)
  extends GraphStage[FanInShape4[List[Arrival], List[Arrival], List[Arrival], List[Arrival], ArrivalsDiff]] {

  val inForecastBaseArrivals: Inlet[List[Arrival]] = Inlet[List[Arrival]]("FlightsForecastBase.in")
  val inForecastArrivals: Inlet[List[Arrival]] = Inlet[List[Arrival]]("FlightsForecast.in")
  val inLiveBaseArrivals: Inlet[List[Arrival]] = Inlet[List[Arrival]]("FlightsLiveBase.in")
  val inLiveArrivals: Inlet[List[Arrival]] = Inlet[List[Arrival]]("FlightsLive.in")
  val outArrivalsDiff: Outlet[ArrivalsDiff] = Outlet[ArrivalsDiff]("ArrivalsDiff.out")
  override val shape = new FanInShape4(inForecastBaseArrivals, inForecastArrivals, inLiveBaseArrivals, inLiveArrivals, outArrivalsDiff)
  val stageName = "arrivals"

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    var aclArrivals: SortedMap[UniqueArrivalWithOrigin, Arrival] = SortedMap()
    var forecastArrivals: SortedMap[UniqueArrivalWithOrigin, Arrival] = SortedMap()
    var ciriumArrivals: SortedMap[UniqueArrivalWithOrigin, Arrival] = SortedMap()
    var liveArrivals: SortedMap[UniqueArrivalWithOrigin, Arrival] = SortedMap()
    var merged: SortedMap[UniqueArrivalWithOrigin, Arrival] = SortedMap()
    var toPush: Option[ArrivalsDiff] = None

    val log: Logger = LoggerFactory.getLogger(s"$getClass-$name")

    def arrivalsForSource(source: ArrivalsSourceType): SortedMap[UniqueArrivalWithOrigin, Arrival] = source match {
      case BaseArrivals => aclArrivals
      case ForecastArrivals => forecastArrivals
      case LiveArrivals => liveArrivals
      case LiveBaseArrivals => ciriumArrivals
      case _ => SortedMap[UniqueArrivalWithOrigin, Arrival]()
    }

    def arrivalSources(sources: List[ArrivalsSourceType]): List[(ArrivalsSourceType, SortedMap[UniqueArrivalWithOrigin, Arrival])] =
      sources.map(s => (s, arrivalsForSource(s)))

    override def preStart(): Unit = {
      log.info(s"Received ${initialForecastBaseArrivals.size} initial base arrivals")
      aclArrivals ++= relevantFlights(SortedMap[UniqueArrivalWithOrigin, Arrival]() ++ initialForecastBaseArrivals)
      log.info(s"Received ${initialForecastArrivals.size} initial forecast arrivals")
      forecastArrivals = prepInitialArrivals(initialForecastArrivals)

      log.info(s"Received ${initialLiveBaseArrivals.size} initial live base arrivals")
      ciriumArrivals = prepInitialArrivals(initialLiveBaseArrivals)
      log.info(s"Received ${initialLiveArrivals.size} initial live arrivals")
      liveArrivals = prepInitialArrivals(initialLiveArrivals)

      merged = initialMergedArrivals
      super.preStart()
    }

    def prepInitialArrivals(initialArrivals: SortedMap[UniqueArrivalWithOrigin, Arrival]): SortedMap[UniqueArrivalWithOrigin, Arrival] = {
      Crunch.purgeExpired(relevantFlights(SortedMap[UniqueArrivalWithOrigin, Arrival]() ++ initialArrivals), UniqueArrivalWithOrigin.atTime, now, expireAfterMillis.toInt)
    }

    setHandler(inForecastBaseArrivals, new InHandler {
      override def onPush(): Unit = onPushArrivals(inForecastBaseArrivals, BaseArrivals)
    })

    setHandler(inForecastArrivals, new InHandler {
      override def onPush(): Unit = onPushArrivals(inForecastArrivals, ForecastArrivals)
    })

    setHandler(inLiveBaseArrivals, new InHandler {
      override def onPush(): Unit = onPushArrivals(inLiveBaseArrivals, LiveBaseArrivals)
    })

    setHandler(inLiveArrivals, new InHandler {
      override def onPush(): Unit = onPushArrivals(inLiveArrivals, LiveArrivals)
    })

    setHandler(outArrivalsDiff, new OutHandler {
      override def onPull(): Unit = {
        val timer = StageTimer(stageName, outArrivalsDiff)
        pushIfAvailable(toPush, outArrivalsDiff)

        List(inLiveBaseArrivals, inLiveArrivals, inForecastArrivals, inForecastBaseArrivals).foreach(inlet => if (!hasBeenPulled(inlet)) {
          log.debug(s"Pulling Inlet: ${inlet.toString()}")
          pull(inlet)
        })
        timer.stopAndReport()
      }
    })

    def onPushArrivals(arrivalsInlet: Inlet[List[Arrival]], sourceType: ArrivalsSourceType): Unit = {
      val timer = StageTimer(stageName, outArrivalsDiff)

      val incoming = grab(arrivalsInlet)

      log.info(s"Grabbed ${incoming.length} arrivals from $arrivalsInlet of $sourceType")
      if (incoming.nonEmpty || sourceType == BaseArrivals) handleIncomingArrivals(sourceType, incoming)

      if (!hasBeenPulled(arrivalsInlet)) pull(arrivalsInlet)

      timer.stopAndReport()
    }

    def handleIncomingArrivals(sourceType: ArrivalsSourceType, incomingArrivals: Seq[Arrival]): Unit = {
      val filteredArrivals = relevantFlights(SortedMap[UniqueArrivalWithOrigin, Arrival]() ++ incomingArrivals.map(a => (UniqueArrivalWithOrigin(a), a)))
      log.info(s"${filteredArrivals.size} arrivals after filtering")
      sourceType match {
        case LiveArrivals =>
          liveArrivals = updateArrivalsSource(liveArrivals, filteredArrivals)
          toPush = mergeUpdatesFromKeys(liveArrivals.keys)
        case LiveBaseArrivals =>
          ciriumArrivals = updateArrivalsSource(ciriumArrivals, filteredArrivals)
          val missingTerminals = ciriumArrivals.count {
            case (_, a) if a.Terminal == InvalidTerminal => true
            case _ => false
          }
          log.info(s"Got $missingTerminals Cirium Arrivals with no terminal")
          toPush = mergeUpdatesFromKeys(ciriumArrivals.keys)
        case ForecastArrivals =>
          forecastArrivals = updateArrivalsSource(forecastArrivals, filteredArrivals)
          toPush = mergeUpdatesFromKeys(forecastArrivals.keys)
        case BaseArrivals =>
          aclArrivals = filteredArrivals
          toPush = mergeUpdatesFromAllSources()
      }
      pushIfAvailable(toPush.map(arrivalsAdjustments(_, merged.keys)), outArrivalsDiff)
    }

    def updateArrivalsSource(existingArrivals: SortedMap[UniqueArrivalWithOrigin, Arrival],
                             newArrivals: SortedMap[UniqueArrivalWithOrigin, Arrival]): SortedMap[UniqueArrivalWithOrigin, Arrival] = newArrivals.foldLeft(existingArrivals) {
      case (soFar, (key, newArrival)) =>
        if (!existingArrivals.contains(key) || !existingArrivals(key).equals(newArrival)) soFar + (key -> newArrival)
        else soFar
    }

    def mergeUpdatesFromAllSources(): Option[ArrivalsDiff] = maybeDiffFromAllSources().map(diff => {
      merged --= diff.toRemove.map(UniqueArrivalWithOrigin(_))
      merged ++= diff.toUpdate
      diff
    })

    def mergeUpdatesFromKeys(uniqueArrivals: Iterable[UniqueArrivalWithOrigin]): Option[ArrivalsDiff] = {
      val updatedArrivals = getUpdatesFromNonBaseArrivals(uniqueArrivals)

      merged ++= updatedArrivals

      updateDiffToPush(updatedArrivals)
    }

    def updateDiffToPush(updatedLiveArrivals: SortedMap[UniqueArrivalWithOrigin, Arrival]): Option[ArrivalsDiff] = {
      toPush match {
        case None => Option(ArrivalsDiff(SortedMap[UniqueArrivalWithOrigin, Arrival]() ++ updatedLiveArrivals, Set()))
        case Some(diff) =>
          val newToUpdate = updatedLiveArrivals.foldLeft(diff.toUpdate) {
            case (toUpdateSoFar, (ak, arrival)) => toUpdateSoFar.updated(ak, arrival)
          }
          Option(diff.copy(toUpdate = newToUpdate))
      }
    }

    def maybeDiffFromAllSources(): Option[ArrivalsDiff] = {
      purgeExpired()

      val existingArrivalsKeys = merged.keys.toSet
      val newArrivalsKeys = aclArrivals.keys.toSet ++ liveArrivals.keys.toSet
      val arrivalsWithUpdates = getUpdatesFromBaseArrivals

      val removedArrivals = (existingArrivalsKeys -- newArrivalsKeys).map(merged(_))

      arrivalsWithUpdates.foreach { case (ak, mergedArrival) =>
        merged += (ak -> mergedArrival)
      }

      if (arrivalsWithUpdates.nonEmpty || removedArrivals.nonEmpty) {
        val updates = SortedMap[UniqueArrivalWithOrigin, Arrival]() ++ arrivalsWithUpdates
        Option(ArrivalsDiff(updates, removedArrivals))
      }
      else None
    }

    def purgeExpired(): Unit = {
      liveArrivals = Crunch.purgeExpired(liveArrivals, UniqueArrivalWithOrigin.atTime, now, expireAfterMillis.toInt)
      ciriumArrivals = Crunch.purgeExpired(ciriumArrivals, UniqueArrivalWithOrigin.atTime, now, expireAfterMillis.toInt)
      forecastArrivals = Crunch.purgeExpired(forecastArrivals, UniqueArrivalWithOrigin.atTime, now, expireAfterMillis.toInt)
      aclArrivals = Crunch.purgeExpired(aclArrivals, UniqueArrivalWithOrigin.atTime, now, expireAfterMillis.toInt)
      merged = Crunch.purgeExpired(merged, UniqueArrivalWithOrigin.atTime, now, expireAfterMillis.toInt)
    }

    def relevantFlights(arrivals: SortedMap[UniqueArrivalWithOrigin, Arrival]): SortedMap[UniqueArrivalWithOrigin, Arrival] = {
      val toRemove = arrivals.filter {
        case (_, f) if !isFlightRelevant(f) =>
          log.debug(s"Filtering out irrelevant arrival: ${f.flightCodeString}, ${SDate(f.Scheduled).toISOString()}, ${f.Origin}")
          true
        case _ => false
      }.keys

      val minusRemovals = arrivals -- toRemove

      minusRemovals
    }

    def isFlightRelevant(flight: Arrival): Boolean = {
      val isValidSuffix = !flight.FlightCodeSuffix.exists(fcs => fcs.suffix == "P" || fcs.suffix == "F")
      val validTerminal = validPortTerminals.contains(flight.Terminal)
      val isDomestic = !flight.Origin.isDomestic
      validTerminal && isDomestic && isValidSuffix
    }

    def pushIfAvailable(arrivalsToPush: Option[ArrivalsDiff], outlet: Outlet[ArrivalsDiff]): Unit = {
      if (isAvailable(outlet)) {
        arrivalsToPush.foreach { diff =>
          Metrics.counter(s"$stageName.arrivals.updates", diff.toUpdate.size)
          Metrics.counter(s"$stageName.arrivals.removals", diff.toRemove.size)
          push(outArrivalsDiff, diff)
          toPush = None
        }
      } else log.debug(s"outMerged not available to push")
    }

    def getUpdatesFromBaseArrivals: SortedMap[UniqueArrivalWithOrigin, Arrival] = {
      aclArrivals.foldLeft(SortedMap[UniqueArrivalWithOrigin, Arrival]()) {
        case (soFar, (key, baseArrival)) =>
          val mergedArrival = mergeBaseArrival(baseArrival)
          if (arrivalHasUpdates(merged.get(key), mergedArrival)) soFar + (key -> mergedArrival)
          else soFar
      }
    }

    def getUpdatesFromNonBaseArrivals(keys: Iterable[UniqueArrivalWithOrigin]): SortedMap[UniqueArrivalWithOrigin, Arrival] =
      SortedMap[UniqueArrivalWithOrigin, Arrival]() ++ keys
        .foldLeft(Map[UniqueArrivalWithOrigin, Arrival]()) {
          case (updatedArrivalsSoFar, key) =>
            mergeArrival(key) match {
              case Some(mergedArrival) =>
                if (arrivalHasUpdates(merged.get(key), mergedArrival)) updatedArrivalsSoFar.updated(key, mergedArrival) else updatedArrivalsSoFar
              case None => updatedArrivalsSoFar
            }
        }

    def arrivalHasUpdates(maybeExistingArrival: Option[Arrival], updatedArrival: Arrival): Boolean = {
      maybeExistingArrival.isEmpty || !maybeExistingArrival.get.equals(updatedArrival)
    }

    def mergeBaseArrival(baseArrival: Arrival): Arrival = {
      val key = UniqueArrivalWithOrigin(baseArrival)
      mergeBestFieldsFromSources(baseArrival, mergeArrival(key).getOrElse(baseArrival))
    }

    def mergeArrival(key: UniqueArrivalWithOrigin): Option[Arrival] = {
      val maybeArrival = ciriumArrivals.get(key)
      val maybeSanitisedLiveBaseArrival = maybeArrival.map(arrivalDataSanitiser.withSaneEstimates)

      val maybeBestArrival: Option[Arrival] = (liveArrivals.get(key), maybeSanitisedLiveBaseArrival, aclArrivals.get(key)) match {
        case (Some(liveArrival), Some(ciriumArrival), _) =>
          if (ciriumArrival.Origin == liveArrival.Origin) {
            val mergedLiveArrival = LiveArrivalsUtil.mergePortFeedWithLiveBase(liveArrival, ciriumArrival)
            val sanitisedLiveArrival = ArrivalDataSanitiser
              .arrivalDataSanitiserWithoutThresholds
              .withSaneEstimates(mergedLiveArrival)
            Option(sanitisedLiveArrival)
          } else Option(liveArrival)

        case (Some(liveArrival), None, _) =>
          mergeApproxIfFoundElseOriginal(liveArrival, liveArrival.Origin, arrivalSources(List(LiveBaseArrivals)))

        case (None, Some(ciriumArrival), Some(aclArrival)) =>
          Some(ciriumArrival.copy(CarrierCode = aclArrival.CarrierCode))

        case (None, Some(ciriumArrival), None) =>
          mergeApproxIfFoundElseNone(ciriumArrival, ciriumArrival.Origin, arrivalSources(List(LiveArrivals, BaseArrivals)))

        case (None, None, Some(aclArrival)) =>
          if (SDate(aclArrival.Scheduled) < now().addHours(24))
            mergeApproxIfFoundElseOriginal(aclArrival, aclArrival.Origin, arrivalSources(List(LiveBaseArrivals)))
          else Option(aclArrival)

        case (None, None, None) => None
      }

      maybeBestArrival.map(bestArrival => {
        val arrivalForFlightCode = aclArrivals.getOrElse(key, bestArrival)
        mergeBestFieldsFromSources(arrivalForFlightCode, bestArrival)
      })
    }

    def mergeBestFieldsFromSources(baseArrival: Arrival, bestArrival: Arrival): Arrival = {
      val key = UniqueArrivalWithOrigin(baseArrival)
      val (pax, transPax) = bestPaxNos(key)
      bestArrival.copy(
        CarrierCode = baseArrival.CarrierCode,
        VoyageNumber = baseArrival.VoyageNumber,
        ActPax = pax,
        TranPax = transPax,
        Status = bestStatus(key),
        FeedSources = feedSources(key),
        PcpTime = Option(pcpArrivalTime(bestArrival).millisSinceEpoch),
        ScheduledDeparture = if (bestArrival.ScheduledDeparture.isEmpty) baseArrival.ScheduledDeparture else bestArrival.ScheduledDeparture
      )
    }

    def bestPaxNos(key: UniqueArrivalWithOrigin): (Option[Int], Option[Int]) =
      (liveArrivals.get(key), forecastArrivals.get(key), aclArrivals.get(key)) match {
        case (Some(live), _, _) if paxDefined(live) => (live.ActPax, live.TranPax)
        case (_, Some(fcst), _) if paxDefined(fcst) => (fcst.ActPax, fcst.TranPax)
        case (_, _, Some(base)) if paxDefined(base) => (base.ActPax, base.TranPax)
        case _ => (None, None)
      }

    def bestStatus(key: UniqueArrivalWithOrigin): ArrivalStatus =
      (liveArrivals.get(key), ciriumArrivals.get(key), forecastArrivals.get(key), aclArrivals.get(key)) match {
        case (Some(live), Some(liveBase), _, _) if live.Status.description == "UNK" => liveBase.Status
        case (Some(live), _, _, _) => live.Status
        case (_, Some(liveBase), _, _) => liveBase.Status
        case (_, _, Some(forecast), _) => forecast.Status
        case (_, _, _, Some(forecastBase)) => forecastBase.Status
        case _ => ArrivalStatus("Unknown")
      }

    def feedSources(uniqueArrival: UniqueArrivalWithOrigin): Set[FeedSource] = {
      List(
        liveArrivals.get(uniqueArrival).map(_ => LiveFeedSource),
        forecastArrivals.get(uniqueArrival).map(_ => ForecastFeedSource),
        aclArrivals.get(uniqueArrival).map(_ => AclFeedSource),
        ciriumArrivals.get(uniqueArrival).map(_ => LiveBaseFeedSource)
      ).flatten.toSet
    }
  }

  private def paxDefined(baseArrival: Arrival): Boolean = baseArrival.ActPax.isDefined
}
