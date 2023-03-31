package services.graphstages

import akka.stream._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import services.arrivals.{ArrivalDataSanitiser, ArrivalsAdjustmentsLike, ArrivalsAdjustmentsNoop, LiveArrivalsUtil}
import services.graphstages.ApproximateScheduleMatch.{mergeApproxIfFoundElseNone, mergeApproxIfFoundElseOriginal}
import services.metrics.{Metrics, StageTimer}
import uk.gov.homeoffice.drt.arrivals.{Arrival, ArrivalStatus, TotalPaxSource, UniqueArrival}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports._
import uk.gov.homeoffice.drt.redlist.RedListUpdates
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

import scala.collection.immutable
import scala.collection.immutable.SortedMap

sealed trait ArrivalsSourceType

case object LiveArrivals extends ArrivalsSourceType

case object LiveBaseArrivals extends ArrivalsSourceType

case object ForecastArrivals extends ArrivalsSourceType

case object BaseArrivals extends ArrivalsSourceType

object ArrivalsGraphStage {
  def terminalRemovals(incomingArrivals: Iterable[Arrival], existingArrivals: Iterable[Arrival]): Iterable[Arrival] =
    existingArrivals.filter { oldArrival =>
      val oldKey = oldArrival.unique
      val noLongerExists = !incomingArrivals.exists(_.unique == oldKey)
      val existsAtDifferentTerminal = incomingArrivals.exists { incoming =>
        val incomingKey = incoming.unique
        val numberMatches = incomingKey.number == oldKey.number
        val originMatches = incomingKey.origin == oldKey.origin
        val scheduledMatches = incomingKey.scheduled == oldKey.scheduled
        val differentTerminal = incomingKey.terminal != oldKey.terminal
        numberMatches && originMatches && scheduledMatches && differentTerminal
      }
      noLongerExists && existsAtDifferentTerminal
    }

  def terminalAdditions(incomingArrivals: Iterable[Arrival], existingArrivals: Iterable[Arrival]): Iterable[Arrival] =
    incomingArrivals.filter { newArrival =>
      val newKey = newArrival.unique
      val didNotExist = !existingArrivals.exists(_.unique == newKey)
      val existsAtDifferentTerminal = existingArrivals.exists { existing =>
        val existingKey = existing.unique
        val numberMatches = existingKey.number == newKey.number
        val originMatches = existingKey.origin == newKey.origin
        val scheduledMatches = existingKey.scheduled == newKey.scheduled
        val differentTerminal = existingKey.terminal != newKey.terminal
        numberMatches && originMatches && scheduledMatches && differentTerminal
      }
      didNotExist && existsAtDifferentTerminal
    }

  def unmatchedArrivalsPercentage(incomingArrivals: Iterable[UniqueArrival], existingArrivals: Iterable[UniqueArrival]): Double = {
    val unmatched = (incomingArrivals.toSet -- existingArrivals).size.toDouble
    unmatched / incomingArrivals.size * 100
  }

}

