package services.graphstages

import akka.actor.ActorRef
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.stream._
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.Flights
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import services.{ArrivalsState, SDate}
import services.graphstages.Crunch.midnightThisMorning

import scala.collection.immutable.{Map, Seq}
import scala.language.postfixOps

class ArrivalsGraphStage(initialBaseArrivals: Set[Arrival],
                         initialForecastArrivals: Set[Arrival],
                         initialLiveArrivals: Set[Arrival],
                         baseArrivalsActor: ActorRef,
                         forecastArrivalsActor: ActorRef,
                         liveArrivalsActor: ActorRef,
                         pcpArrivalTime: (Arrival) => MilliDate, crunchStartDateProvider: () => MillisSinceEpoch = midnightThisMorning _,
                         validPortTerminals: Set[String],
                         expireAfterMillis: Long,
                         now: () => SDateLike)
  extends GraphStage[FanInShape3[Flights, Flights, Flights, ArrivalsDiff]] {

  val inBaseArrivals: Inlet[Flights] = Inlet[Flights]("inFlightsBase.in")
  val inForecastArrivals: Inlet[Flights] = Inlet[Flights]("inFlightsForecast.in")
  val inLiveArrivals: Inlet[Flights] = Inlet[Flights]("inFlightsLive.in")
  val outArrivalsDiff: Outlet[ArrivalsDiff] = Outlet[ArrivalsDiff]("outArrivalsDiff.in")
  override val shape = new FanInShape3(inBaseArrivals, inForecastArrivals, inLiveArrivals, outArrivalsDiff)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    var baseArrivals: Set[Arrival] = Set()
    var forecastArrivalsById: Map[Int, Arrival] = Map()
    var liveArrivals: Map[Int, Arrival] = Map()
    var merged: Map[Int, Arrival] = Map()
    var toPush: Option[ArrivalsDiff] = None

    val log: Logger = LoggerFactory.getLogger(getClass)

    override def preStart(): Unit = {
      baseArrivals = initialBaseArrivals
      forecastArrivalsById = initialForecastArrivals.map(a => (a.uniqueId, a)).toMap
      liveArrivals = initialLiveArrivals.map(a => (a.uniqueId, a)).toMap
      super.preStart()
    }

    setHandler(inBaseArrivals, new InHandler {
      override def onPush(): Unit = {
        log.info(s"inBaseArrivals onPush() grabbing base flights")
        baseArrivals = grabAndSetPcp(inBaseArrivals)

        baseArrivalsActor ! ArrivalsState(baseArrivals.map(a => (a.uniqueId, a)).toMap)

        mergeAndPush(baseArrivals, forecastArrivalsById.values.toSet, liveArrivals.values.toSet)

        if (!hasBeenPulled(inBaseArrivals)) pull(inBaseArrivals)
      }
    })

    setHandler(inForecastArrivals, new InHandler {
      override def onPush(): Unit = {
        log.info(s"inForecastArrivals onPush() grabbing forecast flights")
        forecastArrivalsById = grabAndSetPcp(inForecastArrivals).foldLeft(forecastArrivalsById) {
          case (soFar, updatedArrival) => soFar.updated(updatedArrival.uniqueId, updatedArrival)
        }

        forecastArrivalsActor ! ArrivalsState(forecastArrivalsById)

        mergeAndPush(baseArrivals, forecastArrivalsById.values.toSet, liveArrivals.values.toSet)

        if (!hasBeenPulled(inForecastArrivals)) pull(inForecastArrivals)
      }
    })

    setHandler(inLiveArrivals, new InHandler {
      override def onPush(): Unit = {
        log.info(s"inLiveArrivals onPush() grabbing live flights")

        liveArrivals = updateAndPurge(grabAndSetPcp(inLiveArrivals), liveArrivals)

        liveArrivalsActor ! ArrivalsState(liveArrivals)

        mergeAndPush(baseArrivals, forecastArrivalsById.values.toSet, liveArrivals.values.toSet)

        if (!hasBeenPulled(inLiveArrivals)) pull(inLiveArrivals)
      }
    })

    def updateAndPurge(updates: Set[Arrival], existingArrivals: Map[Int, Arrival]): Map[Int, Arrival] = {
      val expired: Arrival => Boolean = Crunch.hasExpired(now(), expireAfterMillis, (a: Arrival) => a.PcpTime)
      val updated = updates
        .foldLeft(existingArrivals) {
          case (soFar, newArrival) => soFar.updated(newArrival.uniqueId, newArrival)
        }
        .filterNot {
          case (_, arrival) => expired(arrival)
        }

      log.info(s"Purged ${existingArrivals.size - updated.size} expired arrivals during update")

      updated
    }

    def mergeAndPush(baseArrivals: Set[Arrival], forecastArrivals: Set[Arrival], liveArrivals: Set[Arrival]): Unit = {
      val expired: Arrival => Boolean = Crunch.hasExpired(now(), expireAfterMillis, (a: Arrival) => a.PcpTime)

      val newMerged = mergeArrivals(baseArrivals, forecastArrivals, liveArrivals)
      val newMergedFiltered = newMerged.filterNot { case (_, a) => expired(a) }

      log.info(s"Purged ${newMerged.size - newMergedFiltered.size} expired arrivals during merge")

      toPush = arrivalsDiff(merged, newMergedFiltered)
      pushIfAvailable(toPush, outArrivalsDiff)
      merged = newMergedFiltered
    }

    setHandler(outArrivalsDiff, new OutHandler {
      override def onPull(): Unit = {
        pushIfAvailable(toPush, outArrivalsDiff)

        if (!hasBeenPulled(inLiveArrivals)) pull(inLiveArrivals)
        if (!hasBeenPulled(inForecastArrivals)) pull(inForecastArrivals)
        if (!hasBeenPulled(inBaseArrivals)) pull(inBaseArrivals)
      }
    })

    def grabAndSetPcp(arrivals: Inlet[Flights]): Set[Arrival] = {
      val grabbedArrivals = grab(arrivals)
      log.info(s"Grabbed ${grabbedArrivals.flights.length} arrivals")
      grabbedArrivals
        .flights
        .filterNot {
          case f if !isFlightRelevant(f) =>
            log.debug(s"Filtering out irrelevant arrival: ${f.IATA}, ${f.SchDT}, ${f.Origin}")
            true
          case _ => false
        }
        .map(f => f.copy(PcpTime = pcpArrivalTime(f).millisSinceEpoch))
        .toSet
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

    def mergeArrivals(base: Set[Arrival], forecast: Set[Arrival], live: Set[Arrival]): Map[Int, Arrival] = {
      val baseById: Map[Int, Arrival] = base.map(a => (a.uniqueId, a)).toMap

      log.info(s"Merging arrival sources: ${base.size} base arrivals, ${forecast.size} forecast arrivals, ${live.size} live arrivals")

      val withForecast = forecast.foldLeft(baseById) {
        case (mergedSoFar, forecastArrival) =>
          baseById.get(forecastArrival.uniqueId) match {
            case None =>
              log.info(s"Forecast arrival ${forecastArrival.IATA} on ${SDate(forecastArrival.Scheduled).toLocalDateTimeString()} not found in base arrivals so ignoring")
              mergedSoFar
            case Some(baseArrival) =>
              val actPax = if (forecastArrival.ActPax > 0) forecastArrival.ActPax else baseArrival.ActPax
              val mergedArrival = baseArrival.copy(ActPax = actPax, TranPax = forecastArrival.TranPax, Status = forecastArrival.Status)
              mergedSoFar.updated(forecastArrival.uniqueId, mergedArrival)
          }
      }

      val withLive = live.foldLeft(withForecast) {
        case (mergedSoFar, liveArrival) =>
          val baseArrival = baseById.getOrElse(liveArrival.uniqueId, liveArrival)
          val mergedArrival = liveArrival.copy(
            rawIATA = baseArrival.rawIATA,
            rawICAO = baseArrival.rawICAO,
            ActPax = if (liveArrival.ActPax > 0) liveArrival.ActPax else baseArrival.ActPax)
          mergedSoFar.updated(liveArrival.uniqueId, mergedArrival)
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
