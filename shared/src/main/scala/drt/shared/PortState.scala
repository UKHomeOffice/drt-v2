package drt.shared

import drt.shared.CrunchApi._
import drt.shared.FlightsApi.{QueueName, TerminalName}
import upickle.default.{readwriter, ReadWriter => RW}

import scala.collection.immutable.{Map => IMap, SortedMap => ISortedMap}
import scala.collection.{Map, SortedMap}

case class PortState(flights: ISortedMap[UniqueArrival, ApiFlightWithSplits],
                     crunchMinutes: ISortedMap[TQM, CrunchMinute],
                     staffMinutes: ISortedMap[TM, StaffMinute]) extends PortStateLike {
  def window(start: SDateLike, end: SDateLike, portQueues: IMap[TerminalName, Seq[QueueName]]): PortState = {
    val roundedStart = start.roundToMinute()
    val roundedEnd = end.roundToMinute()

    val fs = flightsRange(roundedStart, roundedEnd)
    val cms = crunchMinuteRange(roundedStart.millisSinceEpoch, roundedEnd.millisSinceEpoch)
    val sms = staffMinuteRange(roundedStart.millisSinceEpoch, roundedEnd.millisSinceEpoch)

    PortState(flights = fs, crunchMinutes = cms, staffMinutes = sms)
  }

  def windowWithTerminalFilter(start: SDateLike, end: SDateLike, portQueues: IMap[TerminalName, Seq[QueueName]]): PortState = {
    val roundedStart = start.roundToMinute()
    val roundedEnd = end.roundToMinute()

    val fs = flightsRangeWithTerminals(roundedStart, roundedEnd, portQueues)
    val cms = crunchMinuteRangeWithTerminals(roundedStart.millisSinceEpoch, roundedEnd.millisSinceEpoch, portQueues)
    val sms = staffMinuteRangeWithTerminals(roundedStart.millisSinceEpoch, roundedEnd.millisSinceEpoch, portQueues.keys.toSeq)

    PortState(flights = fs, crunchMinutes = cms, staffMinutes = sms)
  }

  def purgeOlderThanDate(thresholdMillis: MillisSinceEpoch): PortState = PortState(
    purgeExpired(flights, UniqueArrival.atTime, thresholdMillis),
    purgeExpired(crunchMinutes, TQM.atTime, thresholdMillis),
    purgeExpired(staffMinutes, TM.atTime, thresholdMillis)
  )

  def purgeExpired[A <: WithTimeAccessor, B](expireable: ISortedMap[A, B], atTime: MillisSinceEpoch => A, thresholdMillis: MillisSinceEpoch): ISortedMap[A, B] = {
    expireable -- expireable.range(atTime(0L), atTime(thresholdMillis - 1)).keys
  }

  def flightsRange(roundedStart: SDateLike, roundedEnd: SDateLike): ISortedMap[UniqueArrival, ApiFlightWithSplits] = ISortedMap[UniqueArrival, ApiFlightWithSplits]() ++ flights
    .range(UniqueArrival.atTime(roundedStart.millisSinceEpoch), UniqueArrival.atTime(roundedEnd.millisSinceEpoch))

  def flightsRangeWithTerminals(roundedStart: SDateLike, roundedEnd: SDateLike, portQueues: IMap[TerminalName, Seq[QueueName]]): ISortedMap[UniqueArrival, ApiFlightWithSplits] = flightsRange(roundedStart, roundedEnd)
    .filter { case (_, fws) => portQueues.contains(fws.apiFlight.Terminal) }

  def crunchMinuteRange(startMillis: MillisSinceEpoch, endMillis: MillisSinceEpoch): ISortedMap[TQM, CrunchMinute] = crunchMinutes
    .range(TQM.atTime(startMillis), TQM.atTime(endMillis))

  def crunchMinuteRangeWithTerminals(startMillis: MillisSinceEpoch, endMillis: MillisSinceEpoch, portQueues: IMap[TerminalName, Seq[QueueName]]): ISortedMap[TQM, CrunchMinute] =
    crunchMinuteRange(startMillis, endMillis)
      .filterKeys { tqm => portQueues.contains(tqm.terminalName) && portQueues(tqm.terminalName).contains(tqm.queueName) }

  def staffMinuteRange(startMillis: MillisSinceEpoch, endMillis: MillisSinceEpoch): ISortedMap[TM, StaffMinute] = staffMinutes
    .range(TM.atTime(startMillis), TM.atTime(endMillis))

  def staffMinuteRangeWithTerminals(startMillis: MillisSinceEpoch, endMillis: MillisSinceEpoch, terminals: Seq[TerminalName]): ISortedMap[TM, StaffMinute] =
    staffMinuteRange(startMillis, endMillis)
      .filterKeys { tm => terminals.contains(tm.terminalName) }

  def crunchSummary(start: SDateLike, periods: Long, periodSize: Long, terminal: TerminalName, queues: List[String]): ISortedMap[Long, IMap[String, CrunchMinute]] = {
    val startMillis = start.roundToMinute().millisSinceEpoch
    val endMillis = startMillis + (periods * periodSize * 60000)
    val periodMillis = periodSize * 60000
    ISortedMap[Long, IMap[String, CrunchMinute]]() ++ (startMillis until endMillis by periodMillis)
      .map { periodStart =>
        val queueMinutes = queues
          .map { queue =>
            val slotMinutes = (periodStart until (periodStart + periodMillis) by 60000)
              .map { minute => crunchMinutes.get(TQM(terminal, queue, minute)) }
              .collect { case Some(cm) => cm }
              .toList
            (queue, crunchPeriodSummary(terminal, periodStart, queue, slotMinutes))
          }
          .toMap
        (periodStart, queueMinutes)
      }
      .toMap
  }

  def staffSummary(start: SDateLike, periods: Long, periodSize: Long, terminal: TerminalName): ISortedMap[Long, StaffMinute] = {
    val startMillis = start.roundToMinute().millisSinceEpoch
    val endMillis = startMillis + (periods * periodSize * 60000)
    val periodMillis = periodSize * 60000
    ISortedMap[Long, StaffMinute]() ++ (startMillis until endMillis by periodMillis)
      .map { periodStart =>
        val periodEnd = periodStart + periodMillis
        val slotMinutes = (periodStart until periodEnd by 60000)
          .map { minute => staffMinutes.get(TM(terminal, minute)) }
          .collect { case Some(cm) => cm }
          .toList
        val terminalMinutes = staffPeriodSummary(terminal, periodStart, slotMinutes)
        (periodStart, terminalMinutes)
      }
      .toMap
  }

  def crunchPeriodSummary(terminal: String, periodStart: MillisSinceEpoch, queue: String, slotMinutes: List[CrunchMinute]): CrunchMinute = {
    if (slotMinutes.nonEmpty) CrunchMinute(
      terminalName = terminal,
      queueName = queue,
      minute = periodStart,
      paxLoad = slotMinutes.map(_.paxLoad).sum,
      workLoad = slotMinutes.map(_.workLoad).sum,
      deskRec = slotMinutes.map(_.deskRec).max,
      waitTime = slotMinutes.map(_.waitTime).max,
      deployedDesks = if (slotMinutes.exists(cm => cm.deployedDesks.isDefined)) Option(slotMinutes.map(_.deployedDesks.getOrElse(0)).max) else None,
      deployedWait = if (slotMinutes.exists(cm => cm.deployedWait.isDefined)) Option(slotMinutes.map(_.deployedWait.getOrElse(0)).max) else None,
      actDesks = if (slotMinutes.exists(cm => cm.actDesks.isDefined)) Option(slotMinutes.map(_.actDesks.getOrElse(0)).max) else None,
      actWait = if (slotMinutes.exists(cm => cm.actWait.isDefined)) Option(slotMinutes.map(_.actWait.getOrElse(0)).max) else None)
    else CrunchMinute(
      terminalName = terminal,
      queueName = queue,
      minute = periodStart,
      paxLoad = 0,
      workLoad = 0,
      deskRec = 0,
      waitTime = 0,
      deployedDesks = None,
      deployedWait = None,
      actDesks = None,
      actWait = None)
  }

  def staffPeriodSummary(terminal: String, periodStart: MillisSinceEpoch, slotMinutes: List[StaffMinute]): StaffMinute = {
    if (slotMinutes.nonEmpty) StaffMinute(
      terminalName = terminal,
      minute = periodStart,
      shifts = slotMinutes.map(_.shifts).min,
      fixedPoints = slotMinutes.map(_.fixedPoints).max,
      movements = slotMinutes.map(_.movements).max)
    else StaffMinute(
      terminalName = terminal,
      minute = periodStart,
      shifts = 0,
      fixedPoints = 0,
      movements = 0)
  }

  def mutable: PortStateMutable = {
    val ps = new PortStateMutable
    ps.flights ++= SortedMap[UniqueArrival, ApiFlightWithSplits]() ++ flights.map { case (_, fws) => (fws.apiFlight.unique, fws) }
    ps.crunchMinutes ++= crunchMinutes
    ps.staffMinutes ++= staffMinutes
    ps
  }
}

