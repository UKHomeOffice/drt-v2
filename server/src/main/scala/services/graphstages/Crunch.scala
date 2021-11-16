package services.graphstages

import drt.shared.CrunchApi._
import drt.shared.MilliTimes._
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import drt.shared._
import drt.shared.api.Arrival
import drt.shared.dates.{DateLikeOrdering, UtcDate}
import org.joda.time.{DateTime, DateTimeZone}
import org.slf4j.{Logger, LoggerFactory}
import services._
import uk.gov.homeoffice.drt.ports.PaxType

import scala.collection.immutable.{Map, SortedMap, SortedSet}
import scala.collection.mutable

object Crunch {
  val paxOffPerMinute: Int = 20

  val log: Logger = LoggerFactory.getLogger(getClass)
  
  case class SplitMinutes(minutes: Map[TQM, LoadMinute]) {
    def ++(incoming: Iterable[FlightSplitMinute]): SplitMinutes = {
      incoming.foldLeft(this) {
        case (acc, fsm) =>
          acc + LoadMinute(fsm.terminalName, fsm.queueName, fsm.paxLoad, fsm.workLoad, fsm.minute)
      }
    }

    def +(incoming: LoadMinute): SplitMinutes = {
      val key = incoming.uniqueId
      minutes.get(key) match {
        case None => SplitMinutes(minutes + (key -> incoming))
        case Some(existingFsm) => SplitMinutes(minutes + (key -> (existingFsm + incoming)))
      }
    }

    def toLoads: Loads = Loads(SortedMap[TQM, LoadMinute]() ++ minutes)
  }

  case class FlightSplitMinute(flightId: CodeShareKeyOrderedBySchedule,
                               paxType: PaxType,
                               terminalName: Terminal,
                               queueName: Queue,
                               paxLoad: Double,
                               workLoad: Double,
                               minute: MillisSinceEpoch) {
    lazy val key: TQM = TQM(terminalName, queueName, minute)
  }

  case class FlightSplitDiff(flightId: CodeShareKeyOrderedBySchedule,
                             paxType: PaxType,
                             terminalName: Terminal,
                             queueName: Queue,
                             paxLoad: Double,
                             workLoad: Double,
                             minute: MillisSinceEpoch)

  case class LoadMinute(terminal: Terminal,
                        queue: Queue,
                        paxLoad: Double,
                        workLoad: Double,
                        minute: MillisSinceEpoch) extends TerminalQueueMinute {
    lazy val uniqueId: TQM = TQM(terminal, queue, minute)

    def +(other: LoadMinute): LoadMinute = this.copy(
      paxLoad = this.paxLoad + other.paxLoad,
      workLoad = this.workLoad + other.workLoad
      )
  }

  object LoadMinute {
    def apply(cm: CrunchMinute): LoadMinute = LoadMinute(cm.terminal, cm.queue, cm.paxLoad, cm.workLoad, cm.minute)
    def apply(cm: DeskRecMinute): LoadMinute = LoadMinute(cm.terminal, cm.queue, cm.paxLoad, cm.workLoad, cm.minute)
  }

  case class Loads(loadMinutes: SortedMap[TQM, LoadMinute]) {
  }

  object Loads {
    def apply(lms: Seq[LoadMinute]): Loads = Loads(SortedMap[TQM, LoadMinute]() ++ lms.map(cm => (TQM(cm.terminal, cm.queue, cm.minute), cm)))

    def fromCrunchMinutes(cms: SortedMap[TQM, CrunchMinute]): Loads = Loads(cms.mapValues(LoadMinute(_)))
  }

  case class RemoveCrunchMinute(terminalName: Terminal, queueName: Queue, minute: MillisSinceEpoch) {
    lazy val key: TQM = MinuteHelper.key(terminalName, queueName, minute)
  }

  val europeLondonId = "Europe/London"
  val europeLondonTimeZone: DateTimeZone = DateTimeZone.forID(europeLondonId)

  val utcId = "UTC"
  val utcTimeZone: DateTimeZone = DateTimeZone.forID(utcId)

  def isInRangeOnDay(startDateTime: SDateLike, endDateTime: SDateLike)(minute: SDateLike): Boolean =
    startDateTime.millisSinceEpoch <= minute.millisSinceEpoch && minute.millisSinceEpoch <= endDateTime.millisSinceEpoch

