package drt.shared

import drt.shared.DataUpdates.MinuteUpdates
import drt.shared.MilliTimes.oneMinuteMillis
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import upickle.default.{macroRW, _}

import scala.collection.immutable.{Map => IMap}

object CrunchApi {
  type MillisSinceEpoch = Long

  case class PortStateError(message: String)

  object PortStateError {
    implicit val rw: ReadWriter[PortStateError] = macroRW
  }

  trait MinuteLike[A, B] {
    val minute: MillisSinceEpoch
    val lastUpdated: Option[MillisSinceEpoch]
    val terminal: Terminal

    def maybeUpdated(existing: A, now: MillisSinceEpoch): Option[A]

    val key: B

    def toUpdatedMinute(now: MillisSinceEpoch): A

    def toMinute: A
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
                         lastUpdated: Option[MillisSinceEpoch] = None) extends MinuteLike[StaffMinute, TM] with TerminalMinute with WithLastUpdated with MinuteComparison[StaffMinute] {
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

  case class CrunchMinute(terminal: Terminal,
                          queue: Queue,
                          minute: MillisSinceEpoch,
                          paxLoad: Double,
                          workLoad: Double,
                          deskRec: Int,
                          waitTime: Int,
                          deployedDesks: Option[Int] = None,
                          deployedWait: Option[Int] = None,
                          actDesks: Option[Int] = None,
                          actWait: Option[Int] = None,
                          lastUpdated: Option[MillisSinceEpoch] = None) extends MinuteLike[CrunchMinute, TQM] with WithLastUpdated {
    def equals(candidate: CrunchMinute): Boolean = this.copy(lastUpdated = None) == candidate.copy(lastUpdated = None)

    override def maybeUpdated(existing: CrunchMinute, now: MillisSinceEpoch): Option[CrunchMinute] =
      if (!equals(existing)) Option(copy(lastUpdated = Option(now)))
      else None

    lazy val key: TQM = MinuteHelper.key(terminal, queue, minute)

    override def toUpdatedMinute(now: MillisSinceEpoch): CrunchMinute = this.copy(lastUpdated = Option(now))

    override val toMinute: CrunchMinute = this

    def prettyPrint(implicit niceDate: MillisSinceEpoch => String): String = {
      s"CrunchMinute($terminal, $queue, ${niceDate(minute)}, $paxLoad pax, $workLoad work, $deskRec desks, $waitTime waits, $deployedDesks dep desks, $deployedWait dep wait, $actDesks act desks, $actWait act wait, ${lastUpdated.map(niceDate)} updated)"
    }
  }

  object CrunchMinute {
    def apply(tqm: TQM, ad: DeskStat, now: MillisSinceEpoch): CrunchMinute = CrunchMinute(
      terminal = tqm.terminal,
      queue = tqm.queue,
      minute = tqm.minute,
      paxLoad = 0,
      workLoad = 0,
      deskRec = 0,
      waitTime = 0,
      actDesks = ad.desks,
      actWait = ad.waitTime,
      lastUpdated = Option(now)
    )

    implicit val rw: ReadWriter[CrunchMinute] = macroRW
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

  case class DeskRecMinute(terminal: Terminal,
                           queue: Queue,
                           minute: MillisSinceEpoch,
                           paxLoad: Double,
                           workLoad: Double,
                           deskRec: Int,
                           waitTime: Int) extends DeskRecMinuteLike with MinuteComparison[CrunchMinute] with MinuteLike[CrunchMinute, TQM] {
    lazy val key: TQM = MinuteHelper.key(terminal, queue, minute)

    override def maybeUpdated(existing: CrunchMinute, now: MillisSinceEpoch): Option[CrunchMinute] =
      if (existing.paxLoad != paxLoad || existing.workLoad != workLoad || existing.deskRec != deskRec || existing.waitTime != waitTime)
        Option(existing.copy(
          paxLoad = paxLoad, workLoad = workLoad, deskRec = deskRec, waitTime = waitTime, lastUpdated = Option(now)
        ))
      else None

    override val lastUpdated: Option[MillisSinceEpoch] = None

    override def toUpdatedMinute(now: MillisSinceEpoch): CrunchMinute = toMinute.copy(lastUpdated = Option(now))

    override def toMinute: CrunchMinute = CrunchMinute(
      terminal, queue, minute, paxLoad, workLoad, deskRec, waitTime, lastUpdated = None)
  }

  case class DeskRecMinutes(minutes: Seq[DeskRecMinute]) extends PortStateQueueMinutes {
    override val asContainer: MinutesContainer[CrunchMinute, TQM] = MinutesContainer(minutes)

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
      terminal, queue, minute, 0d, 0d, 0, 0, None, None, deskStat.desks, deskStat.waitTime, None)
  }

  case class ActualDeskStats(portDeskSlots: IMap[Terminal, IMap[Queue, IMap[MillisSinceEpoch, DeskStat]]]) extends PortStateQueueMinutes {
    override val asContainer: MinutesContainer[CrunchMinute, TQM] = MinutesContainer(deskStatMinutes)

    override def isEmpty: Boolean = portDeskSlots.isEmpty

    lazy val deskStatMinutes: Iterable[DeskStatMinute] = for {
      (tn, queueMinutes) <- portDeskSlots
      (qn, deskStats) <- queueMinutes
      (startMinute, deskStat) <- deskStats
      minute <- startMinute until startMinute + 15 * oneMinuteMillis by oneMinuteMillis
    } yield DeskStatMinute(tn, qn, minute, deskStat)
  }

  sealed trait MinutesLike[A, B] {
    def minutes: Iterable[MinuteLike[A, B]]
  }

  object MinutesContainer {
    def empty[A, B <: WithTimeAccessor]: MinutesContainer[A, B] = MinutesContainer[A, B](Iterable())
  }

  case class MinutesContainer[A, B <: WithTimeAccessor](minutes: Iterable[MinuteLike[A, B]]) extends MinuteUpdates {
    def window(start: SDateLike, end: SDateLike): MinutesContainer[A, B] = {
      val startMillis = start.millisSinceEpoch
      val endMillis = end.millisSinceEpoch
      MinutesContainer(minutes.filter(i => startMillis <= i.minute && i.minute <= endMillis))
    }

    def ++(that: MinutesContainer[A, B]): MinutesContainer[A, B] = MinutesContainer(minutes ++ that.minutes)

    def updatedSince(sinceMillis: MillisSinceEpoch): MinutesContainer[A, B] = MinutesContainer(minutes.filter(_.lastUpdated.getOrElse(0L) > sinceMillis))

    def contains(clazz: Class[_]): Boolean = minutes.headOption match {
      case Some(x) if x.getClass == clazz => true
      case _ => false
    }

    lazy val indexed: IMap[B, A] = minutes.map(m => (m.key, m.toMinute)).toMap
  }

  case class CrunchMinutes(minutes: Set[CrunchMinute]) extends MinutesLike[CrunchMinute, TQM]

  case class PortStateUpdates(latest: MillisSinceEpoch,
                              flights: Set[ApiFlightWithSplits],
                              queueMinutes: Set[CrunchMinute],
                              staffMinutes: Set[StaffMinute])

  object PortStateUpdates {
    implicit val rw: ReadWriter[PortStateUpdates] = macroRW
  }

  case class ForecastTimeSlot(startMillis: MillisSinceEpoch, available: Int, required: Int)

  case class ForecastPeriodWithHeadlines(forecast: ForecastPeriod, headlines: ForecastHeadlineFigures)

  case class ForecastPeriod(days: IMap[MillisSinceEpoch, Seq[ForecastTimeSlot]])

  case class ForecastHeadlineFigures(queueDayHeadlines: Seq[QueueHeadline])

  case class QueueHeadline(day: MillisSinceEpoch, queue: Queue, paxNos: Int, workload: Int)

  def groupCrunchMinutesByX(groupSize: Int)
                           (crunchMinutes: Seq[(MillisSinceEpoch, List[CrunchMinute])],
                            terminalName: Terminal,
                            queueOrder: List[Queue]): Seq[(MillisSinceEpoch, Seq[CrunchMinute])] = {
    crunchMinutes.grouped(groupSize).toList.map(group => {
      val byQueueName = group.flatMap(_._2).groupBy(_.queue)
      val startMinute = group.map(_._1).min
      val queueCrunchMinutes = queueOrder.collect {
        case qn if byQueueName.contains(qn) =>
          val queueMinutes: Seq[CrunchMinute] = byQueueName(qn)
          val allActDesks = queueMinutes.collect {
            case CrunchMinute(_, _, _, _, _, _, _, _, _, Some(ad), _, _) => ad
          }
          val actDesks = if (allActDesks.isEmpty) None else Option(allActDesks.max)
          val allActWaits = queueMinutes.collect {
            case CrunchMinute(_, _, _, _, _, _, _, _, _, _, Some(aw), _) => aw
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
            deployedDesks = Option(queueMinutes.map(_.deployedDesks.getOrElse(0)).max),
            deployedWait = Option(queueMinutes.map(_.deployedWait.getOrElse(0)).max),
            actDesks = actDesks,
            actWait = actWaits
          )
      }
      (startMinute, queueCrunchMinutes)
    })
  }

  def terminalMinutesByMinute[T <: MinuteLike[A, B], A, B](minutes: List[T],
                                                           terminalName: Terminal): Seq[(MillisSinceEpoch, List[T])] = minutes
    .filter(_.terminal == terminalName)
    .groupBy(_.minute)
    .toList
    .sortBy(_._1)

}