sealed trait PortStateLike {
  val flights: Map[UniqueArrival, ApiFlightWithSplits]
  val crunchMinutes: SortedMap[TQM, CrunchMinute]
  val staffMinutes: SortedMap[TM, StaffMinute]

  lazy val latestUpdate: MillisSinceEpoch = {
    val latestFlights = if (flights.nonEmpty) flights.map(_._2.lastUpdated.getOrElse(0L)).max else 0L
    val latestCrunch = if (crunchMinutes.nonEmpty) crunchMinutes.map(_._2.lastUpdated.getOrElse(0L)).max else 0L
    val latestStaff = if (staffMinutes.nonEmpty) staffMinutes.map(_._2.lastUpdated.getOrElse(0L)).max else 0L
    List(latestFlights, latestCrunch, latestStaff).max
  }

  def window(start: SDateLike, end: SDateLike, portQueues: IMap[TerminalName, Seq[QueueName]]): PortState

  def windowWithTerminalFilter(start: SDateLike, end: SDateLike, portQueues: IMap[TerminalName, Seq[QueueName]]): PortState
}


class PortStateMutable {
  val flights = new IndexedFlights
  val crunchMinutes = new IndexedCrunchMinutes
  val staffMinutes = new IndexedStaffMinutes

  def window(start: SDateLike, end: SDateLike): PortState = {
    val roundedStart = start.roundToMinute()
    val roundedEnd = end.roundToMinute()

    val fs = flightsRange(roundedStart, roundedEnd)
    val cms = crunchMinuteRange(roundedStart, roundedEnd)
    val sms = staffMinuteRange(roundedStart, roundedEnd)

    PortState(flights = fs, crunchMinutes = cms, staffMinutes = sms)
  }

