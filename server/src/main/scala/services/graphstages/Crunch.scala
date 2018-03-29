package services.graphstages

import drt.shared.CrunchApi._
import drt.shared.FlightsApi.{QueueName, TerminalName}
import drt.shared._
import org.joda.time.{DateTime, DateTimeZone}
import org.slf4j.{Logger, LoggerFactory}
import services._

import scala.collection.immutable.Map
import scala.reflect.runtime.universe.{typeOf, TypeTag}

object Crunch {
  val log: Logger = LoggerFactory.getLogger(getClass)

  case class FlightSplitMinute(flightId: Int, paxType: PaxType, terminalName: TerminalName, queueName: QueueName, paxLoad: Double, workLoad: Double, minute: MillisSinceEpoch)

  case class FlightSplitDiff(flightId: Int, paxType: PaxType, terminalName: TerminalName, queueName: QueueName, paxLoad: Double, workLoad: Double, minute: MillisSinceEpoch)

  case class LoadMinute(terminalName: TerminalName, queueName: QueueName, paxLoad: Double, workLoad: Double, minute: MillisSinceEpoch) {
    lazy val uniqueId: Int = (terminalName, queueName, minute).hashCode
  }

  object LoadMinute {
    def apply(cm: CrunchMinute): LoadMinute = LoadMinute(cm.terminalName, cm.queueName, cm.paxLoad, cm.workLoad, cm.minute)
  }

  case class Loads(loadMinutes: Set[LoadMinute])

  object Loads {
    def apply(cms: Seq[CrunchMinute]): Loads = Loads(cms.map(LoadMinute(_)).toSet)
  }

  case class RemoveCrunchMinute(terminalName: TerminalName, queueName: QueueName, minute: MillisSinceEpoch) {
    lazy val key: Int = s"$terminalName$queueName$minute".hashCode
  }

  case class RemoveFlight(flightId: Int)

  case class CrunchDiff(flightRemovals: Set[RemoveFlight],
                        flightUpdates: Set[ApiFlightWithSplits],
                        crunchMinuteUpdates: Set[CrunchMinute],
                        staffMinuteUpdates: Set[StaffMinute])

  case class CrunchRequest(flights: List[ApiFlightWithSplits], crunchStart: MillisSinceEpoch)

  val oneMinuteMillis: MillisSinceEpoch = 60000L
  val oneHourMillis: MillisSinceEpoch = oneMinuteMillis * 60
  val oneDayMillis: MillisSinceEpoch = oneHourMillis * 24

  val europeLondonId = "Europe/London"
  val europeLondonTimeZone: DateTimeZone = DateTimeZone.forID(europeLondonId)

  def midnightThisMorning: MillisSinceEpoch = {
    val localNow = SDate(new DateTime(europeLondonTimeZone).getMillis)
    val crunchStartDate = Crunch.getLocalLastMidnight(localNow).millisSinceEpoch
    crunchStartDate
  }

  def getLocalLastMidnight(now: SDateLike): SDateLike = {
    val localNow = SDate(now, europeLondonTimeZone)
    val localMidnight = s"${localNow.getFullYear()}-${localNow.getMonth()}-${localNow.getDate()}T00:00"
    SDate(localMidnight, europeLondonTimeZone)
  }

  def getLocalNextMidnight(now: SDateLike): SDateLike = {
    val nextDay = now.addDays(1)
    val localMidnight = s"${nextDay.getFullYear()}-${nextDay.getMonth()}-${nextDay.getDate()}T00:00"
    SDate(localMidnight, europeLondonTimeZone)
  }

