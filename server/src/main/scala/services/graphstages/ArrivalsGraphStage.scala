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
          liveArrivals = updateArrivalsSource(liveArrivals, filteredArrivals)
          toPush = mergeUpdatesFromKeys(liveArrivals.keys)
        case ForecastArrivals =>
          forecastArrivals = updateArrivalsSource(forecastArrivals, filteredArrivals)
          toPush = mergeUpdatesFromKeys(forecastArrivals.keys)
        case BaseArrivals =>
          baseArrivals = filteredArrivals
          toPush = mergeUpdatesFromAllSources()
      }
      pushIfAvailable(toPush, outArrivalsDiff)
    }

    def updateArrivalsSource(existingArrivals: Map[Int, Arrival], newArrivals: Map[Int, Arrival]): Map[Int, Arrival] = newArrivals.foldLeft(existingArrivals) {
      case (arrivalsSoFar, (key, newArrival)) =>
        if (!arrivalsSoFar.contains(key) || !arrivalsSoFar(key).equals(newArrival)) arrivalsSoFar.updated(key, newArrival)
        else arrivalsSoFar
    }

    def mergeUpdatesFromAllSources(): Option[ArrivalsDiff] = maybeDiffFromAllSources().map(diff => {
      merged = diff.toUpdate.foldLeft(merged -- diff.toRemove) {
        case (mergedSoFar, updatedArrival) => mergedSoFar.updated(updatedArrival.uniqueId, updatedArrival)
      }
      diff
    })

    def mergeUpdatesFromKeys(arrivalKeys: Iterable[Int]): Option[ArrivalsDiff] = {
      val updatedArrivals = getUpdatesFromNonBaseArrivals(arrivalKeys)

      merged = updatedArrivals.foldLeft(merged) {
        case (mergedSoFar, updatedArrival) => mergedSoFar.updated(updatedArrival.uniqueId, updatedArrival)
      }

      updateDiffToPush(updatedArrivals.toSeq)
    }

    def updateDiffToPush(updatedLiveArrivals: Seq[Arrival]): Option[ArrivalsDiff] = {
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

    def maybeDiffFromAllSources(): Option[ArrivalsDiff] = {
      val existingArrivalsKeys = merged.keys.toSet
      val newArrivalsKeys = baseArrivals.keys.toSet ++ liveArrivals.keys.toSet
      val arrivalsWithUpdates = getUpdatesFromBaseArrivals(baseArrivals)
      arrivalsWithUpdates.foreach(mergedArrival => merged = merged.updated(mergedArrival.uniqueId, mergedArrival))

      purgeExpiredFromMerged()

      val removedArrivalsKeys = existingArrivalsKeys -- newArrivalsKeys

      if (arrivalsWithUpdates.nonEmpty || removedArrivalsKeys.nonEmpty)
        Option(ArrivalsDiff(arrivalsWithUpdates, removedArrivalsKeys))
      else None
    }

    def purgeExpiredFromMerged(): Unit = {
      val countBeforePurge = merged.size
      merged = Crunch.purgeExpired(merged, (a: Arrival) => a.PcpTime.getOrElse(0L), now, expireAfterMillis)
      val countAfterPurge = merged.size

      val numPurged = countBeforePurge - countAfterPurge
      if (numPurged > 0) log.info(s"Purged $numPurged expired arrivals during merge. $countAfterPurge arrivals remaining")
    }

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

    def getUpdatesFromBaseArrivals(base: Map[Int, Arrival]): Set[Arrival] = base
      .foldLeft(Map[Int, Arrival]()) {
        case (updatedArrivalsSoFar, (key, baseArrival)) =>
          val mergedArrival = mergeBaseArrival(baseArrival)
          if (arrivalHasUpdates(merged.get(key), mergedArrival)) updatedArrivalsSoFar.updated(key, mergedArrival) else updatedArrivalsSoFar
      }.values.toSet

    def getUpdatesFromNonBaseArrivals(keys: Iterable[Int]): Set[Arrival] = keys
      .foldLeft(Map[Int, Arrival]()) {
        case (updatedArrivalsSoFar, key) =>
          mergeArrival(key) match {
            case Some(mergedArrival) =>
              if (arrivalHasUpdates(merged.get(key), mergedArrival)) updatedArrivalsSoFar.updated(key, mergedArrival) else updatedArrivalsSoFar
            case None => updatedArrivalsSoFar
          }
      }.values.toSet

    def arrivalHasUpdates(maybeExistingArrival: Option[Arrival], updatedArrival: Arrival): Boolean = {
      maybeExistingArrival.isEmpty || !maybeExistingArrival.get.equals(updatedArrival)
    }

    def mergeBaseArrival(baseArrival: Arrival): Arrival = {
      val key = baseArrival.uniqueId
      val bestArrival = liveArrivals.getOrElse(key, baseArrival)
      updateArrival(baseArrival, bestArrival)
    }

    def mergeArrival(key: Int): Option[Arrival] = {
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
      val key = baseArrival.uniqueId
      bestArrival.copy(
        rawIATA = baseArrival.rawIATA,
        rawICAO = baseArrival.rawICAO,
        ActPax = bestPax(key),
        TranPax = bestTransPax(key),
        Status = bestStatus(key),
        FeedSources = feedSources(key)
      )
    }

    def bestPax(uniqueId: Int): Option[Int] = liveArrivals.get(uniqueId) match {
      case Some(liveArrival) if liveArrival.ActPax.exists(_ > 0) => liveArrival.ActPax
      case _ => forecastArrivals.get(uniqueId) match {
        case Some(forecastArrival) if forecastArrival.ActPax.exists(_ > 0) => forecastArrival.ActPax
        case _ => baseArrivals.get(uniqueId) match {
          case Some(baseArrival) if baseArrival.ActPax.exists(_ > 0) => baseArrival.ActPax
          case _ => None
        }
      }
    }

    def bestTransPax(uniqueId: Int): Option[Int] = liveArrivals.get(uniqueId) match {
      case Some(liveArrival) if liveArrival.ActPax.exists(_ > 0) => liveArrival.TranPax
      case _ => forecastArrivals.get(uniqueId) match {
        case Some(forecastArrival) if forecastArrival.ActPax.exists(_ > 0) => forecastArrival.TranPax
        case _ => baseArrivals.get(uniqueId) match {
          case Some(baseArrival) if baseArrival.ActPax.exists(_ > 0) => baseArrival.TranPax
          case _ => None
        }
      }
    }

    def bestStatus(uniqueId: Int): String = liveArrivals.get(uniqueId) match {
      case Some(liveArrival) => liveArrival.Status
      case _ => forecastArrivals.get(uniqueId) match {
        case Some(forecastArrival) => forecastArrival.Status
        case _ => baseArrivals.get(uniqueId) match {
          case Some(baseArrival) => baseArrival.Status
          case _ => "Unknown"
        }
      }
    }

    def feedSources(uniqueId: Int): Set[FeedSource] = List(
      liveArrivals.get(uniqueId).map(_ => LiveFeedSource),
      forecastArrivals.get(uniqueId).map(_ => ForecastFeedSource),
      baseArrivals.get(uniqueId).map(_ => AclFeedSource)
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
