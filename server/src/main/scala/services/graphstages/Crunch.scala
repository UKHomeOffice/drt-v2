package services.graphstages

import drt.shared.CrunchApi._
import drt.shared.FlightsApi.{QueueName, TerminalName}
import drt.shared._
import org.joda.time.{DateTime, DateTimeZone}
import org.slf4j.{Logger, LoggerFactory}
import services._
import services.workloadcalculator.PaxLoadCalculator.Load

import scala.collection.immutable.{Map, SortedMap, SortedSet}
import scala.collection.mutable
import scala.reflect.runtime.universe.{TypeTag, typeOf}

object Crunch {
  val log: Logger = LoggerFactory.getLogger(getClass)

  case class FlightSplitMinute(flightId: Int, paxType: PaxType, terminalName: TerminalName, queueName: QueueName, paxLoad: Double, workLoad: Double, minute: MillisSinceEpoch)

  case class FlightSplitDiff(flightId: Int, paxType: PaxType, terminalName: TerminalName, queueName: QueueName, paxLoad: Double, workLoad: Double, minute: MillisSinceEpoch)

  case class LoadMinute(terminalName: TerminalName, queueName: QueueName, paxLoad: Double, workLoad: Double, minute: MillisSinceEpoch) extends TerminalQueueMinute {
    lazy val uniqueId: TQM = TQM(terminalName, queueName, minute)
  }

  object LoadMinute {
    def apply(cm: CrunchMinute): LoadMinute = LoadMinute(cm.terminalName, cm.queueName, cm.paxLoad, cm.workLoad, cm.minute)
  }

  case class Loads(loadMinutes: SortedMap[TQM, LoadMinute])

  object Loads {
    def apply(lms: Seq[LoadMinute]): Loads = Loads(SortedMap[TQM, LoadMinute]() ++ lms.map(cm => (TQM(cm.terminalName, cm.queueName, cm.minute), cm)))

    def fromCrunchMinutes(cms: SortedMap[TQM, CrunchMinute]): Loads = Loads(cms.mapValues(LoadMinute(_)))
  }

  case class RemoveCrunchMinute(terminalName: TerminalName, queueName: QueueName, minute: MillisSinceEpoch) {
    lazy val key: TQM = MinuteHelper.key(terminalName, queueName, minute)
  }

  case class CrunchRequest(flights: List[ApiFlightWithSplits], crunchStart: MillisSinceEpoch)

  val oneMinuteMillis: MillisSinceEpoch = 60000L
  val oneHourMillis: MillisSinceEpoch = oneMinuteMillis * 60
  val oneDayMillis: MillisSinceEpoch = oneHourMillis * 24
  val minutesInADay: Int = 60 * 24

  val europeLondonId = "Europe/London"
  val europeLondonTimeZone: DateTimeZone = DateTimeZone.forID(europeLondonId)

  def isInRangeOnDay(startDateTime: SDateLike, endDateTime: SDateLike)(minute: SDateLike): Boolean =
    startDateTime.millisSinceEpoch <= minute.millisSinceEpoch && minute.millisSinceEpoch <= endDateTime.millisSinceEpoch

  def midnightThisMorning: MillisSinceEpoch = {
    val localNow = SDate(new DateTime(europeLondonTimeZone).getMillis)
    val crunchStartDate = Crunch.getLocalLastMidnight(localNow.millisSinceEpoch).millisSinceEpoch
    crunchStartDate
  }

