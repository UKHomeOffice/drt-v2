package services.graphstages

import akka.stream._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import drt.shared.FlightsApi.Flights
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import drt.server.feeds.{ArrivalsFeedFailure, ArrivalsFeedResponse, ArrivalsFeedSuccess}
import services.SDate

import scala.collection.immutable.{Map, SortedMap}
import scala.language.postfixOps


sealed trait ArrivalsSourceType

case object LiveArrivals extends ArrivalsSourceType

case object ForecastArrivals extends ArrivalsSourceType

case object BaseArrivals extends ArrivalsSourceType

class ArrivalsGraphStage(name: String = "",
                         initialBaseArrivals: Set[Arrival],
                         initialForecastArrivals: Set[Arrival],
                         initialLiveArrivals: Set[Arrival],
                         initialMergedArrivals: SortedMap[ArrivalKey, Arrival],
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
    var baseArrivals: SortedMap[ArrivalKey, Arrival] = SortedMap()
    var forecastArrivals: SortedMap[ArrivalKey, Arrival] = SortedMap()
    var liveArrivals: SortedMap[ArrivalKey, Arrival] = SortedMap()
    var merged: SortedMap[ArrivalKey, Arrival] = SortedMap()
    var toPush: Option[ArrivalsDiff] = None

    val log: Logger = LoggerFactory.getLogger(s"$getClass-$name")

    override def preStart(): Unit = {
      log.info(s"Received ${initialBaseArrivals.size} initial base arrivals")
      baseArrivals = filterAndSetPcp(initialBaseArrivals.toSeq)
      log.info(s"Received ${initialForecastArrivals.size} initial forecast arrivals")
      forecastArrivals = prepInitialArrivals(initialForecastArrivals)
      log.info(s"Received ${initialLiveArrivals.size} initial live arrivals")
      liveArrivals = prepInitialArrivals(initialLiveArrivals)
      merged = SortedMap[ArrivalKey, Arrival]() ++ initialMergedArrivals.map { case (_, a) => (ArrivalKey(a), a) }
      super.preStart()
    }

    def prepInitialArrivals(arrivals: Set[Arrival]): SortedMap[ArrivalKey, Arrival] = {
      val arrivalsWithPcp = filterAndSetPcp(arrivals.toSeq)
      Crunch.purgeExpired(arrivalsWithPcp, now, expireAfterMillis.toInt)
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

    setHandler(outArrivalsDiff, new OutHandler {
      override def onPull(): Unit = {
        val start = SDate.now()
        pushIfAvailable(toPush, outArrivalsDiff)

        List(inLiveArrivals, inForecastArrivals, inBaseArrivals).foreach(inlet => if (!hasBeenPulled(inlet)) pull(inlet))
        log.info(s"outArrivalsDiff Took ${SDate.now().millisSinceEpoch - start.millisSinceEpoch}ms")
      }
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
      log.info(s"${filteredArrivals.size} arrivals after filtering")
      sourceType match {
        case LiveArrivals =>
          liveArrivals = liveArrivals ++ filteredArrivals
          toPush = mergeUpdatesFromKeys(liveArrivals.keys)
        case ForecastArrivals =>
          forecastArrivals = forecastArrivals ++ filteredArrivals
          toPush = mergeUpdatesFromKeys(forecastArrivals.keys)
        case BaseArrivals =>
          baseArrivals = filteredArrivals
          toPush = mergeUpdatesFromAllSources()
      }
      pushIfAvailable(toPush, outArrivalsDiff)
    }

    def mergeUpdatesFromAllSources(): Option[ArrivalsDiff] = maybeDiffFromAllSources().map { diff =>
      val minusRemovals = merged -- diff.toRemove.map(ArrivalKey(_))
      merged = minusRemovals ++ diff.toUpdate
      diff
    }

    def mergeUpdatesFromKeys(arrivalKeys: Iterable[ArrivalKey]): Option[ArrivalsDiff] = {
      val updatedArrivals = getUpdatesFromNonBaseArrivals(arrivalKeys)

      merged = merged ++ updatedArrivals

      updateDiffToPush(updatedArrivals)
    }

    def updateDiffToPush(updatedLiveArrivals: SortedMap[ArrivalKey, Arrival]): Option[ArrivalsDiff] = {
      toPush match {
        case None => Option(ArrivalsDiff(updatedLiveArrivals, Set()))
        case Some(diff) =>
          val newToUpdate = diff.toUpdate ++ updatedLiveArrivals
          Option(diff.copy(toUpdate = newToUpdate))
      }
    }

    def maybeDiffFromAllSources(): Option[ArrivalsDiff] = {
      purgeExpired()

      val existingArrivalsKeys = merged.keys.toSet
      val newArrivalsKeys = baseArrivals.keys.toSet ++ liveArrivals.keys.toSet
      val arrivalsWithUpdates = getUpdatesFromBaseArrivals(baseArrivals)

      val removedArrivals = (existingArrivalsKeys -- newArrivalsKeys).map(merged(_))

      arrivalsWithUpdates.foreach { case (ak, mergedArrival) =>
        merged = merged.updated(ak, mergedArrival)
      }

      if (arrivalsWithUpdates.nonEmpty || removedArrivals.nonEmpty)
        Option(ArrivalsDiff(arrivalsWithUpdates, removedArrivals))
      else None
    }

    def purgeExpired(): Unit = {
      liveArrivals = Crunch.purgeExpired(liveArrivals, now, expireAfterMillis.toInt)
      forecastArrivals = Crunch.purgeExpired(forecastArrivals, now, expireAfterMillis.toInt)
      baseArrivals = Crunch.purgeExpired(baseArrivals, now, expireAfterMillis.toInt)
      merged = Crunch.purgeExpired(merged, now, expireAfterMillis.toInt)
    }

    def filterAndSetPcp(arrivals: Seq[Arrival]): SortedMap[ArrivalKey, Arrival] = {
      SortedMap[ArrivalKey, Arrival]() ++ arrivals
        .filterNot {
          case f if !isFlightRelevant(f) =>
            log.debug(s"Filtering out irrelevant arrival: ${f.IATA}, ${SDate(f.Scheduled).toISOString()}, ${f.Origin}")
            true
          case _ => false
        }
        .map(arrival => {
          val withPcpTime = arrival.copy(PcpTime = Some(pcpArrivalTime(arrival).millisSinceEpoch))
          (ArrivalKey(arrival), withPcpTime)
        })
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

    def getUpdatesFromBaseArrivals(base: SortedMap[ArrivalKey, Arrival]): SortedMap[ArrivalKey, Arrival] = SortedMap[ArrivalKey, Arrival]() ++ base
      .foldLeft(List[(ArrivalKey, Arrival)]()) {
        case (updatedArrivalsSoFar, (key, baseArrival)) =>
          val mergedArrival = mergeBaseArrival(baseArrival)
          if (arrivalHasUpdates(merged.get(key), mergedArrival))
            (key, mergedArrival) :: updatedArrivalsSoFar
          else updatedArrivalsSoFar
      }

    def getUpdatesFromNonBaseArrivals(keys: Iterable[ArrivalKey]): SortedMap[ArrivalKey, Arrival] = SortedMap[ArrivalKey, Arrival]() ++ keys
      .foldLeft(Map[ArrivalKey, Arrival]()) {
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
      val key = ArrivalKey(baseArrival)
      val bestArrival = liveArrivals.getOrElse(key, baseArrival)
      updateArrival(baseArrival, bestArrival)
    }

    def mergeArrival(key: ArrivalKey): Option[Arrival] = {
      val maybeBestArrival = liveArrivals.get(key) match {
        case Some(liveArrival) => Option(liveArrival)
        case None => baseArrivals.get(key)
      }
      maybeBestArrival.map(bestArrival => {
        val arrivalForFlightCode = baseArrivals.getOrElse(key, bestArrival)
        updateArrival(arrivalForFlightCode, bestArrival)
      })
    }

    def updateArrival(baseArrival: Arrival, bestArrival: Arrival): Arrival = {
      val key = ArrivalKey(baseArrival)
      val (pax, transPax) = bestPaxNos(key)
      bestArrival.copy(
        rawIATA = baseArrival.rawIATA,
        rawICAO = baseArrival.rawICAO,
        ActPax = pax,
        TranPax = transPax,
        Status = bestStatus(key),
        FeedSources = feedSources(key)
      )
    }

    def bestPaxNos(arrivalKey: ArrivalKey): (Option[Int], Option[Int]) = (liveArrivals.get(arrivalKey), forecastArrivals.get(arrivalKey), baseArrivals.get(arrivalKey)) match {
      case (Some(liveArrival), _, _) if liveArrival.ActPax.exists(_ > 0) => (liveArrival.ActPax, liveArrival.TranPax)
      case (_, Some(fcstArrival), _) if fcstArrival.ActPax.exists(_ > 0) => (fcstArrival.ActPax, fcstArrival.TranPax)
      case (_, _, Some(baseArrival)) if baseArrival.ActPax.exists(_ > 0) => (baseArrival.ActPax, baseArrival.TranPax)
      case _ => (None, None)
    }

    def bestStatus(arrivalKey: ArrivalKey): String = liveArrivals.get(arrivalKey) match {
      case Some(liveArrival) => liveArrival.Status
      case _ => forecastArrivals.get(arrivalKey) match {
        case Some(forecastArrival) => forecastArrival.Status
        case _ => baseArrivals.get(arrivalKey) match {
          case Some(baseArrival) => baseArrival.Status
          case _ => "Unknown"
        }
      }
    }

    def feedSources(arrivalKey: ArrivalKey): Set[FeedSource] = List(
      liveArrivals.get(arrivalKey).map(_ => LiveFeedSource),
      forecastArrivals.get(arrivalKey).map(_ => ForecastFeedSource),
      baseArrivals.get(arrivalKey).map(_ => AclFeedSource)
    ).flatten.toSet
  }

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
