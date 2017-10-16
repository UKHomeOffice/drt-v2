package services.graphstages

import akka.actor.ActorRef
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.stream.{Attributes, FanInShape2, Inlet, Outlet}
import drt.shared.Crunch.MillisSinceEpoch
import drt.shared.FlightsApi.Flights
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import services.ArrivalsState
import services.graphstages.Crunch.midnightThisMorning

import scala.collection.immutable.{Map, Seq}
import scala.language.postfixOps

class ArrivalsGraphStage(initialBaseArrivals: Set[Arrival],
                         initialLiveArrivals: Set[Arrival],
                         baseArrivalsActor: ActorRef,
                         liveArrivalsActor: ActorRef,
                         pcpArrivalTime: (Arrival) => MilliDate, crunchStartDateProvider: () => MillisSinceEpoch = midnightThisMorning _,
                         validPortTerminals: Set[String])
  extends GraphStage[FanInShape2[Flights, Flights, ArrivalsDiff]] {

  val inBaseArrivals: Inlet[Flights] = Inlet[Flights]("inFlightsBase.in")
  val inLiveArrivals: Inlet[Flights] = Inlet[Flights]("inFlightsLive.in")
  val outArrivalsDiff: Outlet[ArrivalsDiff] = Outlet[ArrivalsDiff]("outArrivalsDiff.in")
  override val shape = new FanInShape2(inBaseArrivals, inLiveArrivals, outArrivalsDiff)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    var baseArrivals: Set[Arrival] = Set()
    var liveArrivals: Set[Arrival] = Set()
    var merged: Map[Int, Arrival] = Map()
    var toPush: Option[ArrivalsDiff] = None

    val log: Logger = LoggerFactory.getLogger(getClass)

    override def preStart(): Unit = {
      baseArrivals = initialBaseArrivals
      liveArrivals = initialLiveArrivals
      super.preStart()
    }

    setHandler(inBaseArrivals, new InHandler {
      override def onPush(): Unit = {
        log.info(s"inBase onPush() grabbing base flights")
        baseArrivals = grabAndSetPcp(inBaseArrivals)

        baseArrivalsActor ! ArrivalsState(baseArrivals.map(a => (a.uniqueId, a)).toMap)

        val newMerged = mergeArrivals(baseArrivals, liveArrivals)
        toPush = arrivalsDiff(merged, newMerged)
        pushIfAvailable(toPush, outArrivalsDiff)
      }
    })

    setHandler(inLiveArrivals, new InHandler {
      override def onPush(): Unit = {
        log.info(s"inLive onPush() grabbing live flights")
        liveArrivals = grabAndSetPcp(inLiveArrivals)
          .foldLeft(liveArrivals.map(a => (a.uniqueId, a)).toMap) {
            case (soFar, newArrival) => soFar.updated(newArrival.uniqueId, newArrival)
          }.values.toSet

        liveArrivalsActor ! ArrivalsState(liveArrivals.map(a => (a.uniqueId, a)).toMap)

        val newMerged = mergeArrivals(baseArrivals, liveArrivals)
        toPush = arrivalsDiff(merged, newMerged)
        pushIfAvailable(toPush, outArrivalsDiff)
      }
    })

    setHandler(outArrivalsDiff, new OutHandler {
      override def onPull(): Unit = {
        pushIfAvailable(toPush, outArrivalsDiff)

        if (!hasBeenPulled(inLiveArrivals)) pull(inLiveArrivals)
        if (!hasBeenPulled(inBaseArrivals)) pull(inBaseArrivals)
      }
    })

    def grabAndSetPcp(arrivals: Inlet[Flights]): Set[Arrival] = {
      grab(arrivals)
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

    def isFlightRelevant(flight: Arrival) =
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

    def mergeArrivals(base: Set[Arrival], live: Set[Arrival]): Map[Int, Arrival] = {
      val baseById: Map[Int, Arrival] = base.map(a => (a.uniqueId, a)).toMap

      live.foldLeft(baseById) {
        case (mergedSoFar, liveArrival) =>
          val baseArrival = baseById.getOrElse(liveArrival.uniqueId, liveArrival)
          val mergedArrival = liveArrival.copy(
            rawIATA = baseArrival.rawIATA,
            rawICAO = baseArrival.rawICAO,
            ActPax = if (liveArrival.ActPax > 0) liveArrival.ActPax else baseArrival.ActPax)
          mergedSoFar.updated(liveArrival.uniqueId, mergedArrival)
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
