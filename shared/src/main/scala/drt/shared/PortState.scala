package drt.shared

import drt.shared.CrunchApi._
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, UniqueArrival}
import uk.gov.homeoffice.drt.models.{CrunchMinute, TQM}
import uk.gov.homeoffice.drt.ports.FeedSource
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.MilliTimes.oneMinuteMillis
import uk.gov.homeoffice.drt.time.{LocalDate, SDateLike}
import upickle.default.{readwriter, ReadWriter => RW}

import scala.collection.immutable.{Map => IMap, SortedMap => ISortedMap}
import scala.collection.{Map, SortedMap}
import scala.concurrent.duration.DurationInt


case class PortState(flights: IMap[UniqueArrival, ApiFlightWithSplits],
                     crunchMinutes: ISortedMap[TQM, CrunchMinute],
                     staffMinutes: ISortedMap[TM, StaffMinute]) extends PortStateLike {
  def window(start: SDateLike, end: SDateLike, sourceOrderPreference: List[FeedSource]): PortState = {
    val roundedStart = start.roundToMinute()
    val roundedEnd = end.roundToMinute()

    val fs = flightsRange(roundedStart, roundedEnd, sourceOrderPreference)
    val cms = crunchMinuteRange(roundedStart.millisSinceEpoch, roundedEnd.millisSinceEpoch)
    val sms = staffMinuteRange(roundedStart.millisSinceEpoch, roundedEnd.millisSinceEpoch)

    PortState(flights = fs, crunchMinutes = cms, staffMinutes = sms)
  }

  def windowWithTerminalFilter(start: SDateLike,
                               end: SDateLike,
                               terminals: (LocalDate, LocalDate) => Seq[Terminal],
                               queues: (LocalDate, LocalDate, Terminal) => Seq[Queue],
                               sourceOrderPreference: List[FeedSource],
                              ): PortState = {
    val roundedStart = start.roundToMinute()
    val roundedEnd = end.roundToMinute()

    val fs = flightsRangeWithTerminals(roundedStart, roundedEnd, terminals, sourceOrderPreference)
    val cms = crunchMinuteRangeWithTerminals(roundedStart, roundedEnd, queues)
    val sms = staffMinuteRangeWithTerminals(roundedStart, roundedEnd, terminals)

    PortState(flights = fs, crunchMinutes = cms, staffMinutes = sms)
  }

  def flightsRange(roundedStart: SDateLike, roundedEnd: SDateLike, sourceOrderPreference: List[FeedSource]): ISortedMap[UniqueArrival, ApiFlightWithSplits]
  = ISortedMap[UniqueArrival, ApiFlightWithSplits]() ++ flights
    .filter {
      case (_, f) => f.apiFlight.isRelevantToPeriod(roundedStart, roundedEnd, sourceOrderPreference)
    }

  def flightsRangeWithTerminals(roundedStart: SDateLike,
                                roundedEnd: SDateLike,
                                terminals: (LocalDate, LocalDate) => Seq[Terminal],
                                sourceOrderPreference: List[FeedSource],
                               ): ISortedMap[UniqueArrival, ApiFlightWithSplits] =
    flightsRange(roundedStart, roundedEnd, sourceOrderPreference)
      .filter { case (_, fws) => terminals(roundedStart.toLocalDate, roundedEnd.toLocalDate).contains(fws.apiFlight.Terminal) }

  def crunchMinuteRange(startMillis: MillisSinceEpoch, endMillis: MillisSinceEpoch): ISortedMap[TQM, CrunchMinute] = crunchMinutes
    .range(TQM.atTime(startMillis), TQM.atTime(endMillis))

  def crunchMinuteRangeWithTerminals(start: SDateLike,
                                     end: SDateLike,
                                     portQueues: (LocalDate, LocalDate, Terminal) => Seq[Queue],
                                    ): ISortedMap[TQM, CrunchMinute] =
    ISortedMap[TQM, CrunchMinute]() ++ crunchMinuteRange(start.millisSinceEpoch, end.millisSinceEpoch)
      .filterKeys { tqm => portQueues(start.toLocalDate, end.toLocalDate, tqm.terminal).contains(tqm.queue) }

  def staffMinuteRange(startMillis: MillisSinceEpoch, endMillis: MillisSinceEpoch): ISortedMap[TM, StaffMinute] = staffMinutes
    .range(TM.atTime(startMillis), TM.atTime(endMillis))

  def staffMinuteRangeWithTerminals(start: SDateLike,
                                    end: SDateLike,
                                    terminals: (LocalDate, LocalDate) => Seq[Terminal],
                                   ): ISortedMap[TM, StaffMinute] =
    ISortedMap[TM, StaffMinute]() ++ staffMinuteRange(start.millisSinceEpoch, end.millisSinceEpoch)
      .filterKeys { tm => terminals(start.toLocalDate, end.toLocalDate).contains(tm.terminal) }

  def crunchSummary(start: SDateLike, periods: Int, periodMinutes: Int, terminal: Terminal, queues: Seq[Queue]): ISortedMap[Long, IMap[Queue, CrunchMinute]] = {
    val startMillis = start.roundToMinute().millisSinceEpoch
    val endMillis = startMillis + (periods * periodMinutes.minutes).toMillis
    val periodMillis = periodMinutes.minutes.toMillis
    ISortedMap[Long, IMap[Queue, CrunchMinute]]() ++ (startMillis until endMillis by periodMillis)
      .map { periodStart =>
        val queueMinutes = queues
          .map { queue =>
            val periodEnd = periodStart + periodMillis
            val slotMinutes = (periodStart until periodEnd by oneMinuteMillis)
              .map { minute => crunchMinutes.get(TQM(terminal, queue, minute)) }
              .collect { case Some(cm) => cm }
              .toList
            (queue, CrunchMinutes.periodSummary(terminal, periodStart, queue, slotMinutes))
          }
          .toMap
        (periodStart, queueMinutes)
      }
      .toMap
  }

  def queueRecStaffSummary(start: SDateLike, periods: Int, periodMinutes: Int, terminal: Terminal, queues: Seq[Queue]): ISortedMap[Long, Int] = {
    val startMillis = start.roundToMinute().millisSinceEpoch
    val endMillis = startMillis + (periods * periodMinutes.minutes).toMillis
    val periodMillis = periodMinutes.minutes.toMillis
    ISortedMap[Long, Int]() ++ (startMillis until endMillis by periodMillis)
      .map { periodStart =>
        val periodEnd = periodStart + periodMillis
        val maxStaff = (periodStart until periodEnd by oneMinuteMillis)
          .map { minute =>
            val miscStaff = staffMinutes.get(TM(terminal, minute)).map(_.fixedPoints).getOrElse(0)
            val queueStaff = queues
              .map(queue => crunchMinutes.get(TQM(terminal, queue, minute)))
              .map(_.map(_.deskRec).getOrElse(0))
              .sum
            miscStaff + queueStaff
          }
          .max
        (periodStart, maxStaff)
      }
      .toMap
  }
  def queueDepStaffSummary(start: SDateLike, periods: Int, periodMinutes: Int, terminal: Terminal, queues: Seq[Queue]): ISortedMap[Long, Option[Int]] = {
    val startMillis = start.roundToMinute().millisSinceEpoch
    val endMillis = startMillis + (periods * periodMinutes.minutes).toMillis
    val periodMillis = periodMinutes.minutes.toMillis
    ISortedMap[Long, Option[Int]]() ++ (startMillis until endMillis by periodMillis)
      .map { periodStart =>
        val periodEnd = periodStart + periodMillis
        val deployedStaff = (periodStart until periodEnd by oneMinuteMillis)
          .map { minute =>
            val miscStaff = staffMinutes.get(TM(terminal, minute)).map(_.fixedPoints).getOrElse(0)
            val queueStaff = queues
              .map(queue => crunchMinutes.get(TQM(terminal, queue, minute)))
              .map(_.flatMap(_.deployedDesks))
            queueStaff :+ Option(miscStaff)
          }
        val maxStaff = staffDeployedSummary(deployedStaff)

        (periodStart, maxStaff)
      }
      .toMap
  }

  def staffDeployedSummary(slots: Seq[Seq[Option[Int]]]): Option[Int] = {
    val maxDeployed: Seq[Option[Int]] = slots
      .collect { queues =>
        if (queues.forall(_.isEmpty)) None
        else Option(queues.map(_.getOrElse(0)).sum)
      }

    if (maxDeployed.forall(_.isEmpty))
      None
    else {
      val staff = maxDeployed
        .collect {
          case Some(staff) => staff
        }
        .max
      Option(staff)
    }
  }

  def dailyCrunchSummary(start: SDateLike,
                         days: Int,
                         terminal: Terminal,
                         queues: (LocalDate, LocalDate, Terminal) => Seq[Queue],
                        ): ISortedMap[Long, IMap[Queue, CrunchMinute]] =
    ISortedMap[Long, IMap[Queue, CrunchMinute]]() ++ (0 until days)
      .map { day =>
        val dayStart = start.addDays(day)
        val dayEnd = dayStart.addDays(1)
        val queueMinutes = queues(dayStart.toLocalDate, dayEnd.toLocalDate, terminal)
          .map { queue =>
            val slotMinutes = (dayStart.millisSinceEpoch until dayEnd.millisSinceEpoch by 60000)
              .map { minute => crunchMinutes.get(TQM(terminal, queue, minute)) }
              .collect { case Some(cm) => cm }
              .toList
            (queue, CrunchMinutes.periodSummary(terminal, dayStart.millisSinceEpoch, queue, slotMinutes))
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

  def staffPeriodSummary(terminal: Terminal, periodStart: MillisSinceEpoch, slotMinutes: List[StaffMinute]): StaffMinute = {
    val timeWeighting = for {
      first <- slotMinutes.map(_.minute).headOption
      last <- slotMinutes.map(_.minute).lastOption
    } yield last - first + 60000

    timeWeighting match {
      case Some(tw) =>
        val maxAvailableMin = slotMinutes.sortBy(m => m.shifts + m.movements + (m.minute.toDouble / tw)).reverse.head
        StaffMinute(
          terminal = terminal,
          minute = periodStart,
          shifts = maxAvailableMin.shifts,
          fixedPoints = maxAvailableMin.fixedPoints,
          movements = maxAvailableMin.movements,
          lastUpdated = maxAvailableMin.lastUpdated,
        )
      case None =>
        StaffMinute(
          terminal = terminal,
          minute = periodStart,
          shifts = 0,
          fixedPoints = 0,
          movements = 0
        )

    }

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

  lazy val flightsLatest: MillisSinceEpoch = if (flights.nonEmpty) flights.map(_._2.lastUpdated.getOrElse(0L)).max else 0L
  lazy val crunchMinutesLatest: MillisSinceEpoch = if (crunchMinutes.nonEmpty) crunchMinutes.map(_._2.lastUpdated.getOrElse(0L)).max else 0L
  lazy val staffMinutesLatest: MillisSinceEpoch = if (staffMinutes.nonEmpty) staffMinutes.map(_._2.lastUpdated.getOrElse(0L)).max else 0L

  def window(start: SDateLike, end: SDateLike, sourceOrderPreference: List[FeedSource]): PortState

  def windowWithTerminalFilter(start: SDateLike,
                               end: SDateLike,
                               terminals: (LocalDate, LocalDate) => Seq[Terminal],
                               queues: (LocalDate, LocalDate, Terminal) => Seq[Queue],
                               sourceOrderPreference: List[FeedSource],
                              ): PortState
}
