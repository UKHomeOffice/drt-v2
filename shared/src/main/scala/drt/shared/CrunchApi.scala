package drt.shared

import uk.gov.homeoffice.drt.DataUpdates.{Combinable, MinuteUpdates}
import uk.gov.homeoffice.drt.arrivals.{WithLastUpdated, WithTimeAccessor}
import uk.gov.homeoffice.drt.models.{CrunchMinute, MinuteLike, TQM, WithMinute}
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.MilliTimes.oneMinuteMillis
import uk.gov.homeoffice.drt.time.{SDateLike, UtcDate}
import upickle.default._

import scala.collection.immutable.{Map => IMap}
import scala.util.Try

object CrunchApi {
  type MillisSinceEpoch = Long

  case class PortStateError(message: String)

  object PortStateError {
    implicit val rw: ReadWriter[PortStateError] = macroRW
  }

  trait TerminalQueueMinute {
    val terminal: Terminal
    val queue: Queue
    val minute: MillisSinceEpoch
  }

  trait TerminalMinute {
    val terminal: Terminal
    val minute: MillisSinceEpoch
  }

  case class StaffMinute(terminal: Terminal,
                         minute: MillisSinceEpoch,
                         shifts: Int,
                         fixedPoints: Int,
                         movements: Int,
                         lastUpdated: Option[MillisSinceEpoch] = None)
    extends MinuteLike[StaffMinute, TM]
      with WithMinute with TerminalMinute with WithLastUpdated with MinuteComparison[StaffMinute] {
    def equals(candidate: StaffMinute): Boolean =
      this.copy(lastUpdated = None) == candidate.copy(lastUpdated = None)

    lazy val key: TM = TM(terminal, minute)
    lazy val available: Int = shifts + movements match {
      case sa if sa >= 0 => sa
      case _ => 0
    }
    lazy val availableAtPcp: Int = {
      shifts - fixedPoints + movements match {
        case sa if sa >= 0 => sa
        case _ => 0
      }
    }

    override def maybeUpdated(existing: StaffMinute, now: MillisSinceEpoch): Option[StaffMinute] =
      if (existing.shifts != shifts || existing.fixedPoints != fixedPoints || existing.movements != movements) Option(existing.copy(
        shifts = shifts, fixedPoints = fixedPoints, movements = movements, lastUpdated = Option(now)
      ))
      else None

    override def toUpdatedMinute(now: MillisSinceEpoch): StaffMinute = toMinute.copy(lastUpdated = Option(now))

    override def toMinute: StaffMinute = this
  }

  object StaffMinute {
    def empty: StaffMinute = StaffMinute(Terminal(""), 0L, 0, 0, 0, None)

    implicit val rw: ReadWriter[StaffMinute] = macroRW
  }

  case class StaffMinutes(minutes: Seq[StaffMinute]) extends PortStateStaffMinutes with MinutesLike[StaffMinute, TM] {
    override val asContainer: MinutesContainer[StaffMinute, TM] = MinutesContainer(minutes)

    override def isEmpty: Boolean = minutes.isEmpty

    lazy val millis: Iterable[MillisSinceEpoch] = minutes.map(_.minute)
  }

  object StaffMinutes {
    def apply(minutesByKey: IMap[TM, StaffMinute]): StaffMinutes = StaffMinutes(minutesByKey.values.toSeq)

    implicit val rw: ReadWriter[StaffMinutes] = macroRW
  }

  case class PassengersMinute(terminal: Terminal,
                              queue: Queue,
                              minute: MillisSinceEpoch,
                              passengers: Iterable[Double],
                              lastUpdated: Option[MillisSinceEpoch]
                             ) extends MinuteLike[PassengersMinute, TQM] {

    override def maybeUpdated(existing: PassengersMinute, now: MillisSinceEpoch): Option[PassengersMinute] =
      if (existing.passengers != passengers)
        Option(copy(lastUpdated = Option(now)))
      else
        None

    override val key: TQM = TQM(terminal, queue, minute)

    override def toUpdatedMinute(now: MillisSinceEpoch): PassengersMinute = toMinute.copy(lastUpdated = Option(now))

    override def toMinute: PassengersMinute = this
  }