  def isDueLookup(arrival: ArrivalKey, lastLookupMillis: MillisSinceEpoch, now: SDateLike): Boolean = {
    val soonWithExpiredLookup = isWithinHours(arrival.scheduled, 48, now) && !wasWithinHours(lastLookupMillis, 24, now)
    val notSoonWithExpiredLookup = !isWithinHours(arrival.scheduled, 48, now) && !wasWithinHours(lastLookupMillis, 24 * 7, now)

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
      getLocalLastMidnight(minutes.minute - offsetMinutes * 60000).millisSinceEpoch
    })

  def getLocalLastMidnight(now: MilliDate): SDateLike = getLocalLastMidnight(now.millisSinceEpoch)

  def getLocalLastMidnight(now: SDateLike): SDateLike = getLocalLastMidnight(now.millisSinceEpoch)

  def getLocalLastMidnight(now: MillisSinceEpoch): SDateLike = {
    val localNow = SDate(now, europeLondonTimeZone)
    val localMidnight = s"${localNow.getFullYear()}-${localNow.getMonth()}-${localNow.getDate()}T00:00"
    SDate(localMidnight, europeLondonTimeZone)
  }

  def getLocalNextMidnight(now: MilliDate): SDateLike = getLocalNextMidnight(now.millisSinceEpoch)

  def getLocalNextMidnight(now: SDateLike): SDateLike = getLocalNextMidnight(now.millisSinceEpoch)

  def getLocalNextMidnight(now: MillisSinceEpoch): SDateLike = {
    val nextDay = getLocalLastMidnight(now).addDays(1)
    val localMidnight = s"${nextDay.getFullYear()}-${nextDay.getMonth()}-${nextDay.getDate()}T00:00"
    SDate(localMidnight, europeLondonTimeZone)
  }

  def minuteMillisFor24hours(dayMillis: MillisSinceEpoch): Seq[MillisSinceEpoch] =
    (0 until minutesInADay).map(m => dayMillis + (m * oneMinuteMillis))

  def missingMinutesForDay(fromMillis: MillisSinceEpoch, minuteExistsTerminals: (MillisSinceEpoch, List[TerminalName]) => Boolean, terminals: List[TerminalName], days: Int): Set[MillisSinceEpoch] = {
    val fromMillisMidnight = getLocalLastMidnight(fromMillis).millisSinceEpoch

    (0 until days).foldLeft(List[MillisSinceEpoch]()) {
      case (missingSoFar, day) =>
        val dayMillis = fromMillisMidnight + (day * Crunch.oneDayMillis)
        val isMissing = !minuteExistsTerminals(dayMillis, terminals)
        if (isMissing) Crunch.minuteMillisFor24hours(dayMillis) ++: missingSoFar
        else missingSoFar
    }.toSet
  }

  def filterNonMinuteBoundaryMillis(millis: List[MillisSinceEpoch]): List[MillisSinceEpoch] = millis.filter(_ % oneMinuteMillis == 0)

  def earliestAndLatestAffectedPcpTimeFromFlights(maxDays: Int)(existingFlights: Set[ApiFlightWithSplits], updatedFlights: Set[ApiFlightWithSplits]): Option[(SDateLike, SDateLike)] = {
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

  def purgeExpired[A <: WithTimeAccessor, B](expireable: mutable.SortedMap[A, B], now: () => SDateLike, expireAfter: Int): Unit = {
    val thresholdMillis = now().addMillis(-1 * expireAfter).millisSinceEpoch
    expireable
      .takeWhile { case (a: A, _) => a.timeValue < thresholdMillis }
      .foreach { case (a, _) => expireable.remove(a) }
  }

  def purgeExpired[A <: WithTimeAccessor](expireable: mutable.SortedSet[A], now: () => SDateLike, expireAfter: Int): Unit = {
    val thresholdMillis = now().addMillis(-1 * expireAfter).millisSinceEpoch
    expireable
      .takeWhile { a: A => a.timeValue < thresholdMillis }
      .foreach(a => expireable.remove(a))
  }

  def purgeExpired[A <: WithTimeAccessor, B](expireable: SortedMap[A, B], now: () => SDateLike, expireAfter: Int): SortedMap[A, B] = {
    val thresholdMillis = now().addMillis(-1 * expireAfter).millisSinceEpoch
    expireable.dropWhile { case (a: A, _) => a.timeValue < thresholdMillis }
  }

  def purgeExpired[A <: WithTimeAccessor](expireable: SortedSet[A], now: () => SDateLike, expireAfter: Int): SortedSet[A] = {
    val thresholdMillis = now().addMillis(-1 * expireAfter).millisSinceEpoch
    expireable.dropWhile {
      _.timeValue < thresholdMillis
    }
  }

  def purgeExpired[A: TypeTag](expireable: List[(MillisSinceEpoch, A)], now: () => SDateLike, expireAfter: MillisSinceEpoch): List[(MillisSinceEpoch, A)] = {
    val expired = hasExpiredForType(identity[MillisSinceEpoch], now, expireAfter)
    val updated = expireable.filterNot { case (i, _) => expired(i) }

    val numPurged = expireable.size - updated.size
    if (numPurged > 0) log.info(s"Purged $numPurged ${typeOf[A].toString}")

    updated
  }

  def purgeExpired[I, A: TypeTag](expireable: Map[I, A], timeAccessor: A => MillisSinceEpoch, now: () => SDateLike, expireAfter: MillisSinceEpoch): Map[I, A] = {
    val expired = hasExpiredForType(timeAccessor, now, expireAfter)
    val updated = expireable.filterNot { case (_, a) => expired(a) }

    val numPurged = expireable.size - updated.size
    if (numPurged > 0) log.info(s"Purged $numPurged ${typeOf[A].toString}")

    updated
  }

  def purgeExpired[I, A: TypeTag](expireable: mutable.Map[I, A], timeAccessor: A => MillisSinceEpoch, now: () => SDateLike, expireAfter: MillisSinceEpoch): Unit = {
    val expired = hasExpiredForType(timeAccessor, now, expireAfter)
    val sizeBefore = expireable.size
    expireable --= expireable.collect { case (idx, a) if expired(a) => idx }

    val numPurged = sizeBefore - expireable.size
    if (numPurged > 0) log.info(s"Purged $numPurged ${typeOf[A].toString}")
  }

  def purgeExpiredTuple[A: TypeTag, B](expireable: Map[A, B], timeAccessor: A => MillisSinceEpoch, now: () => SDateLike, expireAfter: MillisSinceEpoch): Map[A, B] = {
    val expired = hasExpiredForType(timeAccessor, now, expireAfter)
    val updated = expireable.filterNot { case (a, _) => expired(a) }

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

  def purgeExpired[A: TypeTag](expireable: Seq[A], timeAccessor: A => MillisSinceEpoch, now: () => SDateLike, expireAfter: MillisSinceEpoch): Seq[A] = {
    val expired = hasExpiredForType(timeAccessor, now, expireAfter)
    val updated = expireable.filterNot(expired)

    val numPurged = expireable.size - updated.size
    if (numPurged > 0) log.info(s"Purged $numPurged ${typeOf[A].toString}")

    updated
  }

  def hasExpiredForType[A](toMillis: A => MillisSinceEpoch, now: () => SDateLike, expireAfter: MillisSinceEpoch): A => Boolean = {
    Crunch.hasExpired[A](now(), expireAfter, toMillis)
  }

  def hasExpired[A](now: SDateLike, expireAfterMillis: Long, toMillis: A => MillisSinceEpoch)(toCompare: A): Boolean = {
    val ageInMillis = now.millisSinceEpoch - toMillis(toCompare)
    ageInMillis > expireAfterMillis
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
    val mergedFlights = ps1.flights ++ ps2.flights
    val mergedCrunchMinutes = ps1.crunchMinutes ++ ps2.crunchMinutes
    val mergedStaffMinutes = ps1.staffMinutes ++ ps2.staffMinutes
    PortState(mergedFlights, mergedCrunchMinutes, mergedStaffMinutes)
  }

  def combineArrivalsWithMaybeSplits(as1: Seq[(Arrival, Option[Splits])], as2: Seq[(Arrival, Option[Splits])]): Seq[(Arrival, Option[Splits])] = {
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

  def mergeLoadsIntoQueue(incomingLoads: Loads, existingQueue: mutable.SortedMap[MilliDate, Loads], crunchPeriodStartMillis: SDateLike => SDateLike): Unit = {
    val changedDays: Map[MillisSinceEpoch, SortedMap[TQM, LoadMinute]] = incomingLoads.loadMinutes
      .groupBy { case (_, sm) =>
        crunchPeriodStartMillis(SDate(sm.minute, europeLondonTimeZone)).millisSinceEpoch
      }

    changedDays.foreach {
      case (dayStartMillis, newLoadsForDay) =>
        val milliDate = MilliDate(dayStartMillis)
        val existingLoadsForDay = existingQueue.get(milliDate)
        val mergedDayMinutes = mergeUpdatedLoads(existingLoadsForDay, newLoadsForDay)
        existingQueue += (milliDate -> Loads(mergedDayMinutes))
    }
  }

  def mergeUpdatedLoads(maybeExistingDayLoads: Option[Loads], dayLoadMinutes: SortedMap[TQM, LoadMinute]): SortedMap[TQM, LoadMinute] = {
    maybeExistingDayLoads match {
      case None => dayLoadMinutes
      case Some(existingDayLoads) =>
        dayLoadMinutes
          .foldLeft(existingDayLoads.loadMinutes) {
            case (daySoFar, (_, loadMinute)) => daySoFar.updated(loadMinute.uniqueId, loadMinute)
          }
    }
  }

  def movementsUpdateCriteria(existingMovements: Set[StaffMovement], incomingMovements: Seq[StaffMovement]): UpdateCriteria = {
    val updatedMovements = incomingMovements.toSet -- existingMovements
    val deletedMovements = existingMovements -- incomingMovements.toSet
    val affectedMovements = updatedMovements ++ deletedMovements
    log.info(s"affected movements: $affectedMovements")
    val minutesToUpdate = allMinuteMillis(affectedMovements.toSeq)
    val terminalsToUpdate = affectedMovements.map(_.terminalName)

    UpdateCriteria(minutesToUpdate, terminalsToUpdate)
  }

  def allMinuteMillis(movements: Seq[StaffMovement]): Seq[MillisSinceEpoch] = {
    movements
      .groupBy(_.uUID)
      .flatMap {
        case (_, mmPair) =>
          val startMillis = mmPair.map(_.time.millisSinceEpoch).min
          val endMillis = mmPair.map(_.time.millisSinceEpoch).max
          startMillis until endMillis by 60000
      }
      .toSeq
  }
}
