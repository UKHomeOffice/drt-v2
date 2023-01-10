package drt.shared

import drt.shared.CrunchApi._
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, UniqueArrival}
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.SDateLike
import upickle.default.{readwriter, ReadWriter => RW}

import scala.collection.immutable.{Map => IMap, SortedMap => ISortedMap}
import scala.collection.{Map, SortedMap}


case class PortState(flights: IMap[UniqueArrival, ApiFlightWithSplits],
                     crunchMinutes: ISortedMap[TQM, CrunchMinute],
                     staffMinutes: ISortedMap[TM, StaffMinute]) extends PortStateLike {
  def window(start: SDateLike, end: SDateLike): PortState = {
    val roundedStart = start.roundToMinute()
    val roundedEnd = end.roundToMinute()

    val fs = flightsRange(roundedStart, roundedEnd)
    val cms = crunchMinuteRange(roundedStart.millisSinceEpoch, roundedEnd.millisSinceEpoch)
    val sms = staffMinuteRange(roundedStart.millisSinceEpoch, roundedEnd.millisSinceEpoch)

    PortState(flights = fs, crunchMinutes = cms, staffMinutes = sms)
  }

  def windowWithTerminalFilter(start: SDateLike, end: SDateLike, portQueues: IMap[Terminal, Seq[Queue]]): PortState = {
    val roundedStart = start.roundToMinute()
    val roundedEnd = end.roundToMinute()

    val fs = flightsRangeWithTerminals(roundedStart, roundedEnd, portQueues)
    val cms = crunchMinuteRangeWithTerminals(roundedStart.millisSinceEpoch, roundedEnd.millisSinceEpoch, portQueues)
    val sms = staffMinuteRangeWithTerminals(roundedStart.millisSinceEpoch, roundedEnd.millisSinceEpoch, portQueues.keys.toSeq)

    PortState(flights = fs, crunchMinutes = cms, staffMinutes = sms)
  }

  def flightsRange(roundedStart: SDateLike, roundedEnd: SDateLike): ISortedMap[UniqueArrival, ApiFlightWithSplits]
  = ISortedMap[UniqueArrival, ApiFlightWithSplits]() ++ flights
    .filter {
      case (_, f) => f.apiFlight.isRelevantToPeriod(roundedStart, roundedEnd)
    }

  def flightsRangeWithTerminals(roundedStart: SDateLike, roundedEnd: SDateLike, portQueues: IMap[Terminal, Seq[Queue]]): ISortedMap[UniqueArrival, ApiFlightWithSplits] = flightsRange(roundedStart, roundedEnd)
    .filter { case (_, fws) => portQueues.contains(fws.apiFlight.Terminal) }

  def crunchMinuteRange(startMillis: MillisSinceEpoch, endMillis: MillisSinceEpoch): ISortedMap[TQM, CrunchMinute] = crunchMinutes
    .range(TQM.atTime(startMillis), TQM.atTime(endMillis))

  def crunchMinuteRangeWithTerminals(startMillis: MillisSinceEpoch, endMillis: MillisSinceEpoch, portQueues: IMap[Terminal, Seq[Queue]]): ISortedMap[TQM, CrunchMinute] =
    ISortedMap[TQM, CrunchMinute]() ++ crunchMinuteRange(startMillis, endMillis)
      .filterKeys { tqm => portQueues.contains(tqm.terminal) && portQueues(tqm.terminal).contains(tqm.queue) }

  def staffMinuteRange(startMillis: MillisSinceEpoch, endMillis: MillisSinceEpoch): ISortedMap[TM, StaffMinute] = staffMinutes
    .range(TM.atTime(startMillis), TM.atTime(endMillis))

  def staffMinuteRangeWithTerminals(startMillis: MillisSinceEpoch, endMillis: MillisSinceEpoch, terminals: Seq[Terminal]): ISortedMap[TM, StaffMinute] =
    ISortedMap[TM, StaffMinute]() ++ staffMinuteRange(startMillis, endMillis)
      .filterKeys { tm => terminals.contains(tm.terminal) }

  def crunchSummary(start: SDateLike, periods: Long, periodSize: Long, terminal: Terminal, queues: List[Queue]): ISortedMap[Long, IMap[Queue, CrunchMinute]] = {
    val startMillis = start.roundToMinute().millisSinceEpoch
    val endMillis = startMillis + (periods * periodSize * 60000)
    val periodMillis = periodSize * 60000
    ISortedMap[Long, IMap[Queue, CrunchMinute]]() ++ (startMillis until endMillis by periodMillis)
      .map { periodStart =>
        val queueMinutes = queues
          .map { queue =>
            val periodEnd = periodStart + periodMillis
            val slotMinutes = (periodStart until periodEnd by 60000)
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

  def dailyCrunchSummary(start: SDateLike, days: Int, terminal: Terminal, queues: List[Queue]): ISortedMap[Long, IMap[Queue, CrunchMinute]] =
    ISortedMap[Long, IMap[Queue, CrunchMinute]]() ++ (0 until days)
      .map { day =>
        val dayStart = start.addDays(day)
        val dayEnd = dayStart.addDays(1)
        val queueMinutes = queues
          .map { queue =>
            val slotMinutes = (dayStart.millisSinceEpoch until dayEnd.millisSinceEpoch by 60000)
              .map { minute => crunchMinutes.get(TQM(terminal, queue, minute)) }
              .collect { case Some(cm) => cm }
              .toList
            (queue, crunchPeriodSummary(terminal, dayStart.millisSinceEpoch, queue, slotMinutes))
          }
          .toMap
        (dayStart.millisSinceEpoch, queueMinutes)
      }
      .toMap

  def staffSummary(start: SDateLike, periods: Long, periodSize: Long, terminal: Terminal): ISortedMap[Long, StaffMinute] = {
    val startMillis = start.roundToMinute().millisSinceEpoch
    val endMillis = startMillis + (periods * periodSize * 60000)
    val periodMillis = periodSize * 60000
    ISortedMap[Long, StaffMinute]() ++ (startMillis until endMillis by periodMillis)
      .map { periodStart =>
        val periodEnd = periodStart + periodMillis
        val slotMinutes = (periodStart until periodEnd by 60000)
          .map { minute => staffMinutes.get(TM(terminal, minute)) }
          .collect { case Some(sm) => sm }
          .toList
        (periodStart, staffPeriodSummary(terminal, periodStart, slotMinutes))
      }
      .toMap
  }

  def crunchPeriodSummary(terminal: Terminal, periodStart: MillisSinceEpoch, queue: Queue, slotMinutes: List[CrunchMinute]): CrunchMinute = {
    if (slotMinutes.nonEmpty) CrunchMinute(
      terminal = terminal,
      queue = queue,
      minute = periodStart,
      paxLoad = slotMinutes.map(_.paxLoad).sum,
      workLoad = slotMinutes.map(_.workLoad).sum,
      deskRec = slotMinutes.map(_.deskRec).max,
      waitTime = slotMinutes.map(_.waitTime).max,
      maybePaxInQueue = slotMinutes.map(_.maybePaxInQueue).max,
      deployedDesks = if (slotMinutes.exists(cm => cm.deployedDesks.isDefined))
        Option(slotMinutes.map(_.deployedDesks.getOrElse(0)).max)
      else
        None,
      deployedWait = if (slotMinutes.exists(cm => cm.deployedWait.isDefined))
        Option(slotMinutes.map(_.deployedWait.getOrElse(0)).max)
      else
        None,
      maybeDeployedPaxInQueue = if (slotMinutes.exists(cm => cm.maybeDeployedPaxInQueue.isDefined))
        Option(slotMinutes.map(_.maybeDeployedPaxInQueue.getOrElse(0)).max)
      else
        None,
      actDesks = if (slotMinutes.exists(cm => cm.actDesks.isDefined))
        Option(slotMinutes.map(_.actDesks.getOrElse(0)).max)
      else
        None,
      actWait = if (slotMinutes.exists(cm => cm.actWait.isDefined))
        Option(slotMinutes.map(_.actWait.getOrElse(0)).max)
      else
        None
    )
    else CrunchMinute(
      terminal = terminal,
      queue = queue,
      minute = periodStart,
      paxLoad = 0,
      workLoad = 0,
      deskRec = 0,
      waitTime = 0,
      maybePaxInQueue = None,
      deployedDesks = None,
      deployedWait = None,
      maybeDeployedPaxInQueue = None,
      actDesks = None,
      actWait = None)
  }

  def staffPeriodSummary(terminal: Terminal, periodStart: MillisSinceEpoch, slotMinutes: List[StaffMinute]): StaffMinute = {
    if (slotMinutes.nonEmpty) StaffMinute(
      terminal = terminal,
      minute = periodStart,
      shifts = slotMinutes.map(_.shifts).min,
      fixedPoints = slotMinutes.map(_.fixedPoints).max,
      movements = slotMinutes.map(_.movements).max)
    else StaffMinute(
      terminal = terminal,
      minute = periodStart,
      shifts = 0,
      fixedPoints = 0,
      movements = 0)
  }
}

object PortState {
  implicit val rw: RW[PortState] =
    readwriter[(IMap[UniqueArrival, ApiFlightWithSplits], IMap[TQM, CrunchMinute], IMap[TM, StaffMinute])]
      .bimap[PortState](ps => portStateToTuple(ps), t => tupleToPortState(t))

  private def tupleToPortState(t: (IMap[UniqueArrival, ApiFlightWithSplits], IMap[TQM, CrunchMinute], IMap[TM, StaffMinute])): PortState = {
    PortState(ISortedMap[UniqueArrival, ApiFlightWithSplits]() ++ t._1, ISortedMap[TQM, CrunchMinute]() ++ t._2, ISortedMap[TM, StaffMinute]() ++ t._3)
  }

  private def portStateToTuple(ps: PortState): (IMap[UniqueArrival, ApiFlightWithSplits], ISortedMap[TQM, CrunchMinute], ISortedMap[TM, StaffMinute]) = {
    (ps.flights, ps.crunchMinutes, ps.staffMinutes)
  }

  def apply(flightsWithSplits: Iterable[ApiFlightWithSplits], crunchMinutes: Iterable[CrunchMinute], staffMinutes: Iterable[StaffMinute]): PortState = {
    val flights = ISortedMap[UniqueArrival, ApiFlightWithSplits]() ++ flightsWithSplits.map(fws => (fws.apiFlight.unique, fws))
    val cms = ISortedMap[TQM, CrunchMinute]() ++ crunchMinutes.map(cm => (TQM(cm), cm))
    val sms = ISortedMap[TM, StaffMinute]() ++ staffMinutes.map(sm => (TM(sm), sm))
    PortState(flights, cms, sms)
  }

  val empty: PortState = PortState(ISortedMap[UniqueArrival, ApiFlightWithSplits](), ISortedMap[TQM, CrunchMinute](), ISortedMap[TM, StaffMinute]())
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

  def window(start: SDateLike, end: SDateLike): PortState

  def windowWithTerminalFilter(start: SDateLike, end: SDateLike, portQueues: IMap[Terminal, Seq[Queue]]): PortState
}