  def earliestAndLatestAffectedPcpTimeFromFlights(maxDays: Int)(existingFlights: Set[ApiFlightWithSplits], updatedFlights: Set[ApiFlightWithSplits]): Option[(SDateLike, SDateLike)] = {
    val differences: Set[ApiFlightWithSplits] = updatedFlights -- existingFlights
    val latestPcpTimes = differences
      .toList
      .sortBy(_.apiFlight.PcpTime)
      .map(_.apiFlight.PcpTime)

    if (latestPcpTimes.nonEmpty) {
      Option((SDate(latestPcpTimes.head), SDate(latestPcpTimes.reverse.head))) match {
        case Some((e, l)) if (l.millisSinceEpoch - e.millisSinceEpoch) / oneDayMillis <= maxDays => Some((e, l))
        case Some((e, _)) => Some((e, e.addDays(maxDays)))
      }
    } else None
  }

  def flightsDiff(oldFlightsById: Map[Int, ApiFlightWithSplits], newFlightsById: Map[Int, ApiFlightWithSplits]): (Set[RemoveFlight], Set[ApiFlightWithSplits]) = {
    val oldIds = oldFlightsById.keys.toSet
    val newIds = newFlightsById.keys.toSet
    val toRemove = (oldIds -- newIds).map(RemoveFlight)
    val toUpdate = newFlightsById.collect {
      case (id, f) if oldFlightsById.get(id).isEmpty || !f.equals(oldFlightsById(id)) => f
    }.toSet

    toUpdate.map(_.apiFlight).foreach(ua => {
      oldFlightsById.get(ua.uniqueId).map(_.apiFlight).foreach(oa => {
        if (ua.EstDT != oa.EstDT) log.info(s"${ua.IATA} changed estimated ${oa.EstDT} -> ${ua.EstDT}")
        if (ua.ActDT != oa.ActDT) log.info(s"${ua.IATA} changed touchdown ${oa.ActDT} -> ${ua.ActDT}")
        if (ua.EstChoxDT != oa.EstChoxDT) log.info(s"${ua.IATA} changed estchox   ${oa.EstChoxDT} -> ${ua.EstChoxDT}")
        if (ua.ActChoxDT != oa.ActChoxDT) log.info(s"${ua.IATA} changed actchox   ${oa.ActChoxDT} -> ${ua.ActChoxDT}")
        if (ua.ActPax != oa.ActPax) log.info(s"${ua.IATA} changed actpax   ${oa.ActPax} -> ${ua.ActPax}")
      })
    })

    Tuple2(toRemove, toUpdate)
  }

  def crunchMinutesDiff(oldTqmToCm: Map[Int, CrunchMinute], newTqmToCm: Map[Int, CrunchMinute]): Set[CrunchMinute] = {
    val toUpdate = newTqmToCm.collect {
      case (k, cm) if oldTqmToCm.get(k).isEmpty || !cm.equals(oldTqmToCm(k)) => cm
    }.toSet

    toUpdate
  }

  def staffMinutesDiff(oldTqmToCm: Map[Int, StaffMinute], newTqmToCm: Map[Int, StaffMinute]): Set[StaffMinute] = {
    val toUpdate = newTqmToCm.collect {
      case (k, cm) if oldTqmToCm.get(k).isEmpty || !cm.equals(oldTqmToCm(k)) => cm
    }.toSet

    toUpdate
  }

  def crunchMinuteToTqmCm(cm: CrunchMinute): ((TerminalName, QueueName, MillisSinceEpoch), CrunchMinute) = {
    Tuple2(Tuple3(cm.terminalName, cm.queueName, cm.minute), cm)
  }

  def applyCrunchDiff(diff: CrunchDiff, crunchMinutes: Map[Int, CrunchMinute]): Map[Int, CrunchMinute] = {
    val nowMillis = SDate.now().millisSinceEpoch
    val withUpdates = diff.crunchMinuteUpdates.foldLeft(crunchMinutes) {
      case (soFar, ncm) => soFar.updated(ncm.key, ncm.copy(lastUpdated = Option(nowMillis)))
    }
    withUpdates
  }

  def applyStaffDiff(diff: CrunchDiff, staffMinutes: Map[Int, StaffMinute]): Map[Int, StaffMinute] = {
    val nowMillis = SDate.now().millisSinceEpoch
    val withUpdates = diff.staffMinuteUpdates.foldLeft(staffMinutes) {
      case (soFar, newStaffMinute) => soFar.updated(newStaffMinute.key, newStaffMinute.copy(lastUpdated = Option(nowMillis)))
    }
    withUpdates
  }