  trait DeskRecMinuteLike {
    val terminal: Terminal
    val queue: Queue
    val minute: MillisSinceEpoch
    val paxLoad: Double
    val workLoad: Double
    val deskRec: Int
    val waitTime: Int
  }

  object DeskRecMinute {
    def from(crunchMinute: CrunchMinute): DeskRecMinute = DeskRecMinute(
      terminal = crunchMinute.terminal,
      queue = crunchMinute.queue,
      minute = crunchMinute.minute,
      paxLoad = crunchMinute.paxLoad,
      workLoad = crunchMinute.workLoad,
      deskRec = crunchMinute.deskRec,
      waitTime = crunchMinute.waitTime,
      maybePaxInQueue = crunchMinute.maybePaxInQueue,
    )
  }

  case class DeskRecMinute(terminal: Terminal,
                           queue: Queue,
                           minute: MillisSinceEpoch,
                           paxLoad: Double,
                           workLoad: Double,
                           deskRec: Int,
                           waitTime: Int,
                           maybePaxInQueue: Option[Int],
                          ) extends DeskRecMinuteLike with MinuteComparison[CrunchMinute] with MinuteLike[CrunchMinute, TQM] {
    lazy val key: TQM = MinuteHelper.key(terminal, queue, minute)

    override def maybeUpdated(existing: CrunchMinute, now: MillisSinceEpoch): Option[CrunchMinute] =
      if (this != DeskRecMinute.from(existing))
        Option(existing.copy(
          paxLoad = paxLoad,
          workLoad = workLoad,
          deskRec = deskRec,
          waitTime = waitTime,
          maybePaxInQueue = maybePaxInQueue,
          lastUpdated = Option(now)
        ))
      else None

    override val lastUpdated: Option[MillisSinceEpoch] = None

    override def toUpdatedMinute(now: MillisSinceEpoch): CrunchMinute = toMinute.copy(lastUpdated = Option(now))

    override def toMinute: CrunchMinute = CrunchMinute(
      terminal, queue, minute, paxLoad, workLoad, deskRec, waitTime, maybePaxInQueue, lastUpdated = None)
  }

  case class DeskRecMinutes(minutes: Iterable[DeskRecMinute]) extends PortStateQueueMinutes {
    override val asContainer: MinutesContainer[CrunchMinute, TQM] = MinutesContainer(minutes)

    override def isEmpty: Boolean = minutes.isEmpty
  }

  case class PassengersMinutes(minutes: Seq[PassengersMinute]) extends PortStateQueueLoadMinutes {
    override val asContainer: MinutesContainer[PassengersMinute, TQM] = MinutesContainer(minutes)

    override def isEmpty: Boolean = minutes.isEmpty
  }

  trait SimulationMinuteLike {
    val terminal: Terminal
    val queue: Queue
    val minute: MillisSinceEpoch
    val desks: Int
    val waitTime: Int
  }

  case class DeskStat(desks: Option[Int], waitTime: Option[Int]) extends MinuteComparison[CrunchMinute] {
    override def maybeUpdated(existing: CrunchMinute, now: MillisSinceEpoch): Option[CrunchMinute] =
      if (existing.actDesks != desks || existing.actWait != waitTime) Option(existing.copy(
        actDesks = desks, actWait = waitTime, lastUpdated = Option(now)
      ))
      else None
  }

  case class DeskStatMinute(terminal: Terminal,
                            queue: Queue,
                            minute: MillisSinceEpoch,
                            deskStat: DeskStat) extends MinuteLike[CrunchMinute, TQM] {
    override val key: TQM = TQM(terminal, queue, minute)

    override def maybeUpdated(existing: CrunchMinute, now: MillisSinceEpoch): Option[CrunchMinute] =
      if (existing.actDesks != deskStat.desks || existing.actWait != deskStat.waitTime) Option(existing.copy(
        actDesks = deskStat.desks, actWait = deskStat.waitTime, lastUpdated = Option(now)
      ))
      else None

    override val lastUpdated: Option[MillisSinceEpoch] = None

    override def toUpdatedMinute(now: MillisSinceEpoch): CrunchMinute = toMinute.copy(lastUpdated = Option(now))

    override def toMinute: CrunchMinute = CrunchMinute(
      terminal = terminal,
      queue = queue,
      minute = minute,
      paxLoad = 0d,
      workLoad = 0d,
      deskRec = 0,
      waitTime = 0,
      maybePaxInQueue = None,
      deployedDesks = None,
      deployedWait = None,
      maybeDeployedPaxInQueue = None,
      actDesks = deskStat.desks,
      actWait = deskStat.waitTime,
    )
  }

