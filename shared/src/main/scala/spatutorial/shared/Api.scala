package drt.shared

import drt.shared.CrunchApi.{CrunchState, CrunchUpdates, ForecastPeriod, MillisSinceEpoch}
import drt.shared.FlightsApi._
import drt.shared.SplitRatiosNs.SplitSources

import scala.collection.immutable.{Map, _}
import scala.concurrent.Future
import scala.util.matching.Regex


object DeskAndPaxTypeCombinations {
  val egate = "eGate"
  val deskEeaNonMachineReadable = "EEA NMR"
  val deskEea = "EEA"
  val nationalsDeskVisa = "VISA"
  val nationalsDeskNonVisa = "Non-VISA"
}

case class MilliDate(millisSinceEpoch: Long) extends Ordered[MilliDate] {
  def compare(that: MilliDate): Int = millisSinceEpoch.compare(that.millisSinceEpoch)
}

object FlightParsing {
  val iataRe: Regex = """([A-Z0-9]{2})(\d{1,4})(\w)?""".r
  val icaoRe: Regex = """([A-Z]{2,3})(\d{1,4})(\w)?""".r

  def parseIataToCarrierCodeVoyageNumber(iata: String): Option[(String, String)] = {
    iata match {
      case iataRe(carriercode, voyageNumber, _) => Option((carriercode, voyageNumber))
      case icaoRe(carriercode, voyageNumber, _) => Option((carriercode, voyageNumber))
      case what => None
    }
  }
}


sealed trait SplitStyle {
  def name: String = getClass.getSimpleName
}

object SplitStyle {
  def apply(splitStyle: String): SplitStyle = splitStyle match {
    case "PaxNumbers$" => PaxNumbers
    case "Percentage$" => Percentage
    case _ => UndefinedSplitStyle
  }
}

case object PaxNumbers extends SplitStyle

case object Percentage extends SplitStyle

case object UndefinedSplitStyle extends SplitStyle

case class ApiSplits(splits: Set[ApiPaxTypeAndQueueCount], source: String, eventType: Option[String], splitStyle: SplitStyle = PaxNumbers) {
  lazy val totalExcludingTransferPax: Double = ApiSplits.totalExcludingTransferPax(splits)
  lazy val totalPax: Double = ApiSplits.totalPax(splits)
}

object ApiSplits {
  def totalExcludingTransferPax(splits: Set[ApiPaxTypeAndQueueCount]): Double = splits.filter(s => s.queueType != Queues.Transfer).map(_.paxCount).sum

  def totalPax(splits: Set[ApiPaxTypeAndQueueCount]): Double = splits.map(_.paxCount).sum
}

case class ApiFlightWithSplits(apiFlight: Arrival, splits: Set[ApiSplits], lastUpdated: Option[MillisSinceEpoch] = None) {
  def equals(candidate: ApiFlightWithSplits): Boolean = {
    this.copy(lastUpdated = None) == candidate.copy(lastUpdated = None)
  }

  def bestSplits: Option[ApiSplits] = {
    val apiSplitsDc = splits.find(s => s.source == SplitSources.ApiSplitsWithCsvPercentage && s.eventType.contains(DqEventCodes.DepartureConfirmed))
    val apiSplitsCi = splits.find(s => s.source == SplitSources.ApiSplitsWithCsvPercentage && s.eventType.contains(DqEventCodes.CheckIn))
    val historicalSplits = splits.find(_.source == SplitSources.Historical)
    val terminalSplits = splits.find(_.source == SplitSources.TerminalAverage)

    apiSplitsDc match {
      case s@Some(_) => s
      case None => apiSplitsCi match {
        case s@Some(_) => s
        case None => historicalSplits match {
          case s@Some(_) => s
          case None => terminalSplits match {
            case s@Some(_) => s
            case None => None
          }
        }
      }
    }
  }
}


case class FlightsNotReady()

case class Arrival(
                    Operator: String,
                    Status: String,
                    EstDT: String,
                    ActDT: String,
                    EstChoxDT: String,
                    ActChoxDT: String,
                    Gate: String,
                    Stand: String,
                    MaxPax: Int,
                    ActPax: Int,
                    TranPax: Int,
                    RunwayID: String,
                    BaggageReclaimId: String,
                    FlightID: Int,
                    AirportID: String,
                    Terminal: String,
                    rawICAO: String,
                    rawIATA: String,
                    Origin: String,
                    SchDT: String,
                    Scheduled: Long,
                    PcpTime: Long,
                    LastKnownPax: Option[Int] = None) {
  lazy val ICAO: String = Arrival.standardiseFlightCode(rawICAO)
  lazy val IATA: String = Arrival.standardiseFlightCode(rawIATA)

  lazy val flightNumber: Int = {
    val bestCode = (IATA, ICAO) match {
      case (iata, _) if iata != "" => iata
      case (_, icao) if icao != "" => icao
      case _ => ""
    }

    bestCode match {
      case Arrival.flightCodeRegex(_, fn, _) => fn.toInt
      case _ => 0
    }
  }

  lazy val uniqueId: Int = s"$Terminal$Scheduled$flightNumber}".hashCode
}

