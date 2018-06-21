package services.graphstages

import akka.stream._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.Flights
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import services.SDate
import services.graphstages.Crunch.midnightThisMorning

import scala.collection.immutable.Map
import scala.language.postfixOps

class ArrivalsGraphStage(name: String = "",
                         initialBaseArrivals: Set[Arrival],
                         initialForecastArrivals: Set[Arrival],
                         initialLiveArrivals: Set[Arrival],
                         pcpArrivalTime: (Arrival) => MilliDate, crunchStartDateProvider: () => MillisSinceEpoch = midnightThisMorning _,
                         validPortTerminals: Set[String],
                         expireAfterMillis: Long,
                         now: () => SDateLike)
  extends GraphStage[FanInShape3[Option[Flights], Flights, Flights, ArrivalsDiff]] {

  val inBaseArrivals: Inlet[Option[Flights]] = Inlet[Option[Flights]]("inFlightsBase.in")
  val inForecastArrivals: Inlet[Flights] = Inlet[Flights]("inFlightsForecast.in")
  val inLiveArrivals: Inlet[Flights] = Inlet[Flights]("inFlightsLive.in")
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
      merged = mergeArrivals(baseArrivals, forecastArrivals, liveArrivals)
      super.preStart()
    }

    def prepInitialArrivals(arrivals: Set[Arrival]): Map[Int, Arrival] = {
      val arrivalsWithPcp = filterAndSetPcp(arrivals.toSeq)
      purgeExpired(arrivalsWithPcp)
    }

    setHandler(inBaseArrivals, new InHandler {
      override def onPush(): Unit = {
        val start = SDate.now()
        log.info(s"inBaseArrivals onPush() grabbing base flights")

        grab(inBaseArrivals) match {
          case None =>
            log.info(s"No arrivals from ACL. Nothing to do")

          case Some(grabbedArrivals) =>
            log.info(s"Grabbed ${grabbedArrivals.flights.length} base arrivals")
            baseArrivals = filterAndSetPcp(grabbedArrivals.flights)
            mergeAllSourcesAndPush(baseArrivals, forecastArrivals, liveArrivals)

            if (!hasBeenPulled(inBaseArrivals)) pull(inBaseArrivals)
            log.info(s"inBaseArrivals Took ${SDate.now().millisSinceEpoch - start.millisSinceEpoch}ms")
        }
      }
    })

    setHandler(inForecastArrivals, new InHandler {
      override def onPush(): Unit = {
        val start = SDate.now()
        log.info(s"inForecastArrivals onPush() grabbing forecast flights")
        val grabbedArrivals = grab(inForecastArrivals)
        log.info(s"Grabbed ${grabbedArrivals.flights.length} forecast arrivals")
        forecastArrivals = mergeUpdatesAndPurge(filterAndSetPcp(grabbedArrivals.flights), forecastArrivals)

        mergeAllSourcesAndPush(baseArrivals, forecastArrivals, liveArrivals)

        if (!hasBeenPulled(inForecastArrivals)) pull(inForecastArrivals)
        log.info(s"inForecastArrivals Took ${SDate.now().millisSinceEpoch - start.millisSinceEpoch}ms")
      }
    })

    setHandler(inLiveArrivals, new InHandler {
      override def onPush(): Unit = {
        val start = SDate.now()
        log.info(s"inLiveArrivals onPush() grabbing live flights")

        val grabbedArrivals = grab(inLiveArrivals)
        log.info(s"Grabbed ${grabbedArrivals.flights.length} live arrivals")
        liveArrivals = mergeUpdatesAndPurge(filterAndSetPcp(grabbedArrivals.flights), liveArrivals)

        mergeAllSourcesAndPush(baseArrivals, forecastArrivals, liveArrivals)

        if (!hasBeenPulled(inLiveArrivals)) pull(inLiveArrivals)
        log.info(s"inLiveArrivals Took ${SDate.now().millisSinceEpoch - start.millisSinceEpoch}ms")
      }
    })

    def mergeUpdatesAndPurge(updates: Map[Int, Arrival], existingArrivals: Map[Int, Arrival]): Map[Int, Arrival] = {
      val updated = updates
        .foldLeft(existingArrivals) {
          case (soFar, (newArrivalId, newArrival)) => soFar.updated(newArrivalId, newArrival)
        }

      val minusExpired = purgeExpired(updated)

      val numPurged = existingArrivals.size - minusExpired.size
      if (numPurged > 0) log.info(s"Purged $numPurged expired arrivals during update")

      minusExpired
    }

    def purgeExpired(flightsById: Map[Int, Arrival]): Map[Int, Arrival] = {
      val expired: Arrival => Boolean = Crunch.hasExpired(now(), expireAfterMillis, (a: Arrival) => a.PcpTime)
      flightsById.filterNot {
        case (_, arrival) => expired(arrival)
      }
    }

    def purgeExpired(flights: Set[Arrival]): Set[Arrival] = {
      val expired: Arrival => Boolean = Crunch.hasExpired(now(), expireAfterMillis, (a: Arrival) => a.PcpTime)
      flights.filterNot(expired)
    }

    def mergeAllSourcesAndPush(baseArrivals: Map[Int, Arrival], forecastArrivals: Map[Int, Arrival], liveArrivals: Map[Int, Arrival]): Unit = {
      val newMerged = mergeArrivals(baseArrivals, forecastArrivals, liveArrivals)
      val minusExpired = purgeExpired(newMerged)

      val numPurged = newMerged.size - minusExpired.size
      if (numPurged > 0) log.info(s"Purged $numPurged expired arrivals during merge: ${newMerged.size} -> ${minusExpired.size}")

      val maybeDiff1 = toPush
      val maybeDiff2 = arrivalsDiff(merged, minusExpired)
      toPush = Crunch.mergeMaybeArrivalsDiffs(maybeDiff1, maybeDiff2)

      pushIfAvailable(toPush, outArrivalsDiff)
      merged = minusExpired
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
            log.debug(s"Filtering out irrelevant arrival: ${f.IATA}, ${f.SchDT}, ${f.Origin}")
            true
          case _ => false
        }
        .map(f => (f.uniqueId, f.copy(PcpTime = pcpArrivalTime(f).millisSinceEpoch)))
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

    def mergeArrivals(base: Map[Int, Arrival], forecast: Map[Int, Arrival], live: Map[Int, Arrival]): Map[Int, Arrival] = {
      log.info(s"Merging arrival sources: ${base.size} base arrivals, ${forecast.size} forecast arrivals, ${live.size} live arrivals")

      val (notFoundCount, withForecast) = forecast.foldLeft((0, base)) {
        case ((notFoundSoFar, mergedSoFar), (fcstId, forecastArrival)) =>
          base.get(fcstId) match {
            case None =>
              (notFoundSoFar + 1, mergedSoFar)
            case Some(baseArrival) =>
              val actPax = if (forecastArrival.ActPax > 0) forecastArrival.ActPax else baseArrival.ActPax
              val mergedArrival = baseArrival.copy(ActPax = actPax, TranPax = forecastArrival.TranPax, Status = forecastArrival.Status)
              (notFoundSoFar, mergedSoFar.updated(fcstId, mergedArrival))
          }
      }
      log.info(s"Ignoring $notFoundCount forecast arrivals not found in base arrivals")

      val withLive = live.foldLeft(withForecast) {
        case (mergedSoFar, (liveId, liveArrival)) =>
          val baseArrival = base.getOrElse(liveId, liveArrival)
          val mergedSoFarArrival = mergedSoFar.getOrElse(liveId, liveArrival)
          val mergedArrival = liveArrival.copy(
            rawIATA = baseArrival.rawIATA,
            rawICAO = baseArrival.rawICAO,
            ActPax = if (liveArrival.ActPax > 0) liveArrival.ActPax else mergedSoFarArrival.ActPax,
            TranPax = if (liveArrival.ActPax > 0) liveArrival.TranPax else mergedSoFarArrival.TranPax)

          mergedSoFar.updated(liveId, mergedArrival)
      }

      withLive
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