  object DeskStatMinute {
    def from(crunchMinute: CrunchMinute): DeskStatMinute = DeskStatMinute(
      terminal = crunchMinute.terminal,
      queue = crunchMinute.queue,
      minute = crunchMinute.minute,
      deskStat = DeskStat(crunchMinute.actDesks, crunchMinute.actWait)
    )
  }

  case class ActualDeskStats(portDeskSlots: IMap[Terminal, IMap[Queue, IMap[MillisSinceEpoch, DeskStat]]]) extends PortStateQueueMinutes {
    override val asContainer: MinutesContainer[CrunchMinute, TQM] = MinutesContainer(deskStatMinutes)

    override def isEmpty: Boolean = portDeskSlots.isEmpty

    lazy val deskStatMinutes: Seq[DeskStatMinute] = {
      val mins = for {
        (tn, queueMinutes) <- portDeskSlots
        (qn, deskStats) <- queueMinutes
        (startMinute, deskStat) <- deskStats
        minute <- startMinute until startMinute + 15 * oneMinuteMillis by oneMinuteMillis
      } yield DeskStatMinute(tn, qn, minute, deskStat)
      mins.toSeq
    }
  }

  sealed trait MinutesLike[A, B] {
    def minutes: Iterable[MinuteLike[A, B]]
  }

  object MinutesContainer {
    def empty[A, B <: WithTimeAccessor]: MinutesContainer[A, B] = MinutesContainer[A, B](Seq())
  }

  case class MinutesContainer[MINUTE, IDX <: WithTimeAccessor](minutes: Iterable[MinuteLike[MINUTE, IDX]]) extends MinuteUpdates with Combinable[MinutesContainer[MINUTE, IDX]] {
    def latestUpdateMillis: MillisSinceEpoch = Try(minutes.map(_.lastUpdated.getOrElse(0L)).max).getOrElse(0L)

    def window(start: SDateLike, end: SDateLike): MinutesContainer[MINUTE, IDX] = {
      val startMillis = start.millisSinceEpoch
      val endMillis = end.millisSinceEpoch
      MinutesContainer(minutes.filter(i => startMillis <= i.minute && i.minute <= endMillis))
    }

    def ++(that: MinutesContainer[MINUTE, IDX]): MinutesContainer[MINUTE, IDX] = MinutesContainer(minutes ++ that.minutes)

    def contains(clazz: Class[_]): Boolean = minutes.headOption match {
      case Some(x) if x.getClass == clazz => true
      case _ => false
    }

    lazy val indexed: IMap[IDX, MINUTE] = minutes.map(m => (m.key, m.toMinute)).toMap
  }

  case class CrunchMinutes(minutes: Iterable[CrunchMinute]) extends MinutesLike[CrunchMinute, TQM]

  object CrunchMinutes {
    def groupByMinutes(slotSizeMinutes: Int, crunchMinutes: Seq[CrunchMinute], date: UtcDate)
                      (implicit dateInMillis: UtcDate => MillisSinceEpoch): Iterable[CrunchMinute] =
      crunchMinutes
        .groupBy(_.terminal)
        .map {
          case (terminal, minutes) => (terminal, minutes.groupBy(_.queue))
        }
        .flatMap {
          case (terminal, minutesByQueue) =>
            minutesByQueue.flatMap {
              case (queue, minutes) =>
                val dayStart = dateInMillis(date)
                (0 until 1440 by slotSizeMinutes).map { slot =>
                  val slotStart = dayStart + slot * oneMinuteMillis
                  val slotEnd = slotStart + slotSizeMinutes * oneMinuteMillis
                  val minsInSlot = minutes.filter(cm => slotStart <= cm.minute && cm.minute < slotEnd)
                  periodSummary(terminal, slotStart, queue, minsInSlot.toList)
                }
            }
        }
    def periodSummary(terminal: Terminal, periodStart: MillisSinceEpoch, queue: Queue, slotMinutes: List[CrunchMinute]): CrunchMinute = {
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

  }