object Arrival {
  val flightCodeRegex: Regex = "^([A-Z0-9]{2,3}?)([0-9]{1,4})([A-Z]?)$".r

  def summaryString(arrival: Arrival): TerminalName = arrival.AirportID + "/" + arrival.Terminal + "@" + arrival.SchDT + "!" + arrival.IATA

  def standardiseFlightCode(flightCode: String): String = {
    val flightCodeRegex = "^([A-Z0-9]{2,3}?)([0-9]{1,4})([A-Z]?)$".r

    flightCode match {
      case flightCodeRegex(operator, flightNumber, suffix) =>
        val number = f"${flightNumber.toInt}%04d"
        f"$operator$number$suffix"
      case _ => flightCode
    }
  }
}

trait SDateLike {

  def ddMMyyString: String = f"${getDate}%02d/${getMonth}%02d/${getFullYear - 2000}%02d"

  def getFullYear(): Int

  def getMonth(): Int

  def getDate(): Int

  def getHours(): Int

  def getMinutes(): Int

  def millisSinceEpoch: Long

  def toISOString(): String

  def addDays(daysToAdd: Int): SDateLike

  def addHours(hoursToAdd: Int): SDateLike

  def addMinutes(minutesToAdd: Int): SDateLike

  def toLocalDateTimeString(): String = f"${getFullYear()}-${getMonth()}%02d-${getDate()}%02d ${getHours()}%02d:${getMinutes()}%02d"

  def toHoursAndMinutes(): String = f"${getHours()}%02d:${getMinutes()}%02d"

  def prettyDateTime(): String = f"${getDate()}%02d-${getMonth()}%02d-${getFullYear()} ${getHours()}%02d:${getMinutes()}%02d"

  override def toString: String = f"${getFullYear()}-${getMonth()}%02d-${getDate()}%02dT${getHours()}%02d${getMinutes()}%02d"
}


object CrunchResult {
  def empty = CrunchResult(0, 0, Vector[Int](), List())
}


case class CrunchResult(
                         firstTimeMillis: Long,
                         intervalMillis: Long,
                         recommendedDesks: IndexedSeq[Int],
                         waitTimes: Seq[Int])

case class AirportInfo(airportName: String, city: String, country: String, code: String)

object FlightsApi {

  case class Flights(flights: List[Arrival])

  case class FlightsWithSplits(flights: List[ApiFlightWithSplits])

  type TerminalName = String

  type QueueName = String
}

sealed trait SplitCounts

case class ApiPaxTypeAndQueueCount(passengerType: PaxType, queueType: String, paxCount: Double) extends SplitCounts

object PassengerSplits {
  type QueueType = String

  type PaxTypeAndQueueCounts = List[SplitsPaxTypeAndQueueCount]

  case class SplitsPaxTypeAndQueueCount(passengerType: PaxType, queueType: QueueType, paxCount: Int)

  case object FlightsNotFound

  case class FlightNotFound(carrierCode: String, flightCode: String, scheduledArrivalDateTime: MilliDate)

  case class VoyagePaxSplits(destinationPort: String, carrierCode: String,
                             voyageNumber: String,
                             totalPaxCount: Int,
                             scheduledArrivalDateTime: MilliDate,
                             paxSplits: List[SplitsPaxTypeAndQueueCount])

}

case class DeskStat(desks: Option[Int], waitTime: Option[Int])

case class ActualDeskStats(desks: Map[String, Map[String, Map[Long, DeskStat]]])

object CrunchApi {
  type MillisSinceEpoch = Long

  case class CrunchState(crunchFirstMinuteMillis: MillisSinceEpoch,
                         numberOfMinutes: Int,
                         flights: Set[ApiFlightWithSplits],
                         crunchMinutes: Set[CrunchMinute])

  case class PortState(flights: Map[Int, ApiFlightWithSplits],
                       crunchMinutes: Map[Int, CrunchMinute],
                       staffMinutes: Map[Int, StaffMinute])

