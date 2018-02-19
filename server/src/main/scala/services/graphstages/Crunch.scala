package services.graphstages

import drt.shared.CrunchApi.{CrunchMinute, MillisSinceEpoch, Minute, StaffMinute}
import drt.shared.FlightsApi.{QueueName, TerminalName}
import drt.shared._
import org.joda.time.{DateTime, DateTimeZone}
import org.slf4j.{Logger, LoggerFactory}
import services._

import scala.collection.immutable.Map

object Crunch {
  val log: Logger = LoggerFactory.getLogger(getClass)

  case class FlightSplitMinute(flightId: Int, paxType: PaxType, terminalName: TerminalName, queueName: QueueName, paxLoad: Double, workLoad: Double, minute: MillisSinceEpoch)

  case class FlightSplitDiff(flightId: Int, paxType: PaxType, terminalName: TerminalName, queueName: QueueName, paxLoad: Double, workLoad: Double, minute: MillisSinceEpoch)

  case class QueueLoadMinute(terminalName: TerminalName, queueName: QueueName, paxLoad: Double, workLoad: Double, minute: MillisSinceEpoch)

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

  def flightVoyageNumberPadded(arrival: Arrival): String = {
    val number = FlightParsing.parseIataToCarrierCodeVoyageNumber(arrival.IATA)
    val vn = padTo4Digits(number.map(_._2).getOrElse("-"))
    vn
  }

  val europeLondonTimeZone = DateTimeZone.forID("Europe/London")

  def midnightThisMorning: MillisSinceEpoch = {
    val localNow = SDate(new DateTime(europeLondonTimeZone).getMillis)
    val crunchStartDate = Crunch.getLocalLastMidnight(localNow).millisSinceEpoch
    crunchStartDate
  }

  def padTo4Digits(voyageNumber: String): String = {
    val prefix = voyageNumber.length match {
      case 4 => ""
      case 3 => "0"
      case 2 => "00"
      case 1 => "000"
      case _ => ""
    }
    prefix + voyageNumber
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

  def collapseQueueLoadMinutesToSet(queueLoadMinutes: List[QueueLoadMinute]): Set[QueueLoadMinute] = {
    queueLoadMinutes
      .groupBy(qlm => (qlm.terminalName, qlm.queueName, qlm.minute))
      .map {
        case ((t, q, m), qlm) =>
          val summedPaxLoad = qlm.map(_.paxLoad).sum
          val summedWorkLoad = qlm.map(_.workLoad).sum
          QueueLoadMinute(t, q, summedPaxLoad, summedWorkLoad, m)
      }.toSet
  }

  def desksForHourOfDayInUKLocalTime(dateTimeMillis: MillisSinceEpoch, desks: Seq[Int]): Int = {
    val date = new DateTime(dateTimeMillis).withZone(europeLondonTimeZone)
    desks(date.getHourOfDay)
  }

  def hasExpired[A](now: SDateLike, expireAfterMillis: Long, toMillis: (A) => MillisSinceEpoch)(toCompare: A): Boolean = {
    val ageInMillis = now.millisSinceEpoch - toMillis(toCompare)
    ageInMillis > expireAfterMillis
  }

  def purgeExpiredMinutes[M <: Minute](minutes: Map[Int, M], now: () => SDateLike, expireAfterMillis: MillisSinceEpoch): Map[Int, M] = {
    val expired: M => Boolean = Crunch.hasExpired(now(), expireAfterMillis, (cm: M) => cm.minute)
    val updated = minutes.filterNot { case (_, cm) => expired(cm)}
    log.info(s"Purged ${minutes.size - updated.size} expired CrunchMinutes")
    updated
  }
}
