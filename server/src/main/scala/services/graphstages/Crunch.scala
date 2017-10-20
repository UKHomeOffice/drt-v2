package services.graphstages

import drt.shared.CrunchApi.{CrunchMinute, MillisSinceEpoch}
import drt.shared.FlightsApi.{QueueName, TerminalName}
import drt.shared._
import org.joda.time.{DateTime, DateTimeZone}
import org.slf4j.{Logger, LoggerFactory}
import services._

import scala.collection.immutable.{Map, Seq}

object Crunch {
  val log: Logger = LoggerFactory.getLogger(getClass)

  case class FlightSplitMinute(flightId: Int, paxType: PaxType, terminalName: TerminalName, queueName: QueueName, paxLoad: Double, workLoad: Double, minute: MillisSinceEpoch)

  case class FlightSplitDiff(flightId: Int, paxType: PaxType, terminalName: TerminalName, queueName: QueueName, paxLoad: Double, workLoad: Double, minute: MillisSinceEpoch)

  case class QueueLoadMinute(terminalName: TerminalName, queueName: QueueName, paxLoad: Double, workLoad: Double, minute: MillisSinceEpoch)

  case class RemoveCrunchMinute(terminalName: TerminalName, queueName: QueueName, minute: MillisSinceEpoch) {
    lazy val key: Int = s"$terminalName$queueName$minute".hashCode
  }

  case class RemoveFlight(flightId: Int)

  case class CrunchDiff(flightRemovals: Set[RemoveFlight], flightUpdates: Set[ApiFlightWithSplits], crunchMinuteRemovals: Set[RemoveCrunchMinute], crunchMinuteUpdates: Set[CrunchMinute])

  case class CrunchRequest(flights: List[ApiFlightWithSplits], crunchStart: MillisSinceEpoch)

  val oneMinuteMillis: MillisSinceEpoch = 60000L
  val oneHourMillis: MillisSinceEpoch = oneMinuteMillis * 60
  val oneDayMillis: MillisSinceEpoch = oneHourMillis * 24

  def flightVoyageNumberPadded(arrival: Arrival): String = {
    val number = FlightParsing.parseIataToCarrierCodeVoyageNumber(arrival.IATA)
    val vn = padTo4Digits(number.map(_._2).getOrElse("-"))
    vn
  }

  def midnightThisMorning: MillisSinceEpoch = {
    val localNow = SDate(new DateTime(DateTimeZone.forID("Europe/London")).getMillis)
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
    val localMidnight = s"${now.getFullYear()}-${now.getMonth()}-${now.getDate()}T00:00"
    SDate(localMidnight, DateTimeZone.forID("Europe/London"))
  }

  def getLocalNextMidnight(now: SDateLike): SDateLike = {
    val nextDay = now.addDays(1)
    val localMidnight = s"${nextDay.getFullYear()}-${nextDay.getMonth()}-${nextDay.getDate()}T00:00"
    SDate(localMidnight, DateTimeZone.forID("Europe/London"))
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

  def crunchMinutesDiff(oldTqmToCm: Map[Int, CrunchMinute], newTqmToCm: Map[Int, CrunchMinute]): (Set[RemoveCrunchMinute], Set[CrunchMinute]) = {
    val oldKeys = oldTqmToCm.values.map(cm => Tuple3(cm.terminalName, cm.queueName, cm.minute)).toSet
    val newKeys = newTqmToCm.values.map(cm => Tuple3(cm.terminalName, cm.queueName, cm.minute)).toSet
    val toRemove = (oldKeys -- newKeys).map {
      case (tn, qn, m) => RemoveCrunchMinute(tn, qn, m)
    }
    val toUpdate = newTqmToCm.collect {
      case (k, cm) if oldTqmToCm.get(k).isEmpty || !cm.equals(oldTqmToCm(k)) => cm
    }.toSet

    Tuple2(toRemove, toUpdate)
  }

  def crunchMinuteToTqmCm(cm: CrunchMinute): ((TerminalName, QueueName, MillisSinceEpoch), CrunchMinute) = {
    Tuple2(Tuple3(cm.terminalName, cm.queueName, cm.minute), cm)
  }

  def applyCrunchDiff(diff: CrunchDiff, cms: Map[Int, CrunchMinute]): Map[Int, CrunchMinute] = {
    val nowMillis = SDate.now().millisSinceEpoch
    val withoutRemovals = diff.crunchMinuteRemovals.foldLeft(cms) {
      case (soFar, removeCm) => soFar - removeCm.key
    }
    val withoutRemovalsWithUpdates = diff.crunchMinuteUpdates.foldLeft(withoutRemovals) {
      case (soFar, ncm) => soFar.updated(ncm.key, ncm.copy(lastUpdated = Option(nowMillis)))
    }
    withoutRemovalsWithUpdates
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

  def desksForHourOfDayInUKLocalTime(dateTimeMillis: MillisSinceEpoch, desks: Seq[Int]): Int = {
    val date = new DateTime(dateTimeMillis).withZone(DateTimeZone.forID("Europe/London"))
    desks(date.getHourOfDay)
  }

  def hasExpired[A](now: SDateLike, expireAfterMillis: Long, toMillis: (A) => MillisSinceEpoch)(toCompare: A): Boolean = {
    val ageInMillis = now.millisSinceEpoch - toMillis(toCompare)
    ageInMillis > expireAfterMillis
  }
}