  def windowWithTerminalFilter(start: SDateLike, end: SDateLike, terminals: Seq[TerminalName]): PortState = {
    val roundedStart = start.roundToMinute()
    val roundedEnd = end.roundToMinute()

    val fs = flightsRangeWithTerminals(roundedStart, roundedEnd, terminals)
    val cms = crunchMinuteRangeWithTerminals(roundedStart, roundedEnd, terminals)
    val sms = staffMinuteRangeWithTerminals(roundedStart, roundedEnd, terminals)

    PortState(flights = fs, crunchMinutes = cms, staffMinutes = sms)
  }

  def flightsRange(start: SDateLike, end: SDateLike): ISortedMap[UniqueArrival, ApiFlightWithSplits] = flights.range(start, end)

  private def filterByPcp(flightsToFilter: ISortedMap[UniqueArrival, ApiFlightWithSplits], start: SDateLike, end: SDateLike): ISortedMap[UniqueArrival, ApiFlightWithSplits] =
    flightsToFilter.filterNot {
      case (_, ApiFlightWithSplits(arrival, _, _)) =>
        val firstPcpMin = arrival.pcpRange().min
        val lastPcpMin = arrival.pcpRange().max
        val startMillis = start.millisSinceEpoch
        val endMillis = end.millisSinceEpoch
        firstPcpMin > endMillis || lastPcpMin < startMillis
    }

  def flightsRangeWithTerminals(roundedStart: SDateLike, roundedEnd: SDateLike, terminals: Seq[TerminalName]): ISortedMap[UniqueArrival, ApiFlightWithSplits] =
    flights.rangeAtTerminals(roundedStart, roundedEnd, terminals)

  def purgeOlderThanDate(thresholdMillis: MillisSinceEpoch): Unit = {
    flights.purgeDataBefore(thresholdMillis)
    crunchMinutes.purgeDataBefore(thresholdMillis)
    staffMinutes.purgeDataBefore(thresholdMillis)
  }

  def purgeRecentUpdates(thresholdMillis: MillisSinceEpoch): Unit = {
    flights.purgeCacheBefore(thresholdMillis)
    crunchMinutes.purgeCacheBefore(thresholdMillis)
    staffMinutes.purgeCacheBefore(thresholdMillis)
  }

  def crunchMinuteRange(start: SDateLike, end: SDateLike): ISortedMap[TQM, CrunchMinute] = crunchMinutes.range(start, end)

  def crunchMinuteRangeWithTerminals(start: SDateLike, end: SDateLike, terminals: Seq[TerminalName]): ISortedMap[TQM, CrunchMinute] =
    crunchMinutes.rangeAtTerminals(start, end, terminals)

  def staffMinuteRange(start: SDateLike, end: SDateLike): ISortedMap[TM, StaffMinute] = staffMinutes.range(start, end)

  def staffMinuteRangeWithTerminals(start: SDateLike, end: SDateLike, terminals: Seq[TerminalName]): ISortedMap[TM, StaffMinute] =
    staffMinutes.rangeAtTerminals(start, end, terminals)