  case class PortStateUpdates(lastFlightsUpdate: MillisSinceEpoch,
                              lastQueuesUpdate: MillisSinceEpoch,
                              lastStaffUpdate: MillisSinceEpoch,
                              updatesAndRemovals: FlightUpdatesAndRemovals,
                              queueMinutes: Iterable[CrunchMinute],
                              staffMinutes: Iterable[StaffMinute])

  object PortStateUpdates {
    implicit val rw: ReadWriter[PortStateUpdates] = macroRW
  }

  case class ForecastTimeSlot(startMillis: MillisSinceEpoch, available: Int, required: Int)

  object ForecastTimeSlot {
    implicit val rw: ReadWriter[ForecastTimeSlot] = macroRW
  }

  case class ForecastPeriodWithHeadlines(forecast: ForecastPeriod, headlines: ForecastHeadlineFigures)

  object ForecastPeriodWithHeadlines {
    implicit val rw: ReadWriter[ForecastPeriodWithHeadlines] = macroRW
  }

  case class ForecastPeriod(days: IMap[MillisSinceEpoch, Seq[ForecastTimeSlot]])

  object ForecastPeriod {
    implicit val rw: ReadWriter[ForecastPeriod] = macroRW
  }

  case class ForecastHeadlineFigures(queueDayHeadlines: Seq[QueueHeadline])

  object ForecastHeadlineFigures {
    implicit val rw: ReadWriter[ForecastHeadlineFigures] = macroRW
  }

  case class QueueHeadline(day: MillisSinceEpoch, queue: Queue, paxNos: Int, workload: Int)

  object QueueHeadline {
    implicit val rw: ReadWriter[QueueHeadline] = macroRW
  }

  def groupCrunchMinutesBy(groupSize: Int)
                          (crunchMinutes: Seq[(MillisSinceEpoch, List[CrunchMinute])],
                           terminalName: Terminal,
                           queueOrder: List[Queue],
                          ): Seq[(MillisSinceEpoch, Seq[CrunchMinute])] =
    crunchMinutes.grouped(groupSize).toList.map(group => {
      val byQueueName = group.flatMap(_._2).groupBy(_.queue)
      val startMinute = group.map(_._1).min
      val queueCrunchMinutes = queueOrder.collect {
        case qn if byQueueName.contains(qn) =>
          val queueMinutes: Seq[CrunchMinute] = byQueueName(qn)
          val allActDesks = queueMinutes.collect {
            case cm: CrunchMinute if cm.actDesks.isDefined => cm.actDesks.getOrElse(0)
          }
          val actDesks = if (allActDesks.isEmpty) None else Option(allActDesks.max)
          val allActWaits = queueMinutes.collect {
            case cm: CrunchMinute if cm.actWait.isDefined => cm.actWait.getOrElse(0)
          }
          val actWaits = if (allActWaits.isEmpty) None else Option(allActWaits.max)
          CrunchMinute(
            terminal = terminalName,
            queue = qn,
            minute = startMinute,
            paxLoad = queueMinutes.map(_.paxLoad).sum,
            workLoad = queueMinutes.map(_.workLoad).sum,
            deskRec = queueMinutes.map(_.deskRec).max,
            waitTime = queueMinutes.map(_.waitTime).max,
            maybePaxInQueue = queueMinutes.map(_.maybePaxInQueue).max,
            deployedDesks = Option(queueMinutes.map(_.deployedDesks.getOrElse(0)).max),
            deployedWait = Option(queueMinutes.map(_.deployedWait.getOrElse(0)).max),
            maybeDeployedPaxInQueue = Option(queueMinutes.map(_.maybeDeployedPaxInQueue.getOrElse(0)).max),
            actDesks = actDesks,
            actWait = actWaits
          )
      }
      (startMinute, queueCrunchMinutes)
    })

  def terminalMinutesByMinute[T <: MinuteLike[_, _]](minutes: List[T],
                                                           terminalName: Terminal): Seq[(MillisSinceEpoch, List[T])] =
    minutes
      .filter(_.terminal == terminalName)
      .groupBy(_.minute)
      .toList
      .sortBy(_._1)

}