  def applyFlightsWithSplitsDiff(diff: CrunchDiff, flights: Map[Int, ApiFlightWithSplits]): Map[Int, ApiFlightWithSplits] = {
    val nowMillis = SDate.now().millisSinceEpoch
    val withoutRemovals = diff.flightRemovals.foldLeft(flights) {
      case (soFar, removeFlight) => soFar - removeFlight.flightId
    }
    val withoutRemovalsWithUpdates = diff.flightUpdates.foldLeft(withoutRemovals) {
      case (soFar, flight) => soFar.updated(flight.apiFlight.uniqueId, flight.copy(lastUpdated = Option(nowMillis)))
    }
    withoutRemovalsWithUpdates
  }

  def flightLoadDiff(oldSet: Set[FlightSplitMinute], newSet: Set[FlightSplitMinute]): Set[FlightSplitDiff] = {
    val toRemove = oldSet.map(fsm => FlightSplitMinute(fsm.flightId, fsm.paxType, fsm.terminalName, fsm.queueName, -fsm.paxLoad, -fsm.workLoad, fsm.minute))
    val addAndRemoveGrouped: Map[(Int, TerminalName, QueueName, MillisSinceEpoch, PaxType), Set[FlightSplitMinute]] = newSet
      .union(toRemove)
      .groupBy(fsm => (fsm.flightId, fsm.terminalName, fsm.queueName, fsm.minute, fsm.paxType))

    addAndRemoveGrouped
      .map {
        case ((fid, tn, qn, m, pt), fsm) => FlightSplitDiff(fid, pt, tn, qn, fsm.map(_.paxLoad).sum, fsm.map(_.workLoad).sum, m)
      }
      .filterNot(fsd => fsd.paxLoad == 0 && fsd.workLoad == 0)
      .toSet
  }

  def collapseQueueLoadMinutesToSet(queueLoadMinutes: List[LoadMinute]): Set[LoadMinute] = {
    queueLoadMinutes
      .groupBy(qlm => (qlm.terminalName, qlm.queueName, qlm.minute))
      .map {
        case ((t, q, m), qlm) =>
          val summedPaxLoad = qlm.map(_.paxLoad).sum
          val summedWorkLoad = qlm.map(_.workLoad).sum
          LoadMinute(t, q, summedPaxLoad, summedWorkLoad, m)
      }.toSet
  }

  def desksForHourOfDayInUKLocalTime(dateTimeMillis: MillisSinceEpoch, desks: Seq[Int]): Int = {
    val date = new DateTime(dateTimeMillis).withZone(europeLondonTimeZone)
    desks(date.getHourOfDay)
  }

  def purgeExpired[A: TypeTag](expireable: Map[Int, A], timeAccessor: A => MillisSinceEpoch, now: () => SDateLike, expireAfter: MillisSinceEpoch): Map[Int, A] = {
    val expired = hasExpiredForType(timeAccessor, now, expireAfter)
    val updated = expireable.filterNot { case (_, a) => expired(a) }

    val numPurged = expireable.size - updated.size
    if (numPurged > 0) log.info(s"Purged $numPurged ${typeOf[A].toString}")

    updated
  }

  def purgeExpired[A: TypeTag](expireable: Set[A], timeAccessor: A => MillisSinceEpoch, now: () => SDateLike, expireAfter: MillisSinceEpoch): Set[A] = {
    val expired = hasExpiredForType(timeAccessor, now, expireAfter)
    val updated = expireable.filterNot(expired)

    val numPurged = expireable.size - updated.size
    if (numPurged > 0) log.info(s"Purged $numPurged ${typeOf[A].toString}")

    updated
  }

  def hasExpiredForType[A](toMillis: A => MillisSinceEpoch, now: () => SDateLike, expireAfter: MillisSinceEpoch): A => Boolean = {
    Crunch.hasExpired[A](now(), expireAfter, toMillis)
  }