  sealed trait Minute {
    val minute: MillisSinceEpoch
  }

  case class StaffMinute(terminalName: TerminalName,
                         minute: MillisSinceEpoch,
                         staff: Int,
                         lastUpdated: Option[MillisSinceEpoch] = None) extends Minute {
    lazy val key: Int = s"$terminalName$minute".hashCode
  }

  case class CrunchMinute(terminalName: TerminalName,
                          queueName: QueueName,
                          minute: MillisSinceEpoch,
                          paxLoad: Double,
                          workLoad: Double,
                          deskRec: Int,
                          waitTime: Int,
                          deployedDesks: Option[Int] = None,
                          deployedWait: Option[Int] = None,
                          actDesks: Option[Int] = None,
                          actWait: Option[Int] = None,
                          lastUpdated: Option[MillisSinceEpoch] = None) extends Minute {
    def equals(candidate: CrunchMinute): Boolean =
      this.copy(lastUpdated = None) == candidate.copy(lastUpdated = None)

    lazy val key: Int = s"$terminalName$queueName$minute".hashCode
  }

  case class CrunchMinutes(crunchMinutes: Set[CrunchMinute])

  case class CrunchUpdates(latest: MillisSinceEpoch, flights: Set[ApiFlightWithSplits], minutes: Set[CrunchMinute])

  case class ForecastTimeSlot(startMillis: MillisSinceEpoch, available: Int, required: Int)

//  case class ForecastPeriod(days: Iterable[Iterable[ForecastTimeSlot]])
  case class ForecastPeriod(days: Map[MillisSinceEpoch, Seq[ForecastTimeSlot]])

  def groupByX(groupSize: Int)(crunchMinutes: Seq[(MillisSinceEpoch, Set[CrunchMinute])], terminalName: TerminalName, queueOrder: List[String]): Seq[(MillisSinceEpoch, List[CrunchMinute])] = {
    crunchMinutes.grouped(groupSize).toList.map(group => {
      val byQueueName = group.flatMap(_._2).groupBy(_.queueName)
      val startMinute = group.map(_._1).min
      val queueCrunchMinutes = queueOrder.collect {
        case qn if byQueueName.contains(qn) =>
          val queueMinutes = byQueueName(qn)
          CrunchMinute(
            terminalName,
            qn,
            startMinute,
            queueMinutes.map(_.paxLoad).sum,
            queueMinutes.map(_.workLoad).sum,
            queueMinutes.map(_.deskRec).max,
            queueMinutes.map(_.waitTime).max,
            Option(queueMinutes.map(_.deployedDesks.getOrElse(0)).max),
            Option(queueMinutes.map(_.deployedWait.getOrElse(0)).max),
            Option(queueMinutes.map(_.actDesks.getOrElse(0)).max),
            Option(queueMinutes.map(_.actWait.getOrElse(0)).max)
          )
      }
      (startMinute, queueCrunchMinutes)
    })
  }

  def terminalCrunchMinutesByMinute(minutes: Set[CrunchMinute], terminalName: TerminalName): Seq[(MillisSinceEpoch, Set[CrunchMinute])] = minutes
    .filter(_.terminalName == terminalName)
    .groupBy(_.minute)
    .toList
    .sortBy(_._1)

}

trait Api {
  def airportInfoByAirportCode(code: String): Future[Option[AirportInfo]]

  def airportInfosByAirportCodes(codes: Set[String]): Future[Map[String, AirportInfo]]

  def airportConfiguration(): AirportConfig

  def saveShifts(rawShifts: String): Unit

  def getShifts(pointIntTime: MillisSinceEpoch): Future[String]

  def saveFixedPoints(rawFixedPoints: String): Unit

  def getFixedPoints(pointIntTime: MillisSinceEpoch): Future[String]

  def saveStaffMovements(staffMovements: Seq[StaffMovement]): Unit

  def getStaffMovements(pointIntTime: MillisSinceEpoch): Future[Seq[StaffMovement]]

  def getCrunchStateForDay(day: MillisSinceEpoch): Future[Option[CrunchState]]

  def getCrunchStateForPointInTime(pointInTime: MillisSinceEpoch): Future[Option[CrunchState]]

  def getCrunchUpdates(sinceMillis: MillisSinceEpoch): Future[Option[CrunchUpdates]]

  def forecastWeekSummary(startDay: MillisSinceEpoch, terminal: TerminalName): Future[Option[ForecastPeriod]]
}