  def midnightThisMorning: MillisSinceEpoch = {
    val localNow = SDate(new DateTime(europeLondonTimeZone).getMillis)
    val crunchStartDate = SDate(localNow.millisSinceEpoch).getLocalLastMidnight.millisSinceEpoch
    crunchStartDate
  }

  def isDueLookup(scheduled: MillisSinceEpoch, lastLookupMillis: MillisSinceEpoch, now: SDateLike): Boolean = {
    val soonWithExpiredLookup = isWithinHours(scheduled, 48, now) && !wasWithinHours(lastLookupMillis, 24, now)
    val notSoonWithExpiredLookup = !isWithinHours(scheduled, 48, now) && !wasWithinHours(lastLookupMillis, 24 * 7, now)

    soonWithExpiredLookup || notSoonWithExpiredLookup
  }

  def isWithinHours(millis: MillisSinceEpoch, hours: Int, now: SDateLike): Boolean = {
    val target = now.addHours(hours)
    millis <= target.millisSinceEpoch
  }

  def wasWithinHours(millis: MillisSinceEpoch, hours: Int, now: SDateLike): Boolean = {
    val target = now.addHours(-hours)
    target.millisSinceEpoch <= millis
  }

  def changedDays(offsetMinutes: Int, staffMinutes: StaffMinutes): Map[MillisSinceEpoch, Seq[StaffMinute]] =
    staffMinutes.minutes.groupBy(minutes => {
      SDate(minutes.minute - offsetMinutes * 60000).getLocalLastMidnight.millisSinceEpoch
    })

  def minuteMillisFor24hours(dayMillis: MillisSinceEpoch): Iterable[MillisSinceEpoch] =
    (0 until minutesInADay).map(m => dayMillis + (m * oneMinuteMillis))

  def missingMinutesForDay(fromMillis: MillisSinceEpoch,
                           minuteExistsTerminals: (MillisSinceEpoch, List[Terminal]) => Boolean,
                           terminals: List[Terminal],
                           days: Int): Set[MillisSinceEpoch] = {
    val fromMillisMidnight = SDate(fromMillis).getLocalLastMidnight.millisSinceEpoch

    (0 until days).foldLeft(Iterable[MillisSinceEpoch]()) {
      case (missingSoFar, day) =>
        val dayMillis = fromMillisMidnight + (day * oneDayMillis)
        val isMissing = !minuteExistsTerminals(dayMillis, terminals)
        if (isMissing) Crunch.minuteMillisFor24hours(dayMillis) ++ missingSoFar
        else missingSoFar
    }.toSet
  }

  def filterNonMinuteBoundaryMillis(millis: List[MillisSinceEpoch]): List[MillisSinceEpoch] = millis.filter(_ % oneMinuteMillis == 0)

  def earliestAndLatestAffectedPcpTimeFromFlights(maxDays: Int)
                                                 (existingFlights: Set[ApiFlightWithSplits],
                                                  updatedFlights: Set[ApiFlightWithSplits]): Option[(SDateLike, SDateLike)] = {
    val differences: Set[ApiFlightWithSplits] = updatedFlights -- existingFlights
    val latestPcpTimes = differences
      .toList
      .sortBy(_.apiFlight.PcpTime)
      .flatMap(_.apiFlight.PcpTime)

    if (latestPcpTimes.nonEmpty) {
      Option((SDate(latestPcpTimes.head), SDate(latestPcpTimes.reverse.head))) match {
        case Some((e, l)) if (l.millisSinceEpoch - e.millisSinceEpoch) / oneDayMillis <= maxDays => Some((e, l))
        case Some((e, _)) => Some((e, e.addDays(maxDays)))
      }
    } else None
  }

  def flightLoadDiff(oldSet: Set[FlightSplitMinute], newSet: Set[FlightSplitMinute]): Set[FlightSplitDiff] = {
    val toRemove = oldSet.map(fsm => FlightSplitMinute(fsm.flightId, fsm.paxType, fsm.terminalName, fsm.queueName, -fsm.paxLoad, -fsm.workLoad, fsm.minute))
    val addAndRemoveGrouped: Map[(CodeShareKeyOrderedBySchedule, Terminal, Queue, MillisSinceEpoch, PaxType), Set[FlightSplitMinute]] = newSet
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
      .groupBy(qlm => (qlm.terminal, qlm.queue, qlm.minute))
      .map {
        case ((t, q, m), qlm) =>
          val summedPaxLoad = qlm.map(_.paxLoad).sum
          val summedWorkLoad = qlm.map(_.workLoad).sum
          LoadMinute(t, q, summedPaxLoad, summedWorkLoad, m)
      }.toSet
  }