  def hasExpired[A](now: SDateLike, expireAfterMillis: Long, toMillis: (A) => MillisSinceEpoch)(toCompare: A): Boolean = {
    val ageInMillis = now.millisSinceEpoch - toMillis(toCompare)
    ageInMillis > expireAfterMillis
  }

  def purgeExpiredMinutes[M <: Minute](minutes: Map[Int, M], now: () => SDateLike, expireAfterMillis: MillisSinceEpoch): Map[Int, M] = {
    val expired: M => Boolean = Crunch.hasExpired(now(), expireAfterMillis, (cm: M) => cm.minute)
    val updated = minutes.filterNot { case (_, cm) => expired(cm) }
    val numPurged = minutes.size - updated.size
    if (numPurged > 0) log.info(s"Purged $numPurged expired CrunchMinutes")
    updated
  }

  def mergeArrivalsDiffs(diff1: ArrivalsDiff, diff2: ArrivalsDiff): ArrivalsDiff = {
    val mergedUpdates = diff2.toUpdate
      .foldLeft(diff1.toUpdate) {
        case (soFar, newArrival) => soFar.filterNot(_.uniqueId == newArrival.uniqueId) + newArrival
      }
    val mergedRemovals = diff2.toRemove ++ diff1.toRemove
    ArrivalsDiff(mergedUpdates, mergedRemovals)
  }

  def mergeMaybeArrivalsDiffs(maybeDiff1: Option[ArrivalsDiff], maybeDiff2: Option[ArrivalsDiff]): Option[ArrivalsDiff] = {
    (maybeDiff1, maybeDiff2) match {
      case (None, None) => None
      case (Some(diff1), None) => Option(diff1)
      case (None, Some(diff2)) => Option(diff2)
      case (Some(diff1), Some(diff2)) =>
        log.info(s"Merging ArrivalsDiffs")
        Option(mergeArrivalsDiffs(diff1, diff2))
    }
  }

  def mergeMapOfIndexedThings[X](things1: Map[Int, X], things2: Map[Int, X]): Map[Int, X] = things2.foldLeft(things1) {
    case (soFar, (id, newThing)) => soFar.updated(id, newThing)
  }

  def mergeMaybePortStates(maybePortState1: Option[PortState], maybePortState2: Option[PortState]): Option[PortState] = {
    (maybePortState1, maybePortState2) match {
      case (None, None) => None
      case (Some(ps), None) => Option(ps)
      case (None, Some(ps)) => Option(ps)
      case (Some(ps1), Some(ps2)) => Option(mergePortState(ps1, ps2))
    }
  }

  def mergePortState(ps1: PortState, ps2: PortState): PortState = {
    val mergedFlights = mergeMapOfIndexedThings(ps1.flights, ps2.flights)
    val mergedCrunchMinutes = mergeMapOfIndexedThings(ps1.crunchMinutes, ps2.crunchMinutes)
    val mergedStaffMinutes = mergeMapOfIndexedThings(ps1.staffMinutes, ps2.staffMinutes)
    PortState(mergedFlights, mergedCrunchMinutes, mergedStaffMinutes)
  }

  def combineArrivalsWithMaybeSplits(as1: Seq[(Arrival, Option[ApiSplits])], as2: Seq[(Arrival, Option[ApiSplits])]): Seq[(Arrival, Option[ApiSplits])] = {
    val arrivalsWithMaybeSplitsById = as1
      .map {
        case (arrival, maybeSplits) => (arrival.uniqueId, (arrival, maybeSplits))
      }
      .toMap
    as2
      .foldLeft(arrivalsWithMaybeSplitsById) {
        case (soFar, (arrival, maybeNewSplits)) =>
          soFar.updated(arrival.uniqueId, (arrival, maybeNewSplits))
      }
      .map {
        case (_, arrivalWithMaybeSplits) => arrivalWithMaybeSplits
      }
      .toSeq
  }
}