  def updates(sinceEpoch: MillisSinceEpoch, start: MillisSinceEpoch, end: MillisSinceEpoch): Option[PortStateUpdates] = {
    val updatedFlights = flights.updatesSince(sinceEpoch).filter(fws => start <= fws.apiFlight.PcpTime.getOrElse(0L) && fws.apiFlight.PcpTime.getOrElse(0L) < end)
    val updatedCrunch: Set[CrunchMinute] = crunchMinutes.updatesSince(sinceEpoch).filter(m => start <= m.minute && m.minute < end)
    val updatedStaff = staffMinutes.updatesSince(sinceEpoch).filter(m => start <= m.minute && m.minute < end)

    if (updatedFlights.nonEmpty || updatedCrunch.nonEmpty || updatedStaff.nonEmpty) {
      val updateTimes = updatedFlights.map(_.lastUpdated.getOrElse(0L)) ++ updatedCrunch.map(_.lastUpdated.getOrElse(0L)) ++ updatedStaff.map(_.lastUpdated.getOrElse(0L))
      val latestUpdate = updateTimes.max
      val allUpdates = PortStateUpdates(latestUpdate, updatedFlights, updatedCrunch, updatedStaff)
      Option(allUpdates)
    } else None
  }

  def applyFlightsWithSplitsDiff(flightRemovals: Seq[UniqueArrival], flightUpdates: Seq[ApiFlightWithSplits], nowMillis: MillisSinceEpoch): Unit = {
    flights --= flightRemovals
    flights ++= flightUpdates.map(f => (f.apiFlight.unique, f.copy(lastUpdated = Option(nowMillis))))
  }

  def applyCrunchDiff(crunchMinuteUpdates: Seq[CrunchMinute], nowMillis: MillisSinceEpoch): Unit = {
    crunchMinutes ++= crunchMinuteUpdates.map(cm => (cm.key, cm.copy(lastUpdated = Option(nowMillis))))
  }

  def applyStaffDiff(staffMinuteUpdates: Seq[StaffMinute], nowMillis: MillisSinceEpoch): Unit = {
    staffMinutes ++= staffMinuteUpdates.map(sm => (sm.key, sm.copy(lastUpdated = Option(nowMillis))))
  }

  def immutable: PortState = {
    val fs = ISortedMap[UniqueArrival, ApiFlightWithSplits]() ++ flights.all
    val cms = ISortedMap[TQM, CrunchMinute]() ++ crunchMinutes.all
    val sms = ISortedMap[TM, StaffMinute]() ++ staffMinutes.all
    PortState(fs, cms, sms)
  }

  def clear(): Unit = {
    flights.clear()
    crunchMinutes.clear()
    staffMinutes.clear()
  }
}

object PortState {
  implicit val rw: RW[PortState] =
    readwriter[(IMap[UniqueArrival, ApiFlightWithSplits], IMap[TQM, CrunchMinute], IMap[TM, StaffMinute])]
      .bimap[PortState](ps => portStateToTuple(ps), t => tupleToPortState(t))

  private def tupleToPortState(t: (IMap[UniqueArrival, ApiFlightWithSplits], IMap[TQM, CrunchMinute], IMap[TM, StaffMinute])): PortState = {
    PortState(ISortedMap[UniqueArrival, ApiFlightWithSplits]() ++ t._1, ISortedMap[TQM, CrunchMinute]() ++ t._2, ISortedMap[TM, StaffMinute]() ++ t._3)
  }

  private def portStateToTuple(ps: PortState): (ISortedMap[UniqueArrival, ApiFlightWithSplits], ISortedMap[TQM, CrunchMinute], ISortedMap[TM, StaffMinute]) = {
    (ps.flights, ps.crunchMinutes, ps.staffMinutes)
  }

  def apply(flightsWithSplits: List[ApiFlightWithSplits], crunchMinutes: List[CrunchMinute], staffMinutes: List[StaffMinute]): PortState = {
    val flights = ISortedMap[UniqueArrival, ApiFlightWithSplits]() ++ flightsWithSplits.map(fws => (fws.apiFlight.unique, fws))
    val cms = ISortedMap[TQM, CrunchMinute]() ++ crunchMinutes.map(cm => (TQM(cm), cm))
    val sms = ISortedMap[TM, StaffMinute]() ++ staffMinutes.map(sm => (TM(sm), sm))
    PortState(flights, cms, sms)
  }

  val empty: PortState = PortState(ISortedMap[UniqueArrival, ApiFlightWithSplits](), ISortedMap[TQM, CrunchMinute](), ISortedMap[TM, StaffMinute]())
}

object PortStateMutable {
  def empty: PortStateMutable = new PortStateMutable
}