  def purgeExpired[A <: WithTimeAccessor, B](expireable: mutable.SortedMap[A, B],
                                             atTime: MillisSinceEpoch => A,
                                             now: () => SDateLike,
                                             expireAfter: Int): Unit = {
    val thresholdMillis = now().addMillis(-1 * expireAfter).millisSinceEpoch
    val sizeBefore = expireable.size
    val expired = expireable.range(atTime(0L), atTime(thresholdMillis + 1))
    expireable --= expired.keys
    val purgedCount = sizeBefore - expireable.size
    if (purgedCount > 0) log.info(s"Purged $purgedCount items (mutable.SortedMap[A, B])")
  }

  def purgeExpired[A <: WithTimeAccessor, B](expireable: SortedMap[A, B],
                                             atTime: MillisSinceEpoch => A,
                                             now: () => SDateLike,
                                             expireAfter: Int): SortedMap[A, B] = {
    val thresholdMillis = now().addMillis(-1 * expireAfter).millisSinceEpoch
    val sizeBefore = expireable.size
    val expired = expireable.range(atTime(0L), atTime(thresholdMillis + 1))
    val updatedExpireable = expireable -- expired.keys
    val purgedCount = sizeBefore - updatedExpireable.size
    if (purgedCount > 0) log.info(s"Purged $purgedCount items (SortedMap[A, B])")
    updatedExpireable
  }

  def purgeExpired[A <: WithTimeAccessor](expireable: mutable.SortedSet[A],
                                          atTime: MillisSinceEpoch => A,
                                          now: () => SDateLike,
                                          expireAfter: Int): Unit = {
    val thresholdMillis = now().addMillis(-1 * expireAfter).millisSinceEpoch
    val sizeBefore = expireable.size
    val expired = expireable.range(atTime(0L), atTime(thresholdMillis + 1))
    expireable --= expired
    val purgedCount = sizeBefore - expireable.size
    if (purgedCount > 0) log.info(s"Purged $purgedCount items (mutable.SortedSet[A])")
  }

  def purgeExpired[A <: WithTimeAccessor](expireable: SortedSet[A],
                                          atTime: MillisSinceEpoch => A,
                                          now: () => SDateLike,
                                          expireAfter: Int): SortedSet[A] = {
    val thresholdMillis = now().addMillis(-1 * expireAfter).millisSinceEpoch
    val sizeBefore = expireable.size
    val expired = expireable.range(atTime(0L), atTime(thresholdMillis + 1))
    val updatedExpireable = expireable -- expired
    val purgedCount = sizeBefore - updatedExpireable.size
    if (purgedCount > 0) log.info(s"Purged $purgedCount items (SortedSet[A])")
    updatedExpireable
  }

  def hasExpired[A](now: SDateLike, expireAfterMillis: Int, toMillis: A => MillisSinceEpoch)(toCompare: A): Boolean = {
    val ageInMillis = now.millisSinceEpoch - toMillis(toCompare)
    ageInMillis > expireAfterMillis
  }

  def mergeMaybePortStates(maybePortState1: Option[PortState],
                           maybePortState2: Option[PortState]): Option[PortState] = {
    (maybePortState1, maybePortState2) match {
      case (None, None) => None
      case (Some(ps), None) => Option(ps)
      case (None, Some(ps)) => Option(ps)
      case (Some(ps1), Some(ps2)) => Option(mergePortState(ps1, ps2))
    }
  }

  def mergePortState(ps1: PortState, ps2: PortState): PortState = {
    val mergedFlights = ps1.flights ++ ps2.flights
    val mergedCrunchMinutes = ps1.crunchMinutes ++ ps2.crunchMinutes
    val mergedStaffMinutes = ps1.staffMinutes ++ ps2.staffMinutes
    PortState(mergedFlights, mergedCrunchMinutes, mergedStaffMinutes)
  }

