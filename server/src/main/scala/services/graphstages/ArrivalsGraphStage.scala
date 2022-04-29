package services.graphstages

import akka.stream._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import services.SDate
import services.arrivals.{ArrivalDataSanitiser, ArrivalsAdjustmentsLike, ArrivalsAdjustmentsNoop, LiveArrivalsUtil}
import services.graphstages.ApproximateScheduleMatch.{mergeApproxIfFoundElseNone, mergeApproxIfFoundElseOriginal}
import services.metrics.{Metrics, StageTimer}
import uk.gov.homeoffice.drt.arrivals.{Arrival, ArrivalStatus, UniqueArrival}
import uk.gov.homeoffice.drt.ports.Terminals.{InvalidTerminal, Terminal}
import uk.gov.homeoffice.drt.ports._
import uk.gov.homeoffice.drt.redlist.{DeleteRedListUpdates, RedListUpdateCommand, RedListUpdates, SetRedListUpdate}
import uk.gov.homeoffice.drt.time.SDateLike

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
                         initialRedListUpdates: RedListUpdates,
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
  extends GraphStage[FanInShape5[List[Arrival], List[Arrival], List[Arrival], List[Arrival], List[RedListUpdateCommand], ArrivalsDiff]] {

  import ArrivalsGraphStage._

  val inForecastBaseArrivals: Inlet[List[Arrival]] = Inlet[List[Arrival]]("FlightsForecastBase.in")
  val inForecastArrivals: Inlet[List[Arrival]] = Inlet[List[Arrival]]("FlightsForecast.in")
  val inLiveBaseArrivals: Inlet[List[Arrival]] = Inlet[List[Arrival]]("FlightsLiveBase.in")
  val inLiveArrivals: Inlet[List[Arrival]] = Inlet[List[Arrival]]("FlightsLive.in")
  val inRedListUpdates: Inlet[List[RedListUpdateCommand]] = Inlet[List[RedListUpdateCommand]]("RedListUpdates.in")
  val outArrivalsDiff: Outlet[ArrivalsDiff] = Outlet[ArrivalsDiff]("ArrivalsDiff.out")
  override val shape = new FanInShape5(inForecastBaseArrivals, inForecastArrivals, inLiveBaseArrivals, inLiveArrivals, inRedListUpdates, outArrivalsDiff)
  val stageName = "arrivals"

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    var redListUpdates: RedListUpdates = RedListUpdates.empty
    var forecastBaseArrivals: SortedMap[UniqueArrival, Arrival] = SortedMap()
    var forecastArrivals: SortedMap[UniqueArrival, Arrival] = SortedMap()
    var ciriumArrivals: SortedMap[UniqueArrival, Arrival] = SortedMap()
    var liveArrivals: SortedMap[UniqueArrival, Arrival] = SortedMap()
    var merged: SortedMap[UniqueArrival, Arrival] = SortedMap()
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
      redListUpdates = initialRedListUpdates
      log.info(s"Received ${initialForecastBaseArrivals.size} base initial arrivals")
      forecastBaseArrivals ++= relevantFlights(SortedMap[UniqueArrival, Arrival]() ++ initialForecastBaseArrivals)
      log.info(s"Received ${initialForecastArrivals.size} forecast initial arrivals")
      forecastArrivals = prepInitialArrivals(initialForecastArrivals)

      log.info(s"Received ${initialLiveBaseArrivals.size} live base initial arrivals")
      ciriumArrivals = prepInitialArrivals(initialLiveBaseArrivals)
      log.info(s"Received ${initialLiveArrivals.size} live initial arrivals")
      liveArrivals = prepInitialArrivals(initialLiveArrivals)

      val (withAdjustments, removals) = arrivalsAdjustments match {
        case ArrivalsAdjustmentsNoop =>
          (initialMergedArrivals, Iterable[Arrival]())
        case adjustments =>
          val adjusted = adjustments(initialMergedArrivals.values, redListUpdates)
          val adjustedByUnique = SortedMap[UniqueArrival, Arrival]() ++ adjusted.map(a => (a.unique, a))
          val removals = terminalRemovals(adjusted, initialMergedArrivals.values)
          val additions = terminalAdditions(adjusted, initialMergedArrivals.values)

          println(s"removing ${removals.map(a => (a.unique, SDate(a.unique.scheduled).toISOString())).mkString("\n")}")
          println(s"adding ${additions.map(a => (a.unique, SDate(a.unique.scheduled).toISOString())).mkString("\n")}")

          if (removals.nonEmpty || additions.nonEmpty) {
            log.info(s"Removing ${removals.size} initial arrivals with terminal changes")
            toPush = Option(ArrivalsDiff(additions, removals))
          } else log.info("No adjustments to make to initial arrivals")

          (adjustedByUnique, removals)
      }

      merged = withAdjustments

      if (flushOnStart) {
        log.info(s"Flushing ${merged.size} arrivals at startup")
        toPush = Option(ArrivalsDiff(merged, Seq()))
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

    setHandler(inRedListUpdates, new InHandler {
      override def onPush(): Unit = {
        val updates = grab(inRedListUpdates)
        redListUpdates = updates.foldLeft(redListUpdates) {
          case (acc, update: SetRedListUpdate) => acc.update(update)
          case (acc, DeleteRedListUpdates(date)) => acc.remove(date)
        }
        log.info(s"Received ${updates.size} red list update commands")
        if (arrivalsAdjustments != ArrivalsAdjustmentsNoop) {
          log.info("Recalculating all arrivals due to red list change")
          handleIncomingArrivals(BaseArrivals, arrivalsAdjustments.apply(forecastBaseArrivals.values, redListUpdates).toList)
        }

        if (!hasBeenPulled(inRedListUpdates)) pull(inRedListUpdates)
      }
    })

    setHandler(outArrivalsDiff, new OutHandler {
      override def onPull(): Unit = {
        val timer = StageTimer(stageName, outArrivalsDiff)
        pushIfAvailable(toPush, outArrivalsDiff)

        List(inLiveBaseArrivals, inLiveArrivals, inForecastArrivals, inForecastBaseArrivals, inRedListUpdates).foreach(inlet => if (!hasBeenPulled(inlet)) {
          log.debug(s"Pulling Inlet: ${inlet.toString()}")
          pull(inlet)
        })
        timer.stopAndReport()
      }
    })

    def onPushArrivals(arrivalsInlet: Inlet[List[Arrival]], sourceType: ArrivalsSourceType): Unit = {
      val timer = StageTimer(stageName, outArrivalsDiff)

//      val incoming = arrivalsAdjustments.apply(grab(arrivalsInlet), redListUpdates).toList
      val incoming = grab(arrivalsInlet)

      log.info(s"Grabbed ${incoming.length} arrivals from $arrivalsInlet of $sourceType")
      if (incoming.nonEmpty || sourceType == BaseArrivals) handleIncomingArrivals(sourceType, incoming)

      if (!hasBeenPulled(arrivalsInlet)) pull(arrivalsInlet)

      timer.stopAndReport()
    }

    def handleIncomingArrivals(sourceType: ArrivalsSourceType, incomingArrivals: Seq[Arrival]): Unit = {
      val filteredArrivals = relevantFlights(SortedMap[UniqueArrival, Arrival]() ++ incomingArrivals.map(a => (UniqueArrival(a), a)))
      log.info(s"${filteredArrivals.size} arrivals after filtering")
      val maybeNewDiff = sourceType match {
        case LiveArrivals =>
//          val toRemove = terminalRemovals(incomingArrivals, liveArrivals.values)
          liveArrivals = updateArrivalsSource(liveArrivals, filteredArrivals)
          mergeUpdatesFromKeys(liveArrivals.keys)//.map { diff => diff.copy(toRemove = diff.toRemove ++ toRemove) }
        case LiveBaseArrivals =>
          ciriumArrivals = updateArrivalsSource(ciriumArrivals, filteredArrivals)
          val missingTerminals = ciriumArrivals.count {
            case (_, a) if a.Terminal == InvalidTerminal => true
            case _ => false
          }
          log.info(s"Got $missingTerminals Cirium Arrivals with no terminal")
          mergeUpdatesFromKeys(ciriumArrivals.keys)
        case ForecastArrivals =>
          reportUnmatchedArrivals(forecastBaseArrivals.keys, filteredArrivals.keys)
          forecastArrivals = updateArrivalsSource(forecastArrivals, filteredArrivals)
          mergeUpdatesFromKeys(forecastArrivals.keys)
        case BaseArrivals =>
          forecastBaseArrivals = filteredArrivals
          mergeUpdatesFromAllSources()
      }

      toPush = (maybeNewDiff, toPush) match {
        case (Some(newDiff), Some(existingDiff)) =>
          Option(existingDiff.copy(existingDiff.toUpdate ++ newDiff.toUpdate, existingDiff.toRemove ++ newDiff.toRemove))
        case (None, Some(existingDiff)) => Option(existingDiff)
        case (Some(newDiff), None) => Option(newDiff)
        case _ => None
      }

      pushIfAvailable(toPush, outArrivalsDiff)
    }

    def updateArrivalsSource(existingArrivals: SortedMap[UniqueArrival, Arrival],
                             newArrivals: SortedMap[UniqueArrival, Arrival]): SortedMap[UniqueArrival, Arrival] = newArrivals.foldLeft(existingArrivals) {
      case (soFar, (key, newArrival)) =>
        if (!existingArrivals.contains(key) || !existingArrivals(key).isEqualTo(newArrival)) soFar + (key -> newArrival)
        else soFar
    }

    def mergeUpdatesFromAllSources(): Option[ArrivalsDiff] = maybeDiffFromAllSources().map(diff => {
      merged --= diff.toRemove.map(UniqueArrival(_))
      merged ++= diff.toUpdate
      diff
    })

    def mergeUpdatesFromKeys(uniqueArrivals: Iterable[UniqueArrival]): Option[ArrivalsDiff] = {
      val updatedArrivals = getUpdatesFromNonBaseArrivals(uniqueArrivals)

      merged ++= updatedArrivals

      updateDiffToPush(updatedArrivals)
    }

    def updateDiffToPush(updatedLiveArrivals: SortedMap[UniqueArrival, Arrival]): Option[ArrivalsDiff] = {
      toPush match {
        case None => Option(ArrivalsDiff(SortedMap[UniqueArrival, Arrival]() ++ updatedLiveArrivals, Set()))
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
      val newArrivalsKeys = forecastBaseArrivals.keys.toSet ++ liveArrivals.keys.toSet
      val arrivalsWithUpdates = getUpdatesFromBaseArrivals

      val removedArrivalsInFuture = filterArrivalsBeforeToday((existingArrivalsKeys -- newArrivalsKeys).map(merged(_)))

      arrivalsWithUpdates.foreach { case (ak, mergedArrival) =>
        merged += (ak -> mergedArrival)
      }

      if (arrivalsWithUpdates.nonEmpty || removedArrivalsInFuture.nonEmpty) {
        val updates = SortedMap[UniqueArrival, Arrival]() ++ arrivalsWithUpdates
        Option(ArrivalsDiff(updates, removedArrivalsInFuture))
      }
      else None
    }

    def purgeExpired(): Unit = {
      liveArrivals = Crunch.purgeExpired(liveArrivals, UniqueArrival.atTime, now, expireAfterMillis)
      ciriumArrivals = Crunch.purgeExpired(ciriumArrivals, UniqueArrival.atTime, now, expireAfterMillis)
      forecastArrivals = Crunch.purgeExpired(forecastArrivals, UniqueArrival.atTime, now, expireAfterMillis)
      forecastBaseArrivals = Crunch.purgeExpired(forecastBaseArrivals, UniqueArrival.atTime, now, expireAfterMillis)
      merged = Crunch.purgeExpired(merged, UniqueArrival.atTime, now, expireAfterMillis)
    }

    def relevantFlights(arrivals: SortedMap[UniqueArrival, Arrival]): SortedMap[UniqueArrival, Arrival] = {
      val toRemove = arrivals.filter {
        case (_, f) if !isFlightRelevant(f) =>
          log.debug(s"Filtering out irrelevant arrival: ${f.flightCodeString}, ${SDate(f.Scheduled).toISOString()}, ${f.Origin}")
          true
        case (_, f) => false
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

    def getUpdatesFromBaseArrivals: SortedMap[UniqueArrival, Arrival] = {
      forecastBaseArrivals.foldLeft(SortedMap[UniqueArrival, Arrival]()) {
        case (soFar, (key, baseArrival)) =>
          val mergedArrival = mergeBaseArrival(baseArrival)
          if (arrivalHasUpdates(merged.get(key), mergedArrival)) soFar + (key -> mergedArrival)
          else soFar
      }
    }

    def getUpdatesFromNonBaseArrivals(keys: Iterable[UniqueArrival]): SortedMap[UniqueArrival, Arrival] =
      SortedMap[UniqueArrival, Arrival]() ++ keys
        .foldLeft(Map[UniqueArrival, Arrival]()) {
          case (updatedArrivalsSoFar, key) =>
            mergeArrival(key) match {
              case Some(mergedArrival) =>
                if (arrivalHasUpdates(merged.get(key), mergedArrival)) updatedArrivalsSoFar.updated(key, mergedArrival) else updatedArrivalsSoFar
              case None => updatedArrivalsSoFar
            }
        }

    def arrivalHasUpdates(maybeExistingArrival: Option[Arrival], updatedArrival: Arrival): Boolean = {
      maybeExistingArrival.isEmpty || !maybeExistingArrival.get.isEqualTo(updatedArrival)
    }

    def mergeBaseArrival(baseArrival: Arrival): Arrival = {
      val key = UniqueArrival(baseArrival)
      mergeBestFieldsFromSources(baseArrival, mergeArrival(key).getOrElse(baseArrival))
    }

    def mergeArrival(key: UniqueArrival): Option[Arrival] = {
      val maybeArrival = ciriumArrivals.get(key)
      val maybeSanitisedLiveBaseArrival = maybeArrival.map(arrivalDataSanitiser.withSaneEstimates)

      val maybeBestArrival: Option[Arrival] = (liveArrivals.get(key), maybeSanitisedLiveBaseArrival, forecastBaseArrivals.get(key)) match {
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
        val arrivalForFlightCode = forecastBaseArrivals.getOrElse(key, bestArrival)
        mergeBestFieldsFromSources(arrivalForFlightCode, bestArrival)
      })
    }

    def mergeBestFieldsFromSources(baseArrival: Arrival, bestArrival: Arrival): Arrival = {
      val key = UniqueArrival(baseArrival)
      val (pax, transPax) = bestPaxNos(key)
      bestArrival.copy(
        CarrierCode = baseArrival.CarrierCode,
        VoyageNumber = baseArrival.VoyageNumber,
        ActPax = pax,
        TranPax = transPax,
        Status = bestStatus(key),
        FeedSources = feedSources(key),
        ScheduledDeparture = if (bestArrival.ScheduledDeparture.isEmpty) baseArrival.ScheduledDeparture else bestArrival.ScheduledDeparture
      )
    }

    def bestPaxNos(key: UniqueArrival): (Option[Int], Option[Int]) =
      (liveArrivals.get(key), forecastArrivals.get(key), forecastBaseArrivals.get(key)) match {
        case (Some(live), _, _) if paxDefined(live) => (live.ActPax, live.TranPax)
        case (_, _, Some(base)) if base.ActPax.contains(0) => (Option(0), Option(0))
        case (_, Some(fcst), _) if paxDefined(fcst) => (fcst.ActPax, fcst.TranPax)
        case (_, _, Some(base)) if paxDefined(base) => (base.ActPax, base.TranPax)
        case _ => (None, None)
      }

    def bestStatus(key: UniqueArrival): ArrivalStatus =
      (liveArrivals.get(key), ciriumArrivals.get(key), forecastArrivals.get(key), forecastBaseArrivals.get(key)) match {
        case (Some(live), Some(liveBase), _, _) if live.Status.description == "UNK" => liveBase.Status
        case (Some(live), _, _, _) => live.Status
        case (_, Some(liveBase), _, _) => liveBase.Status
        case (_, _, Some(forecast), _) => forecast.Status
        case (_, _, _, Some(forecastBase)) => forecastBase.Status
        case _ => ArrivalStatus("Unknown")
      }

    def feedSources(uniqueArrival: UniqueArrival): Set[FeedSource] = {
      List(
        liveArrivals.get(uniqueArrival).map(_ => LiveFeedSource),
        forecastArrivals.get(uniqueArrival).map(_ => ForecastFeedSource),
        forecastBaseArrivals.get(uniqueArrival).map(_ => AclFeedSource),
        ciriumArrivals.get(uniqueArrival).map(_ => LiveBaseFeedSource)
      ).flatten.toSet
    }
  }

  private def reportUnmatchedArrivals(forecastBaseArrivals: Iterable[UniqueArrival], filteredArrivals: Iterable[UniqueArrival]): Unit =
    Metrics.counter(s"arrivals-forecast-unmatched-percentage", unmatchedArrivalsPercentage(forecastBaseArrivals, filteredArrivals))

  private def filterArrivalsBeforeToday(removedArrivals: Set[Arrival]) = {
    removedArrivals.filterNot(_.Scheduled < now().getUtcLastMidnight.millisSinceEpoch)
  }

  private def paxDefined(baseArrival: Arrival): Boolean = baseArrival.ActPax.isDefined
}
