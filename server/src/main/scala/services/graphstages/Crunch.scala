package services.graphstages

import drt.shared.CrunchApi._
import drt.shared._
import org.joda.time.DateTimeZone
import org.slf4j.{Logger, LoggerFactory}
import uk.gov.homeoffice.drt.arrivals.{Arrival, UniqueArrival, WithTimeAccessor}
import uk.gov.homeoffice.drt.ports.PaxType
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{MilliTimes, SDate, SDateLike, UtcDate}

import scala.collection.immutable.{Map, SortedMap}

object Crunch {
  val paxOffPerMinute: Int = 20

  val log: Logger = LoggerFactory.getLogger(getClass)

  case class SplitMinutes(minutes: Map[TQM, LoadMinute]) {
    def toLoads: Loads = Loads(SortedMap[TQM, LoadMinute]() ++ minutes)
  }

  case class Passenger(processingTime: Double)

  case class FlightSplitDiff(flightId: CodeShareKeyOrderedBySchedule,
                             paxType: PaxType,
                             terminalName: Terminal,
                             queueName: Queue,
                             paxLoad: Double,
                             workLoad: Double,
                             minute: MillisSinceEpoch)

  trait LoadMinuteLike {
    val terminal: Terminal
    val queue: Queue
    val minute: MillisSinceEpoch
    val paxLoad: Double
    val workLoad: Double
    val maybePassengers: Option[Iterable[Double]]
  }

  case class LoadMinute(terminal: Terminal,
                        queue: Queue,
                        passengers: Iterable[Double],
                        workLoad: Double,
                        minute: MillisSinceEpoch) extends TerminalQueueMinute with LoadMinuteLike {
    lazy val uniqueId: TQM = TQM(terminal, queue, minute)

    lazy val paxLoad: Double = passengers.size
    lazy val maybePassengers: Option[Iterable[Double]] = Some(passengers)

    def +(other: LoadMinute): LoadMinuteLike = this.copy(
      passengers = this.passengers ++ other.passengers,
      workLoad = this.workLoad + other.workLoad
      )
  }

  object LoadMinute {
    def apply(cm: CrunchMinute): LoadMinute = {
      val wholePassengerCount = cm.paxLoad.toInt
      val averagePassenger = cm.workLoad / wholePassengerCount
      val passengers = Iterable.fill(wholePassengerCount)(averagePassenger)
      LoadMinute(cm.terminal, cm.queue, passengers, cm.workLoad, cm.minute)
    }
  }

  case class Loads(loadMinutes: SortedMap[TQM, LoadMinute])

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

  def baseArrivalsRemovalsAndUpdates(incoming: Map[UniqueArrival, Arrival],
                                     existing: Map[UniqueArrival, Arrival]): (Set[UniqueArrival], Iterable[Arrival]) = {
    val removals = existing.keys.toSet -- incoming.keys.toSet

    val updates = incoming.collect {
      case (k, a) if !existing.contains(k) || existing(k) != a =>  a
    }

    (removals, updates)
  }

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
}