class ArrivalsGraphStage(name: String = "",
                         initialForecastBaseArrivals: SortedMap[UniqueArrival, Arrival],
                         initialForecastArrivals: SortedMap[UniqueArrival, Arrival],
                         initialLiveBaseArrivals: SortedMap[UniqueArrival, Arrival],
                         initialLiveArrivals: SortedMap[UniqueArrival, Arrival],
                         initialMergedArrivals: SortedMap[UniqueArrival, Arrival],
                         validPortTerminals: Set[Terminal],
                         arrivalDataSanitiser: ArrivalDataSanitiser,
                         arrivalsAdjustments: ArrivalsAdjustmentsLike,
                         expireAfterMillis: Int,
                         now: () => SDateLike,
                         flushOnStart: Boolean = false,
                        )
  extends GraphStage[FanInShape5[List[Arrival], List[Arrival], List[Arrival], List[Arrival], Boolean, ArrivalsDiff]] {
  val log: Logger = LoggerFactory.getLogger(s"$getClass-$name")

  import ArrivalsGraphStage._

  val inForecastBaseArrivals: Inlet[List[Arrival]] = Inlet[List[Arrival]]("FlightsForecastBase.in")
  val inForecastArrivals: Inlet[List[Arrival]] = Inlet[List[Arrival]]("FlightsForecast.in")
  val inLiveBaseArrivals: Inlet[List[Arrival]] = Inlet[List[Arrival]]("FlightsLiveBase.in")
  val inLiveArrivals: Inlet[List[Arrival]] = Inlet[List[Arrival]]("FlightsLive.in")
  val inFlushArrivals: Inlet[Boolean] = Inlet[Boolean]("FlushArrivals.in")
  val outArrivalsDiff: Outlet[ArrivalsDiff] = Outlet[ArrivalsDiff]("ArrivalsDiff.out")
  override val shape = new FanInShape5(inForecastBaseArrivals, inForecastArrivals, inLiveBaseArrivals, inLiveArrivals, inFlushArrivals, outArrivalsDiff)
  val stageName = "arrivals"

  val diffFromAdjustments: Option[ArrivalsDiff] =
    arrivalsAdjustments match {
      case ArrivalsAdjustmentsNoop => None
      case adjustments =>
        val adjusted = adjustments(initialMergedArrivals.values)
        val additions = terminalAdditions(adjusted, initialMergedArrivals.values)
        val removals = terminalRemovals(adjusted, initialMergedArrivals.values)

        if (removals.nonEmpty || additions.nonEmpty) {
          log.info(s"Removing ${removals.size} initial arrivals with terminal changes")
          Option(ArrivalsDiff(additions, removals))
        } else {
          log.info("No adjustments to make to initial arrivals")
          None
        }
    }

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    var redListUpdates: RedListUpdates = RedListUpdates.empty
    var forecastBaseArrivals: SortedMap[UniqueArrival, Arrival] = SortedMap()
    var forecastArrivals: SortedMap[UniqueArrival, Arrival] = SortedMap()
    var ciriumArrivals: SortedMap[UniqueArrival, Arrival] = SortedMap()
    var liveArrivals: SortedMap[UniqueArrival, Arrival] = SortedMap()
    var toPush: Option[ArrivalsDiff] = None

    val log: Logger = LoggerFactory.getLogger(s"$getClass-$name")

    def arrivalsForSource(source: ArrivalsSourceType): SortedMap[UniqueArrival, Arrival] = source match {
      case BaseArrivals => forecastBaseArrivals
      case ForecastArrivals => forecastArrivals
      case LiveArrivals => liveArrivals
      case LiveBaseArrivals => ciriumArrivals
      case _ => SortedMap[UniqueArrival, Arrival]()
    }

    def arrivalSources(sources: List[ArrivalsSourceType]): List[(ArrivalsSourceType, SortedMap[UniqueArrival, Arrival])] =
      sources.map(s => (s, arrivalsForSource(s)))

    override def preStart(): Unit = {
      log.info(s"Received ${initialForecastBaseArrivals.size} base initial arrivals")
      log.info(s"Received ${initialForecastArrivals.size} forecast initial arrivals")
      log.info(s"Received ${initialLiveBaseArrivals.size} live base initial arrivals")
      log.info(s"Received ${initialLiveArrivals.size} live initial arrivals")

      forecastBaseArrivals ++= relevantFlights(SortedMap[UniqueArrival, Arrival]() ++ initialForecastBaseArrivals)
      forecastArrivals = prepInitialArrivals(initialForecastArrivals)
      ciriumArrivals = prepInitialArrivals(initialLiveBaseArrivals)
      liveArrivals = prepInitialArrivals(initialLiveArrivals)

      toPush = diffFromAdjustments

      val existingArrivals = allMergedArrivals(forecastBaseArrivals, liveArrivals)

      val existingFeedArrivalKeys = existingArrivals.map(_.unique).toSet

      toPush = initialMergedArrivals.foldLeft(List[Arrival]()) {
        case (acc, (existingMergedKey, _)) if existingFeedArrivalKeys.contains(existingMergedKey) => acc
        case (acc, (_, existingMergedArrival)) => existingMergedArrival :: acc
      } match {
        case toRemove if toRemove.nonEmpty =>
          toPush match {
            case Some(existingDiff) => Option(existingDiff.copy(toRemove = existingDiff.toRemove ++ toRemove))
            case None => Option(ArrivalsDiff(Seq(), toRemove))
          }
        case _ => toPush
      }

      if (flushOnStart) {
        log.info(s"Flushing ${existingArrivals.size} arrivals at startup")
        toPush = Option(ArrivalsDiff(existingArrivals, Seq()))
      }

      super.preStart()
    }

    def prepInitialArrivals(initialArrivals: SortedMap[UniqueArrival, Arrival]): SortedMap[UniqueArrival, Arrival] = {
      Crunch.purgeExpired(relevantFlights(SortedMap[UniqueArrival, Arrival]() ++ initialArrivals), UniqueArrival.atTime, now, expireAfterMillis)
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

    setHandler(inFlushArrivals, new InHandler {
      override def onPush(): Unit = {
        grab(inFlushArrivals)
        val existingArrivals = allMergedArrivals(forecastBaseArrivals, liveArrivals)
        log.info(s"Flushing ${existingArrivals.size} arrivals")
        toPush = Option(ArrivalsDiff(existingArrivals, Seq()))
        if (!hasBeenPulled(inFlushArrivals)) pull(inFlushArrivals)
      }
    })

    setHandler(outArrivalsDiff, new OutHandler {
      override def onPull(): Unit = {
        val timer = StageTimer(stageName, outArrivalsDiff)
        log.info(s"Pushing ${toPush.map(_.toRemove.size).getOrElse(0)} removals and ${toPush.map(_.toUpdate.size).getOrElse(0)} additions")
        pushIfAvailable(toPush, outArrivalsDiff)

        List(inLiveBaseArrivals, inLiveArrivals, inForecastArrivals, inForecastBaseArrivals, inFlushArrivals)
          .foreach(inlet => if (!hasBeenPulled(inlet)) pull(inlet))

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
      val filteredIncoming = relevantFlights(SortedMap[UniqueArrival, Arrival]() ++ incomingArrivals.map(a => (UniqueArrival(a), a)))
      log.info(s"${filteredIncoming.size} $sourceType incoming arrivals after filtering")
      val maybeNewDiff = sourceType match {
        case LiveArrivals =>
          val updateFilteredArrivals = updateFilteredIncomingWithTotalPaxSource(filteredIncoming, LiveFeedSource)
          val terminalRemovals = ArrivalsGraphStage.terminalRemovals(updateFilteredArrivals.values, liveArrivals.values)
          val keysWithUpdates = changedArrivals(liveArrivals, updateFilteredArrivals)
          val existingMergedForKeys = keysWithUpdates.map(key => (key, mergeArrivalWithMaybeBase(key, forecastBaseArrivals.get(key)))).toMap
          liveArrivals = liveArrivals ++ updateFilteredArrivals
          if (keysWithUpdates.nonEmpty || terminalRemovals.nonEmpty)
            Option(ArrivalsDiff(getUpdatesFromNonBaseArrivals(keysWithUpdates, existingMergedForKeys), terminalRemovals))
          else None
        case LiveBaseArrivals =>
          val updateFilteredArrivals = updateFilteredIncomingWithTotalPaxSource(filteredIncoming, LiveBaseFeedSource)
          val keysWithUpdates = changedArrivals(ciriumArrivals, updateFilteredArrivals)
          val existingMergedForKeys = keysWithUpdates.map(key => (key, mergeArrivalWithMaybeBase(key, forecastBaseArrivals.get(key)))).toMap
          ciriumArrivals = ciriumArrivals ++ updateFilteredArrivals
          if (keysWithUpdates.nonEmpty) Option(ArrivalsDiff(getUpdatesFromNonBaseArrivals(keysWithUpdates, existingMergedForKeys), Seq())) else None
        case ForecastArrivals =>
          val updateFilteredArrivals = updateFilteredIncomingWithTotalPaxSource(filteredIncoming, ForecastFeedSource)
          reportUnmatchedArrivals(forecastBaseArrivals.keys, updateFilteredArrivals.keys)
          val keysWithUpdates = changedArrivals(forecastArrivals, updateFilteredArrivals)
          val existingMergedForKeys = keysWithUpdates.map(key => (key, mergeArrivalWithMaybeBase(key, forecastBaseArrivals.get(key)))).toMap
          forecastArrivals = forecastArrivals ++ updateFilteredArrivals
          if (keysWithUpdates.nonEmpty) Option(ArrivalsDiff(getUpdatesFromNonBaseArrivals(keysWithUpdates, existingMergedForKeys), Seq())) else None
        case BaseArrivals =>
          val updateFilteredArrivals = updateFilteredIncomingWithTotalPaxSource(filteredIncoming, AclFeedSource)
          val diff = maybeDiffFromAllSources(updateFilteredArrivals)
          forecastBaseArrivals = updateFilteredArrivals
          diff
      }

      arrivalsAdjustments match {
        case ArrivalsAdjustmentsNoop =>
          maybeNewDiff.foreach(newDiff => toPush = updateMaybeDiff(newDiff, toPush))

        case _ =>
          maybeNewDiff.foreach { newDiff =>
            val adjustedUpdates = arrivalsAdjustments(newDiff.toUpdate.values)
            val adjustedRemovals = arrivalsAdjustments(newDiff.toRemove)
            val oldTerminalRemovals = terminalRemovals(adjustedUpdates, newDiff.toUpdate.values)
            val diffWithAdjustments = ArrivalsDiff(adjustedUpdates, adjustedRemovals ++ oldTerminalRemovals)
            toPush = updateMaybeDiff(diffWithAdjustments, toPush)
          }
      }
      log.info(s"Pushing ${toPush.map(_.toRemove.size).getOrElse(0)} removals and ${toPush.map(_.toUpdate.size).getOrElse(0)} additions")
      pushIfAvailable(toPush, outArrivalsDiff)
    }

    private def updateMaybeDiff(diffWithAdjustments: ArrivalsDiff, maybeExistingDiff: Option[ArrivalsDiff]): Option[ArrivalsDiff] =
      maybeExistingDiff match {
        case Some(existingDiff) =>
          Option(existingDiff.copy(
            toUpdate = existingDiff.toUpdate ++ diffWithAdjustments.toUpdate,
            toRemove = existingDiff.toRemove ++ diffWithAdjustments.toRemove))
        case None =>
          Option(diffWithAdjustments)
      }

    def updateFilteredIncomingWithTotalPaxSource(filteredIncoming: SortedMap[UniqueArrival, Arrival], feedSource: FeedSource): SortedMap[UniqueArrival, Arrival] =
      filteredIncoming.map { case (k, arrival) =>
        k -> arrival.copy(TotalPax = arrival.TotalPax.updated(feedSource, arrival.ActPax))
      }

    def changedArrivals(existingArrivals: SortedMap[UniqueArrival, Arrival],
                        newArrivals: SortedMap[UniqueArrival, Arrival]): immutable.Iterable[UniqueArrival] = newArrivals.collect {
      case (key, newArrival) if !existingArrivals.contains(key) || !existingArrivals(key).isEqualTo(newArrival) => key
    }

    def maybeDiffFromAllSources(incomingForecastBase: SortedMap[UniqueArrival, Arrival]): Option[ArrivalsDiff] = {
      purgeExpired()

      val existingArrivalsKeys = liveArrivals.keys.toSet ++ forecastBaseArrivals.keys.toSet
      val newArrivalsKeys = incomingForecastBase.keys.toSet ++ liveArrivals.keys.toSet
      val arrivalsWithUpdates = incomingForecastBase.foldLeft(Map[UniqueArrival, Arrival]()) {
        case (acc, (incomingKey, newBaseArrival)) =>
          val newMerged = mergeBaseArrival(newBaseArrival)
          if (existingArrivalsKeys.contains(incomingKey)) {
            mergeArrivalWithMaybeBase(incomingKey, forecastBaseArrivals.get(incomingKey)) match {
              case Some(existingMerged) if newMerged.isEqualTo(existingMerged) => acc
              case _ => acc + (incomingKey -> newMerged)
            }
          } else {
            acc + (incomingKey -> newMerged)
          }
      }

      val pastCutOffTime = now().getUtcLastMidnight.millisSinceEpoch

      val removedArrivalsInFuture = (existingArrivalsKeys -- newArrivalsKeys)
        .filter(_.scheduled >= pastCutOffTime)
        .map(removalKey => mergeArrivalWithMaybeBase(removalKey, forecastBaseArrivals.get(removalKey)))
        .collect {
          case Some(arrivalToRemove) => arrivalToRemove
        }

      if (arrivalsWithUpdates.nonEmpty || removedArrivalsInFuture.nonEmpty)
        Option(ArrivalsDiff(SortedMap[UniqueArrival, Arrival]() ++ arrivalsWithUpdates, removedArrivalsInFuture))
      else None
    }

    def purgeExpired(): Unit = {
      liveArrivals = Crunch.purgeExpired(liveArrivals, UniqueArrival.atTime, now, expireAfterMillis)
      ciriumArrivals = Crunch.purgeExpired(ciriumArrivals, UniqueArrival.atTime, now, expireAfterMillis)
      forecastArrivals = Crunch.purgeExpired(forecastArrivals, UniqueArrival.atTime, now, expireAfterMillis)
      forecastBaseArrivals = Crunch.purgeExpired(forecastBaseArrivals, UniqueArrival.atTime, now, expireAfterMillis)
    }

    def relevantFlights(arrivals: SortedMap[UniqueArrival, Arrival]): SortedMap[UniqueArrival, Arrival] = {
      val toRemove = arrivals.filter {
        case (_, f) if !isFlightRelevant(f) =>
          log.debug(s"Filtering out irrelevant arrival: ${f.flightCodeString}, ${SDate(f.Scheduled).toISOString}, ${f.Origin}")
          true
        case (_, _) => false
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
          log.info(s"Pushing ${diff.toUpdate.size} updates and ${diff.toRemove.size} removals")
          Metrics.counter(s"$stageName.arrivals.updates", diff.toUpdate.size)
          Metrics.counter(s"$stageName.arrivals.removals", diff.toRemove.size)
          push(outArrivalsDiff, diff)
          toPush = None
        }
      } else log.debug(s"outMerged not available to push")
    }

    def getUpdatesFromNonBaseArrivals(keys: Iterable[UniqueArrival], maybeExisting: Map[UniqueArrival, Option[Arrival]]): SortedMap[UniqueArrival, Arrival] =
      SortedMap[UniqueArrival, Arrival]() ++ keys
        .foldLeft(Map[UniqueArrival, Arrival]()) {
          case (updatedArrivalsSoFar, key) =>
            mergeArrivalWithMaybeBase(key, forecastBaseArrivals.get(key)) match {
              case Some(mergedIncoming) =>
                if (arrivalHasUpdates(maybeExisting.get(key).flatten, mergedIncoming)) updatedArrivalsSoFar.updated(key, mergedIncoming) else updatedArrivalsSoFar
              case None => updatedArrivalsSoFar
            }
        }

    def arrivalHasUpdates(maybeExistingArrival: Option[Arrival], updatedArrival: Arrival): Boolean = {
      maybeExistingArrival.isEmpty || !maybeExistingArrival.get.isEqualTo(updatedArrival)
    }

    def mergeBaseArrival(baseArrival: Arrival): Arrival = {
      val merged = mergeBestFields(baseArrival, mergeArrivalWithMaybeBase(baseArrival.unique, Option(baseArrival)).getOrElse(baseArrival))
      merged.copy(
        FeedSources = merged.FeedSources + AclFeedSource,
        TotalPax = merged.TotalPax.updated(AclFeedSource, baseArrival.ActPax)
      )
    }

    def mergeArrivalWithMaybeBase(key: UniqueArrival, maybeBaseArrival: Option[Arrival]): Option[Arrival] = {
      val maybeLiveBaseArrival = ciriumArrivals.get(key)
      val maybeSanitisedLiveBaseArrival = maybeLiveBaseArrival.map(arrivalDataSanitiser.withSaneEstimates)

      val maybeBestArrival: Option[Arrival] = (liveArrivals.get(key), maybeSanitisedLiveBaseArrival, maybeBaseArrival) match {
        case (Some(liveArrival), Some(liveBaseArrival), _) =>
          if (liveBaseArrival.Origin == liveArrival.Origin) {
            val mergedLiveArrival = LiveArrivalsUtil.mergePortFeedWithLiveBase(liveArrival, liveBaseArrival)
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
        val arrivalForFlightCode = maybeBaseArrival.getOrElse(bestArrival)
        mergeBestFields(arrivalForFlightCode, bestArrival)
      })
    }

    def mergeBestFields(baseArrival: Arrival, bestArrival: Arrival): Arrival = {
      val key = UniqueArrival(baseArrival)
      val (pax, transPax) = bestPaxNos(key, baseArrival.ActPax, baseArrival.TranPax)
      bestArrival.copy(
        CarrierCode = baseArrival.CarrierCode,
        VoyageNumber = baseArrival.VoyageNumber,
        ActPax = pax,
        TranPax = transPax,
        Status = bestStatus(key, Option(baseArrival.Status)),
        FeedSources = feedSources(key),
        ScheduledDeparture = if (bestArrival.ScheduledDeparture.isEmpty) baseArrival.ScheduledDeparture else bestArrival.ScheduledDeparture,
        TotalPax = totalPaxSources(key),
      )
    }

    def bestPaxNos(key: UniqueArrival, maybeBasePax: Option[Int], maybeBaseTransPax: Option[Int]): (Option[Int], Option[Int]) =
      (liveArrivals.get(key), forecastArrivals.get(key), maybeBasePax, maybeBaseTransPax) match {
        case (Some(live), _, _, _) if paxDefined(live) => (live.ActPax, live.TranPax)
        case (_, _, Some(zeroPaxInBase), _) if zeroPaxInBase == 0 => (Option(0), Option(0))
        case (_, Some(fcst), _, _) if paxDefined(fcst) => (fcst.ActPax, fcst.TranPax)
        case (_, _, Some(basePax), _) => (Option(basePax), maybeBaseTransPax)
        case _ => (None, None)
      }

    def bestStatus(key: UniqueArrival, maybeBaseStatus: Option[ArrivalStatus]): ArrivalStatus =
      (liveArrivals.get(key), ciriumArrivals.get(key), forecastArrivals.get(key), maybeBaseStatus) match {
        case (Some(live), Some(liveBase), _, _) if live.Status.description == "UNK" => liveBase.Status
        case (Some(live), _, _, _) => live.Status
        case (_, Some(liveBase), _, _) => liveBase.Status
        case (_, _, Some(forecast), _) => forecast.Status
        case (_, _, _, Some(forecastBase)) => forecastBase
        case _ => ArrivalStatus("Unknown")
      }

    def feedSources(uniqueArrival: UniqueArrival): Set[FeedSource] = List(
      liveArrivals.get(uniqueArrival).map(_ => LiveFeedSource),
      forecastArrivals.get(uniqueArrival).map(_ => ForecastFeedSource),
      forecastBaseArrivals.get(uniqueArrival).map(_ => AclFeedSource),
      ciriumArrivals.get(uniqueArrival).map(_ => LiveBaseFeedSource)
    ).flatten.toSet

    def totalPaxSources(uniqueArrival: UniqueArrival): Map[FeedSource, Option[Int]] = List(
      liveArrivals.get(uniqueArrival).map(a => (LiveFeedSource, a.ActPax)),
      forecastArrivals.get(uniqueArrival).map(a => (ForecastFeedSource, a.ActPax)),
      forecastBaseArrivals.get(uniqueArrival).map(a => (AclFeedSource, a.ActPax)),
      ciriumArrivals.get(uniqueArrival).map(a => (LiveBaseFeedSource, a.ActPax))
    ).flatten.toMap

    private def allMergedArrivals(forecastBaseArrivals: SortedMap[UniqueArrival, Arrival], liveArrivals: SortedMap[UniqueArrival, Arrival]) = {
      val arrivals = (liveArrivals.keys ++ forecastBaseArrivals.keys)
        .map(key => mergeArrivalWithMaybeBase(key, forecastBaseArrivals.get(key)))
        .collect {
          case Some(mergedArrival) => mergedArrival
        }

      arrivalsAdjustments(arrivals)
    }

  }

  private def reportUnmatchedArrivals(forecastBaseArrivals: Iterable[UniqueArrival], filteredArrivals: Iterable[UniqueArrival]): Unit =
    Metrics.counter(s"arrivals-forecast-unmatched-percentage", unmatchedArrivalsPercentage(forecastBaseArrivals, filteredArrivals))

  private def paxDefined(baseArrival: Arrival): Boolean = baseArrival.ActPax.isDefined
}