  def combineArrivalsWithMaybeSplits(as1: Seq[(Arrival, Option[Splits])],
                                     as2: Seq[(Arrival, Option[Splits])]): Seq[(Arrival, Option[Splits])] = {
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

  def movementsUpdateCriteria(existingMovements: Set[StaffMovement],
                              incomingMovements: Seq[StaffMovement]): UpdateCriteria = {
    val updatedMovements = incomingMovements.toSet -- existingMovements
    val deletedMovements = existingMovements -- incomingMovements.toSet
    val affectedMovements = updatedMovements ++ deletedMovements
    log.info(s"affected movements: $affectedMovements")
    val minutesToUpdate = allMinuteMillis(affectedMovements.toSeq)
    val terminalsToUpdate = affectedMovements.map(_.terminal)

    UpdateCriteria(minutesToUpdate, terminalsToUpdate)
  }

  def allMinuteMillis(movements: Seq[StaffMovement]): Seq[MillisSinceEpoch] = movements
    .groupBy(_.uUID)
    .flatMap {
      case (_, mmPair) =>
        val startMillis = mmPair.map(_.time.millisSinceEpoch).min
        val endMillis = mmPair.map(_.time.millisSinceEpoch).max
        startMillis until endMillis by 60000
    }
    .toSeq

  def baseArrivalsRemovalsAndUpdates(incoming: Map[UniqueArrival, Arrival],
                                     existing: Map[UniqueArrival, Arrival]): (Set[UniqueArrival], Iterable[Arrival]) = {
    val removals = existing.keys.toSet -- incoming.keys.toSet

    val updates = incoming.collect {
      case (k, a) if !existing.contains(k) || existing(k) != a =>  a
    }

    (removals, updates)
  }

  def arrivalDaysAffected(crunchOffsetMinutes: Int, paxOffPerMinute: Int)(arrival: Arrival): Set[String] = {
    arrival.PcpTime.toSet.flatMap { pcpTime: MillisSinceEpoch =>
      val first = SDate(pcpTime)
      val minutesOfPaxArrivals: Int = (arrival.ActPax.getOrElse(0).toDouble / paxOffPerMinute).ceil.toInt - 1
      val last = first.addMinutes(minutesOfPaxArrivals)
      List(first, last).map(_.addMinutes(-1 * crunchOffsetMinutes).toISODateOnly).toSet
    }
  }

  def tqmsDaysAffected(crunchOffsetMinutes: Int)(tqms: List[TQM]): Set[String] =
    if (tqms.isEmpty)
      Set()
    else
      Set(tqms.min, tqms.max).map(m => SDate(m.minute).addMinutes(-1 * crunchOffsetMinutes).toISODateOnly)

  def crunchStartWithOffset(offsetMinutes: Int)(minuteInQuestion: SDateLike): SDateLike = {
    val adjustedMinute = minuteInQuestion.addMinutes(-1 * offsetMinutes)
    adjustedMinute.getLocalLastMidnight.addMinutes(offsetMinutes)
  }

  @scala.annotation.tailrec
  def reduceIterables[A](iterables: List[Iterable[A]])(combine: (A, A) => A): Iterable[A] = iterables match {
    case Nil => Nil
    case head :: Nil => head
    case emptyHead1 :: head2 :: tail if emptyHead1.isEmpty => reduceIterables(head2 :: tail)(combine)
    case head1 :: emptyHead2 :: tail if emptyHead2.isEmpty => reduceIterables(head1 :: tail)(combine)
    case head1 :: head2 :: tail =>
      val reducedHead = head1.zip(head2).map {
        case (a, b) => combine(a, b)
      }
      reduceIterables(reducedHead :: tail)(combine)
  }

  def utcDaysInPeriod(start: SDateLike, end: SDateLike): Seq[UtcDate] = {
    val startForTimeZone = SDate(start, Crunch.utcTimeZone)
    val endForTimeZone = SDate(end, Crunch.utcTimeZone)

    (startForTimeZone.millisSinceEpoch to endForTimeZone.millisSinceEpoch by MilliTimes.oneHourMillis)
      .map(SDate(_).toUtcDate)
      .distinct
      .toList
  }

  def utcDatesInPeriod(start: SDateLike, end: SDateLike): List[UtcDate] = {
    val startForTimeZone = SDate(start, Crunch.utcTimeZone)
    val endForTimeZone = SDate(end, Crunch.utcTimeZone)

    (startForTimeZone.millisSinceEpoch to endForTimeZone.millisSinceEpoch by MilliTimes.oneHourMillis)
      .map(SDate(_).toUtcDate)
      .distinct
      .sorted(DateLikeOrdering)
      .toList
  }
}
