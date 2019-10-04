package services.graphstages

import akka.stream._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import drt.shared.FlightsApi.Flights
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import server.feeds.{ArrivalsFeedFailure, ArrivalsFeedResponse, ArrivalsFeedSuccess}
import services.{SDate, graphstages}

import scala.collection.mutable
import scala.collection.immutable.SortedMap


sealed trait ArrivalsSourceType

case object LiveArrivals extends ArrivalsSourceType

case object LiveBaseArrivals extends ArrivalsSourceType

case object ForecastArrivals extends ArrivalsSourceType

case object BaseArrivals extends ArrivalsSourceType

class ArrivalsGraphStage(name: String = "",
                         initialForecastBaseArrivals: mutable.SortedMap[UniqueArrival, Arrival],
                         initialForecastArrivals: mutable.SortedMap[UniqueArrival, Arrival],
                         initialLiveBaseArrivals: mutable.SortedMap[UniqueArrival, Arrival],
                         initialLiveArrivals: mutable.SortedMap[UniqueArrival, Arrival],
                         initialMergedArrivals: mutable.SortedMap[UniqueArrival, Arrival],
                         pcpArrivalTime: Arrival => MilliDate,
                         validPortTerminals: Set[String],
                         expireAfterMillis: Long,
                         now: () => SDateLike)
  extends GraphStage[FanInShape4[ArrivalsFeedResponse, ArrivalsFeedResponse, ArrivalsFeedResponse, ArrivalsFeedResponse, ArrivalsDiff]] {

  val inForecastBaseArrivals: Inlet[ArrivalsFeedResponse] = Inlet[ArrivalsFeedResponse]("inFlightsForecastBase")
  val inForecastArrivals: Inlet[ArrivalsFeedResponse] = Inlet[ArrivalsFeedResponse]("inFlightsForecast")
  val inLiveBaseArrivals: Inlet[ArrivalsFeedResponse] = Inlet[ArrivalsFeedResponse]("inFlightsLiveBase")
  val inLiveArrivals: Inlet[ArrivalsFeedResponse] = Inlet[ArrivalsFeedResponse]("inFlightsLive")
  val outArrivalsDiff: Outlet[ArrivalsDiff] = Outlet[ArrivalsDiff]("outArrivalsDiff")
  override val shape = new FanInShape4(inForecastBaseArrivals, inForecastArrivals, inLiveBaseArrivals, inLiveArrivals, outArrivalsDiff)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    val forecastBaseArrivals: mutable.SortedMap[UniqueArrival, Arrival] = mutable.SortedMap()
    val forecastArrivals: mutable.SortedMap[UniqueArrival, Arrival] = mutable.SortedMap()
    val liveBaseArrivals: mutable.SortedMap[UniqueArrival, Arrival] = mutable.SortedMap()
    val liveArrivals: mutable.SortedMap[UniqueArrival, Arrival] = mutable.SortedMap()
    val merged: mutable.SortedMap[UniqueArrival, Arrival] = mutable.SortedMap()
    var toPush: Option[ArrivalsDiff] = None

    val log: Logger = LoggerFactory.getLogger(s"$getClass-$name")

    override def preStart(): Unit = {
      log.info(s"Received ${initialForecastBaseArrivals.size} initial base arrivals")
      forecastBaseArrivals ++= filterAndSetPcp(SortedMap[UniqueArrival, Arrival]() ++ initialForecastBaseArrivals)
      log.info(s"Received ${initialForecastArrivals.size} initial forecast arrivals")
      prepInitialArrivals(initialForecastArrivals, forecastArrivals)

      log.info(s"Received ${initialLiveBaseArrivals.size} initial live base arrivals")
      prepInitialArrivals(initialLiveBaseArrivals, liveBaseArrivals)
      log.info(s"Received ${initialLiveArrivals.size} initial live arrivals")
      prepInitialArrivals(initialLiveArrivals, liveArrivals)

      initialMergedArrivals.map { case (_, a) => merged += (UniqueArrival(a) -> a) }
      super.preStart()
    }

    def prepInitialArrivals(initialArrivals: mutable.SortedMap[UniqueArrival, Arrival], arrivals: mutable.SortedMap[UniqueArrival, Arrival]): Unit = {
      arrivals ++= filterAndSetPcp(SortedMap[UniqueArrival, Arrival]() ++ initialArrivals)
      Crunch.purgeExpired(arrivals, UniqueArrival.atTime, now, expireAfterMillis.toInt)
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
        val start = SDate.now()
        pushIfAvailable(toPush, outArrivalsDiff)

        List(inLiveBaseArrivals, inLiveArrivals, inForecastArrivals, inForecastBaseArrivals).foreach(inlet => if (!hasBeenPulled(inlet)) {
          log.info(s"Pulling Inlet: ${inlet.toString()}")
          pull(inlet)
        })
        log.info(s"outArrivalsDiff Took ${SDate.now().millisSinceEpoch - start.millisSinceEpoch}ms")
      }
    })

    def onPushArrivals(arrivalsInlet: Inlet[ArrivalsFeedResponse], sourceType: ArrivalsSourceType): Unit = {
      val start = SDate.now()
      log.info(s"$arrivalsInlet onPush() grabbing flights")

      grab(arrivalsInlet) match {
        case ArrivalsFeedSuccess(Flights(flights), connectedAt) =>
          log.info(s"Grabbed ${flights.length} arrivals from $arrivalsInlet of $sourceType at ${connectedAt.toISOString()}")
          if (flights.nonEmpty || sourceType == BaseArrivals) handleIncomingArrivals(sourceType, flights)
          else log.info(s"No arrivals to handle")
        case ArrivalsFeedFailure(message, failedAt) =>
          log.warn(s"$arrivalsInlet failed at ${failedAt.toISOString()}: $message")
      }

      if (!hasBeenPulled(arrivalsInlet)) pull(arrivalsInlet)
      log.info(s"$arrivalsInlet Took ${SDate.now().millisSinceEpoch - start.millisSinceEpoch}ms")
    }

    def handleIncomingArrivals(sourceType: ArrivalsSourceType, incomingArrivals: Seq[Arrival]): Unit = {
      val filteredArrivals = filterAndSetPcp(SortedMap[UniqueArrival, Arrival]() ++ incomingArrivals.map(a => (UniqueArrival(a), a)))
      log.info(s"${filteredArrivals.size} arrivals after filtering")
      sourceType match {
        case LiveArrivals =>
          updateArrivalsSource(liveArrivals, filteredArrivals)
          toPush = mergeUpdatesFromKeys(liveArrivals.keys)
        case LiveBaseArrivals =>
          updateArrivalsSource(liveBaseArrivals, filteredArrivals)
          println(s"CIRIUM: ${liveBaseArrivals.keys}")
          toPush = mergeUpdatesFromKeys(liveBaseArrivals.keys)
        case ForecastArrivals =>
          updateArrivalsSource(forecastArrivals, filteredArrivals)
          toPush = mergeUpdatesFromKeys(forecastArrivals.keys)
        case BaseArrivals =>
          forecastBaseArrivals.clear
          forecastBaseArrivals ++= filteredArrivals
          toPush = mergeUpdatesFromAllSources()
      }
      pushIfAvailable(toPush, outArrivalsDiff)
    }

    def updateArrivalsSource(existingArrivals: mutable.SortedMap[UniqueArrival, Arrival], newArrivals: SortedMap[UniqueArrival, Arrival]): Unit = newArrivals.foreach {
      case (key, newArrival) =>
        if (!existingArrivals.contains(key) || !existingArrivals(key).equals(newArrival)) existingArrivals += (key -> newArrival)
    }

    def mergeUpdatesFromAllSources(): Option[ArrivalsDiff] = maybeDiffFromAllSources().map(diff => {
      merged --= diff.toRemove.map(UniqueArrival(_))
      merged ++= diff.toUpdate
      diff
    })

    def mergeUpdatesFromKeys(uniqueArrivals: Iterable[UniqueArrival]): Option[ArrivalsDiff] = {
      val updatedArrivals = getUpdatesFromNonBaseArrivals(uniqueArrivals)

      updatedArrivals.foreach {
        case (ak, updatedArrival) => merged += (ak -> updatedArrival)
      }

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

      val removedArrivals = (existingArrivalsKeys -- newArrivalsKeys).map(merged(_))

      arrivalsWithUpdates.foreach { case (ak, mergedArrival) =>
        merged += (ak -> mergedArrival)
      }

      if (arrivalsWithUpdates.nonEmpty || removedArrivals.nonEmpty) {
        val updates = SortedMap[UniqueArrival, Arrival]() ++ arrivalsWithUpdates
        Option(ArrivalsDiff(updates, removedArrivals))
      }
      else None
    }

    def purgeExpired(): Unit = {
      Crunch.purgeExpired(liveArrivals, UniqueArrival.atTime, now, expireAfterMillis.toInt)
      Crunch.purgeExpired(forecastArrivals, UniqueArrival.atTime, now, expireAfterMillis.toInt)
      Crunch.purgeExpired(forecastBaseArrivals, UniqueArrival.atTime, now, expireAfterMillis.toInt)
      Crunch.purgeExpired(merged, UniqueArrival.atTime, now, expireAfterMillis.toInt)
    }

    def filterAndSetPcp(arrivals: SortedMap[UniqueArrival, Arrival]): SortedMap[UniqueArrival, Arrival] = {
      val toRemove = arrivals.filter {
        case (_, f) if !isFlightRelevant(f) =>
          log.debug(s"Filtering out irrelevant arrival: ${f.IATA}, ${SDate(f.Scheduled).toISOString()}, ${f.Origin}")
          true
        case _ => false
      }.keys

      val minusRemovals = arrivals -- toRemove

      minusRemovals.mapValues { arrival =>
        arrival.copy(PcpTime = Some(pcpArrivalTime(arrival).millisSinceEpoch))
      }
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
      } else log.debug(s"outMerged not available to push")
    }

    def getUpdatesFromBaseArrivals: mutable.SortedMap[UniqueArrival, Arrival] = {
      val arrivals = mutable.SortedMap[UniqueArrival, Arrival]()
      forecastBaseArrivals.foreach {
        case (key, baseArrival) =>
          val mergedArrival = mergeBaseArrival(baseArrival)
          if (arrivalHasUpdates(merged.get(key), mergedArrival)) arrivals += (key -> mergedArrival)
      }
      arrivals
    }

    def getUpdatesFromNonBaseArrivals(keys: Iterable[UniqueArrival]): SortedMap[UniqueArrival, Arrival] = SortedMap[UniqueArrival, Arrival]() ++ keys
      .foldLeft(Map[UniqueArrival, Arrival]()) {
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
      val key = UniqueArrival(baseArrival)
      val bestArrival = liveArrivals.getOrElse(key, baseArrival)
      mergeBestFieldsFromSources(baseArrival, bestArrival)
    }

    def mergeArrival(key: UniqueArrival): Option[Arrival] = {
      val maybeBestArrival: Option[Arrival] = (liveArrivals.get(key), liveBaseArrivals.get(key)) match {
        case (Some(liveArrival), None) => Option(liveArrival)
        case (Some(liveArrival), Some(baseLiveArrival)) => Option(LiveArrivalsUtil.mergePortFeedWithBase(liveArrival, baseLiveArrival))
        case (None, Some(baseLiveArrival)) if forecastBaseArrivals.contains(key) =>
          log.info(s"Matched CIRIUM ${key} in ACL ${LiveArrivalsUtil.printArrival(baseLiveArrival)}")

          Option(baseLiveArrival)
        case _ => forecastBaseArrivals.get(key)
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
        rawIATA = baseArrival.rawIATA,
        rawICAO = baseArrival.rawICAO,
        ActPax = pax,
        TranPax = transPax,
        Status = bestStatus(key),
        FeedSources = feedSources(key)
      )
    }

    def bestPaxNos(key: UniqueArrival): (Option[Int], Option[Int]) = (liveArrivals.get(key), forecastArrivals.get(key), forecastBaseArrivals.get(key)) match {
      case (Some(liveArrival), _, _) if liveArrival.ActPax.exists(_ > 0) => (liveArrival.ActPax, liveArrival.TranPax)
      case (_, Some(fcstArrival), _) if fcstArrival.ActPax.exists(_ > 0) => (fcstArrival.ActPax, fcstArrival.TranPax)
      case (_, _, Some(baseArrival)) if baseArrival.ActPax.exists(_ > 0) => (baseArrival.ActPax, baseArrival.TranPax)
      case _ => (None, None)
    }

    def bestStatus(key: UniqueArrival): String =
      (liveArrivals.get(key), liveBaseArrivals.get(key), forecastArrivals.get(key), forecastBaseArrivals.get(key)) match {
        case (Some(live), _, _, _) => live.Status
        case (_, Some(liveBase), _, _) => liveBase.Status
        case (_, _, Some(forecast), _) => forecast.Status
        case (_, _, _, Some(forecastBase)) => forecastBase.Status
        case _ => "Unknown"
      }

    def feedSources(uniqueArrival: UniqueArrival): Set[FeedSource] = {
      List(
        liveArrivals.get(uniqueArrival).map(_ => LiveFeedSource),
        forecastArrivals.get(uniqueArrival).map(_ => ForecastFeedSource),
        forecastBaseArrivals.get(uniqueArrival).map(_ => AclFeedSource),
        liveBaseArrivals.get(uniqueArrival).map(_ => LiveBaseFeedSource)
      ).flatten.toSet
    }
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
