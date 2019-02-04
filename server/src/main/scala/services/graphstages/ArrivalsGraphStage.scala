package services.graphstages

import akka.stream._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import drt.shared.FlightsApi.Flights
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import server.feeds.{ArrivalsFeedFailure, ArrivalsFeedResponse, ArrivalsFeedSuccess}
import services.SDate

import scala.collection.immutable.Map
import scala.language.postfixOps


sealed trait ArrivalsSourceType

case object LiveArrivals extends ArrivalsSourceType

case object ForecastArrivals extends ArrivalsSourceType

case object BaseArrivals extends ArrivalsSourceType

class ArrivalsGraphStage(name: String = "",
                         initialBaseArrivals: Set[Arrival],
                         initialForecastArrivals: Set[Arrival],
                         initialLiveArrivals: Set[Arrival],
                         initialMergedArrivals: Map[Int, Arrival],
                         pcpArrivalTime: Arrival => MilliDate,
                         validPortTerminals: Set[String],
                         expireAfterMillis: Long,
                         now: () => SDateLike)
  extends GraphStage[FanInShape3[ArrivalsFeedResponse, ArrivalsFeedResponse, ArrivalsFeedResponse, ArrivalsDiff]] {

  val inBaseArrivals: Inlet[ArrivalsFeedResponse] = Inlet[ArrivalsFeedResponse]("inFlightsBase.in")
  val inForecastArrivals: Inlet[ArrivalsFeedResponse] = Inlet[ArrivalsFeedResponse]("inFlightsForecast.in")
  val inLiveArrivals: Inlet[ArrivalsFeedResponse] = Inlet[ArrivalsFeedResponse]("inFlightsLive.in")
  val outArrivalsDiff: Outlet[ArrivalsDiff] = Outlet[ArrivalsDiff]("outArrivalsDiff.in")
  override val shape = new FanInShape3(inBaseArrivals, inForecastArrivals, inLiveArrivals, outArrivalsDiff)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    var baseArrivals: Map[Int, Arrival] = Map()
    var forecastArrivals: Map[Int, Arrival] = Map()
    var liveArrivals: Map[Int, Arrival] = Map()
    var merged: Map[Int, Arrival] = Map()
    var toPush: Option[ArrivalsDiff] = None

    val log: Logger = LoggerFactory.getLogger(s"$getClass-$name")

    override def preStart(): Unit = {
      log.info(s"Received ${initialBaseArrivals.size} initial base arrivals")
      baseArrivals = filterAndSetPcp(initialBaseArrivals.toSeq)
      log.info(s"Received ${initialForecastArrivals.size} initial forecast arrivals")
      forecastArrivals = prepInitialArrivals(initialForecastArrivals)
      log.info(s"Received ${initialLiveArrivals.size} initial live arrivals")
      liveArrivals = prepInitialArrivals(initialLiveArrivals)
      merged = initialMergedArrivals
      super.preStart()
    }

    def prepInitialArrivals(arrivals: Set[Arrival]): Map[Int, Arrival] = {
      val arrivalsWithPcp = filterAndSetPcp(arrivals.toSeq)
      Crunch.purgeExpired(arrivalsWithPcp, (a: Arrival) => a.PcpTime.getOrElse(0L), now, expireAfterMillis)
    }

    setHandler(inBaseArrivals, new InHandler {
      override def onPush(): Unit = onPushArrivals(inBaseArrivals, BaseArrivals)
    })

    setHandler(inForecastArrivals, new InHandler {
      override def onPush(): Unit = onPushArrivals(inForecastArrivals, ForecastArrivals)
    })

    setHandler(inLiveArrivals, new InHandler {
      override def onPush(): Unit = onPushArrivals(inLiveArrivals, LiveArrivals)
    })

    def onPushArrivals(arrivalsInlet: Inlet[ArrivalsFeedResponse], sourceType: ArrivalsSourceType): Unit = {
      val start = SDate.now()
      log.info(s"$arrivalsInlet onPush() grabbing flights")

      grab(arrivalsInlet) match {
        case ArrivalsFeedSuccess(Flights(flights), connectedAt) =>
          log.info(s"Grabbed ${flights.length} arrivals from connection at ${connectedAt.toISOString()}")
          if (flights.nonEmpty || sourceType == BaseArrivals) handleIncomingArrivals(sourceType, flights)
          else log.info(s"No arrivals to handle")

        case ArrivalsFeedFailure(message, failedAt) =>
          log.warn(s"$arrivalsInlet failed at ${failedAt.toISOString()}: $message")
      }

      if (!hasBeenPulled(arrivalsInlet)) pull(arrivalsInlet)
      log.info(s"$arrivalsInlet Took ${SDate.now().millisSinceEpoch - start.millisSinceEpoch}ms")
    }

    def handleIncomingArrivals(sourceType: ArrivalsSourceType, incomingArrivals: Seq[Arrival]): Unit = {
      val filteredArrivals = filterAndSetPcp(incomingArrivals)
      log.info(s"${filteredArrivals.size} arrivals after filtering: $filteredArrivals")
      sourceType match {
        case LiveArrivals =>
          val updatedLiveArrivals = updatesFromIncoming(filteredArrivals.values.toSeq, LiveFeedSource)

          liveArrivals = incomingArrivals.map(arrival => (arrival.uniqueId, arrival)).toMap
          merged = updatedLiveArrivals.foldLeft(merged) {
            case (mergedSoFar, updatedArrival) => mergedSoFar.updated(updatedArrival.uniqueId, updatedArrival)
          }

          toPush = updateToPush(updatedLiveArrivals)
          pushIfAvailable(toPush, outArrivalsDiff)

        case ForecastArrivals =>
          val updatedForecastArrivals = updatesFromIncoming(filteredArrivals.values.toSeq, ForecastFeedSource)

          forecastArrivals = incomingArrivals.map(arrival => (arrival.uniqueId, arrival)).toMap
          merged = updatedForecastArrivals.foldLeft(merged) {
            case (mergedSoFar, updatedArrival) => mergedSoFar.updated(updatedArrival.uniqueId, updatedArrival)
          }

          toPush = updateToPush(updatedForecastArrivals)
          pushIfAvailable(toPush, outArrivalsDiff)

        case BaseArrivals =>
          baseArrivals = filteredArrivals

          mergeAllSources(baseArrivals, forecastArrivals, liveArrivals) match {
            case None =>
            case Some(diff) =>
              merged = diff.toUpdate.foldLeft(merged -- diff.toRemove) {
                case (mergedSoFar, updatedArrival) => mergedSoFar.updated(updatedArrival.uniqueId, updatedArrival)
              }
              toPush = Option(diff)
          }

          pushIfAvailable(toPush, outArrivalsDiff)
      }
    }

    def updateToPush(updatedLiveArrivals: Seq[Arrival]): Option[ArrivalsDiff] = {
      toPush match {
        case None => Option(ArrivalsDiff(updatedLiveArrivals.toSet, Set()))
        case Some(diff) =>
          val map = diff.toUpdate.toSeq.map(a => (a.uniqueId, a)).toMap
          val newToUpdate = updatedLiveArrivals.foldLeft(map) {
            case (toUpdateSoFar, arrival) => toUpdateSoFar.updated(arrival.uniqueId, arrival)
          }.values.toSet
          Option(diff.copy(toUpdate = newToUpdate))
      }
    }

    def mergeAllSources(baseArrivals: Map[Int, Arrival], forecastArrivals: Map[Int, Arrival], liveArrivals: Map[Int, Arrival]): Option[ArrivalsDiff] = {
      val existingArrivalsKeys = merged.keys.toSet
      val newArrivalsKeys = baseArrivals.keys.toSet ++ liveArrivals.keys.toSet
      val removedArrivalsKeys = existingArrivalsKeys -- newArrivalsKeys
      val arrivalsWithUpdates = mergeArrivalSourcesAndGetUpdates(baseArrivals, forecastArrivals, liveArrivals)

      val countBeforePurge = merged.size
      merged = Crunch.purgeExpired(merged, (a: Arrival) => a.PcpTime.getOrElse(0L), now, expireAfterMillis)
      val countAfterPurge = merged.size
      val numPurged = countBeforePurge - countAfterPurge
      if (numPurged > 0) log.info(s"Purged $numPurged expired arrivals during merge. $countAfterPurge arrivals remaining")

      if (arrivalsWithUpdates.nonEmpty || removedArrivalsKeys.nonEmpty)
        Option(ArrivalsDiff(arrivalsWithUpdates, removedArrivalsKeys))
      else None
    }

    setHandler(outArrivalsDiff, new OutHandler {
      override def onPull(): Unit = {
        val start = SDate.now()
        pushIfAvailable(toPush, outArrivalsDiff)

        if (!hasBeenPulled(inLiveArrivals)) pull(inLiveArrivals)
        if (!hasBeenPulled(inForecastArrivals)) pull(inForecastArrivals)
        if (!hasBeenPulled(inBaseArrivals)) pull(inBaseArrivals)
        log.info(s"outArrivalsDiff Took ${SDate.now().millisSinceEpoch - start.millisSinceEpoch}ms")
      }
    })

    def filterAndSetPcp(arrivals: Seq[Arrival]): Map[Int, Arrival] = {
      arrivals
        .filterNot {
          case f if !isFlightRelevant(f) =>
            log.debug(s"Filtering out irrelevant arrival: ${f.IATA}, ${SDate(f.Scheduled).toISOString()}, ${f.Origin}")
            true
          case _ => false
        }
        .map(arrival => {
          val id = arrival.uniqueId
          val withPcpTime = arrival.copy(PcpTime = Some(pcpArrivalTime(arrival).millisSinceEpoch))
          (id, withPcpTime)
        })
        .toMap
    }

    def isFlightRelevant(flight: Arrival): Boolean =
      validPortTerminals.contains(flight.Terminal) && !domesticPorts.contains(flight.Origin)

    def pushIfAvailable(arrivalsToPush: Option[ArrivalsDiff], outlet: Outlet[ArrivalsDiff]): Unit = {
      if (isAvailable(outlet)) {
        arrivalsToPush match {
          case None =>
            log.info(s"No updated arrivals to push")
          case Some(diff) =>
            log.info(s"Pushing ${diff.toUpdate.size} updates & ${diff.toRemove.size} removals")
            push(outArrivalsDiff, diff)
            toPush = None
        }
      } else log.info(s"outMerged not available to push")
    }

    def maybeUpdatedForecastArrival(forecastArrival: Arrival): Option[Arrival] = {
      liveArrivals.get(forecastArrival.uniqueId) match {
        case Some(_) =>
          log.info(s"Ignoring forecast arrival. Live arrival supersedes it")
          None
        case None =>
          baseArrivals.get(forecastArrival.uniqueId) match {
            case None =>
              log.info(s"Ignoring forecast arrival. No base arrival found")
              None
            case Some(baseArrival) =>
              val actPax = bestPax(baseArrival, forecastArrival)
              val mergedArrival = baseArrival.copy(ActPax = actPax, TranPax = forecastArrival.TranPax, Status = forecastArrival.Status, FeedSources = baseArrival.FeedSources ++ forecastArrival.FeedSources)
              Some(mergedArrival)
          }
      }
    }

    def standariseLiveArrival(liveArrival: Arrival): Arrival = {
      val baseArrival = baseArrivals.getOrElse(liveArrival.uniqueId, liveArrival)
      val mergedSoFarArrival = merged.getOrElse(liveArrival.uniqueId, liveArrival)
      liveArrival.copy(
        rawIATA = baseArrival.rawIATA,
        rawICAO = baseArrival.rawICAO,
        ActPax = bestPax(mergedSoFarArrival, liveArrival),
        TranPax = bestTransPax(mergedSoFarArrival, liveArrival),
        FeedSources = liveArrival.FeedSources ++ mergedSoFarArrival.FeedSources
      )
    }

    def mergeArrivalSourcesAndGetUpdates(base: Map[Int, Arrival], forecast: Map[Int, Arrival], live: Map[Int, Arrival]): Set[Arrival] = baseArrivals
      .foldLeft(Map[Int, Arrival]()) {
        case (updatedArrivalsSoFar, (key, baseArrival)) =>
          val withForecast = merged.get(key) match {
            case Some(mergedArrival) => mergedArrival.copy(FeedSources = mergedArrival.FeedSources + AclFeedSource)
            case None => forecast.get(key) match {
              case None => baseArrival
              case Some(forecastArrival) =>
                baseArrival.copy(
                  ActPax = bestPax(baseArrival, forecastArrival),
                  TranPax = bestTransPax(forecastArrival, baseArrival),
                  FeedSources = forecastArrival.FeedSources ++ baseArrival.FeedSources
                )
            }
          }
          val withLive = liveArrivals.get(key) match {
            case None => withForecast
            case Some(liveArrival) =>
              withForecast.copy(
                rawIATA = withForecast.rawIATA,
                rawICAO = withForecast.rawICAO,
                ActPax = bestPax(withForecast, liveArrival),
                TranPax = bestTransPax(liveArrival, withForecast),
                FeedSources = liveArrival.FeedSources ++ withForecast.FeedSources
              )
          }
          if (!merged.contains(key) || !merged(key).equals(withLive)) {
            merged = merged.updated(key, withLive)
            updatedArrivalsSoFar.updated(key, withLive)
          }
          else updatedArrivalsSoFar
      }.values.toSet

    def maybeUpdateFromArrival(arrival: Arrival, feedSource: FeedSource): Option[Arrival] = feedSource match {
      case LiveFeedSource => maybeUpdateFromLiveArrival(arrival)
      case ForecastFeedSource => maybeUpdateFromForecastArrival(arrival)
    }

    def maybeUpdateFromLiveArrival(liveArrival: Arrival): Option[Arrival] = {
      val baseArrival = merged.getOrElse(liveArrival.uniqueId, liveArrival)

      val updated = liveArrival.copy(
        rawIATA = baseArrival.rawIATA,
        rawICAO = baseArrival.rawICAO,
        ActPax = bestPax(baseArrival, liveArrival),
        TranPax = bestTransPax(baseArrival, liveArrival),
        FeedSources = liveArrival.FeedSources ++ baseArrival.FeedSources
      )

      if (merged.contains(updated.uniqueId) && merged(updated.uniqueId).equals(updated)) None
      else Option(updated)
    }

    def maybeUpdateFromForecastArrival(forecastArrival: Arrival): Option[Arrival] = {
      merged.get(forecastArrival.uniqueId) match {
        case None => None
        case Some(mergedArrival) =>
          val updated = mergedArrival.copy(
            ActPax = bestPax(mergedArrival, forecastArrival),
            TranPax = bestTransPax(mergedArrival, forecastArrival),
            FeedSources = forecastArrival.FeedSources ++ mergedArrival.FeedSources
          )
          if (merged.contains(updated.uniqueId) && merged(updated.uniqueId).equals(updated)) None
          else Option(updated)
      }
    }

    def arrivalsDiff(oldMerged: Map[Int, Arrival], newMerged: Map[Int, Arrival]): Option[ArrivalsDiff] = {
      val updates: Option[Set[Arrival]] = newMerged.values.toSet -- oldMerged.values.toSet match {
        case updatedArrivals if updatedArrivals.isEmpty =>
          log.info(s"No updated arrivals")
          None
        case updatedArrivals =>
          log.info(s"${updatedArrivals.size} updated arrivals")
          Option(updatedArrivals)
      }
      val removals: Option[Set[Int]] = oldMerged.keys.toSet -- newMerged.keys.toSet match {
        case removedArrivals if removedArrivals.isEmpty => None
        case removedArrivals => Option(removedArrivals)
      }

      val optionalArrivalsDiff = (updates, removals) match {
        case (None, None) => None
        case (u, r) => Option(ArrivalsDiff(u.getOrElse(Set()), r.getOrElse(Set())))
      }

      optionalArrivalsDiff
    }

    def updatesFromIncoming(incomingArrivals: Seq[Arrival], source: FeedSource): Seq[Arrival] = incomingArrivals
      .map(arrival => maybeUpdateFromArrival(arrival, source))
      .collect { case Some(updatedArrival) => updatedArrival }
  }

  def bestTransPax(existingArrival: Arrival, newArrival: Arrival): Option[Int] = newArrival
    .ActPax
    .filter(_ > 0)
    .flatMap(_ => newArrival.TranPax)
    .orElse(existingArrival.TranPax)

  def bestPax(existingArrival: Arrival, newArrival: Arrival): Option[Int] = newArrival
    .ActPax
    .filter(_ > 0)
    .orElse(existingArrival.ActPax)

  val domesticPorts = Seq(
    "ABB", "ABZ", "ACI", "ADV", "ADX", "AYH",
    "BBP", "BBS", "BEB", "BEQ", "BEX", "BFS", "BHD", "BHX", "BLK", "BLY", "BOH", "BOL", "BQH", "BRF", "BRR", "BRS", "BSH", "BUT", "BWF", "BWY", "BYT", "BZZ",
    "CAL", "CAX", "CBG", "CEG", "CFN", "CHE", "CLB", "COL", "CRN", "CSA", "CVT", "CWL",
    "DCS", "DGX", "DND", "DOC", "DSA", "DUB",
    "EDI", "EMA", "ENK", "EOI", "ESH", "EWY", "EXT",
    "FAB", "FEA", "FFD", "FIE", "FKH", "FLH", "FOA", "FSS", "FWM", "FZO",
    "GCI", "GLA", "GLO", "GQJ", "GSY", "GWY", "GXH",
    "HAW", "HEN", "HLY", "HOY", "HRT", "HTF", "HUY", "HYC",
    "IIA", "ILY", "INQ", "INV", "IOM", "IOR", "IPW", "ISC",
    "JER",
    "KIR", "KKY", "KNF", "KOI", "KRH", "KYN",
    "LBA", "LCY", "LDY", "LEQ", "LGW", "LHR", "LKZ", "LMO", "LON", "LPH", "LPL", "LSI", "LTN", "LTR", "LWK", "LYE", "LYM", "LYX",
    "MAN", "MHZ", "MME", "MSE",
    "NCL", "NDY", "NHT", "NNR", "NOC", "NQT", "NQY", "NRL", "NWI",
    "OBN", "ODH", "OHP", "OKH", "ORK", "ORM", "OUK", "OXF",
    "PIK", "PLH", "PME", "PPW", "PSL", "PSV", "PZE",
    "QCY", "QFO", "QLA", "QUG",
    "RAY", "RCS",
    "SCS", "SDZ", "SEN", "SKL", "SNN", "SOU", "SOY", "SQZ", "STN", "SWI", "SWS", "SXL", "SYY", "SZD",
    "TRE", "TSO", "TTK",
    "UHF", "ULL", "UNT", "UPV",
    "WAT", "WEM", "WEX", "WFD", "WHS", "WIC", "WOB", "WRY", "WTN", "WXF",
    "YEO"
  )
}
