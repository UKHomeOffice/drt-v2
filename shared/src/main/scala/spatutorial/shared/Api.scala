package drt.shared

import java.util.Date

import drt.shared.Crunch.CrunchState
import drt.shared.FlightsApi._
import drt.shared.PassengerSplits.SplitsPaxTypeAndQueueCount
import drt.shared.Simulations.{QueueSimulationResult, TerminalSimulationResultsFull}

import scala.collection.immutable._
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

case class ApiFlightWithSplits(apiFlight: Arrival, splits: Set[ApiSplits])

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
  lazy val ICAO = Arrival.standardiseFlightCode(rawICAO)
  lazy val IATA = Arrival.standardiseFlightCode(rawIATA)
}

object Arrival {
  def summaryString(arrival: Arrival): TerminalName = arrival.AirportID + "/" + arrival.Terminal + "@" + arrival.SchDT + "!" + arrival.IATA

  def standardiseFlightCode(flightCode: String): String = {
    val flightCodeRegex = "^([A-Z0-9]{2,3}?)([0-9]{1,4})([A-Z]?)$".r

    flightCode match {
      case flightCodeRegex(operator, flightNumber, suffix) =>
        f"$operator${flightNumber.toInt}%04d$suffix"
      case _ => flightCode
    }
  }
}

//This is used for handling historic snapshots, do not change or remove.
@SerialVersionUID(2414259893568926057L)
case class ApiFlight(
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
                      PcpTime: Long) {

  lazy val ICAO: String = ApiFlight.standardiseFlightCode(rawICAO)
  lazy val IATA: String = ApiFlight.standardiseFlightCode(rawIATA)


}

object ApiFlight {

  def standardiseFlightCode(flightCode: String): String = {
    val flightCodeRegex = "^([A-Z0-9]{2,3}?)([0-9]{1,4})([A-Z]?)$".r

    flightCode match {
      case flightCodeRegex(operator, flightNumber, suffix) =>
        f"$operator${flightNumber.toInt}%04d$suffix"
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

case class NoCrunchAvailable()

object Simulations {

  case class QueueSimulationResult(recommendedDesks: List[DeskRec], waitTimes: List[Int])

  type TerminalSimulationResultsFull = Map[QueueName, QueueSimulationResult]
}

object FlightsApi {

  case class Flights(flights: List[Arrival])

  case class FlightsWithSplits(flights: List[ApiFlightWithSplits])

  type QueuePaxAndWorkLoads = (Seq[WL], Seq[Pax])

  type PortPaxAndWorkLoads[L] = Map[TerminalName, Map[QueueName, L]]

  type TerminalPaxAndWorkLoads[L] = Map[QueueName, L]

  type TerminalName = String

  type QueueName = String
}

trait FlightsApi {
  def flightsWithSplits(startTimeEpoch: Long, endTimeEpoch: Long): Future[Either[FlightsNotReady, FlightsWithSplits]]
}

case class AirportInfo(airportName: String, city: String, country: String, code: String)

trait WorkloadsHelpers {
  val oneMinute = 60000L

  def terminalWorkloadsForPeriod(workloads: Map[String, Seq[WL]], periodMinutes: NumericRange[Long]): Map[String, List[Double]] = {
    workloads.mapValues((qwl: Seq[WL]) => {
      val queuesMinutesFoldedIntoWholeDay = foldQueuesMinutesIntoDay(periodMinutes, workloadToWorkLoadByTime(qwl))
      queuesWorkloadSortedByMinuteAsFullyPopulatedWorkloadSeq(queuesMinutesFoldedIntoWholeDay)
    })
  }

  def queueWorkloadsForPeriod(qwl: Seq[WL], periodMinutes: NumericRange[Long]): List[Double] = {
    val queuesMinutesFoldedIntoWholeDay = foldQueuesMinutesIntoDay(periodMinutes, workloadToWorkLoadByTime(qwl))
    queuesWorkloadSortedByMinuteAsFullyPopulatedWorkloadSeq(queuesMinutesFoldedIntoWholeDay)
  }

  def foldQueuesMinutesIntoDay(allMins: NumericRange[Long], workloadsByMinute: Map[Long, Double]): Map[Long, Double] = {
    allMins.foldLeft(Map[Long, Double]()) {
      (minuteMap, minute) => minuteMap + (minute -> workloadsByMinute.getOrElse(minute, 0d))
    }
  }

  def workloadPeriodByQueue(workloads: Map[QueueName, QueuePaxAndWorkLoads], periodMinutes: NumericRange[Long]): Map[QueueName, List[Double]] = {
    loadPeriodByQueue(workloads, periodMinutes, workloadByMillis)
  }

  def paxloadPeriodByQueue(workloads: Map[QueueName, QueuePaxAndWorkLoads], periodMinutes: NumericRange[Long]): Map[QueueName, List[Double]] = {
    loadPeriodByQueue(workloads, periodMinutes, paxloadByMillis)
  }

  def loadPeriodByQueue(workloads: Map[QueueName, QueuePaxAndWorkLoads], periodMinutes: NumericRange[Long], loadByMillis: QueuePaxAndWorkLoads => Map[Long, Double]): Map[QueueName, List[Double]] = {
    workloads.mapValues(qwl => {
      val allPaxloadByMinuteForThisQueue: Map[Long, Double] = loadByMillis(qwl)
      val queuesMinutesFoldedIntoWholeDay = foldQueuesMinutesIntoDay(periodMinutes, allPaxloadByMinuteForThisQueue)
      queuesWorkloadSortedByMinuteAsFullyPopulatedWorkloadSeq(queuesMinutesFoldedIntoWholeDay)
    })
  }

  def minutesForPeriod(startFromMilli: Long, numberOfHours: Int): NumericRange[Long] = {
    wholeDaysMinutesFromAllQueues(startFromMilli, numberOfHours)
  }

  def workloadsByPeriod(workloadsByMinute: Seq[WL], n: Int): scala.Seq[WL] =
    workloadsByMinute.grouped(n).toSeq.map((g: Seq[WL]) => WL(g.head.time, g.map(_.workload).sum))

  def queuesWorkloadSortedByMinuteAsFullyPopulatedWorkloadSeq(res: Map[Long, Double]): List[Double] = {
    res.toSeq.sortBy(_._1).map(_._2).toList
  }

  def workloadToWorkLoadByTime(workload: Seq[WL]): Map[Long, Double] = {
    workload.map((wl) => (wl.time, wl.workload)).toMap
  }

  def workloadByMillis(workloads1: QueuePaxAndWorkLoads): Map[Long, Double] = {
    workloads1._1.map((wl) => (wl.time, wl.workload)).toMap
  }

  def paxloadByMillis(paxloads: QueuePaxAndWorkLoads): Map[Long, Double] = {
    paxloads._2.map((paxLoad) => (paxLoad.time, paxLoad.pax)).toMap
  }

  def wholeDaysMinutesFromAllQueues(timesMin: Long, numberOfHours: Int = 24): NumericRange[Long] = {
    val timeMinPlusOneDay: Long = timesMin + oneMinute * 60 * numberOfHours
    timesMin until timeMinPlusOneDay by oneMinute
  }

  def midnightBeforeNow(): Long = {
    val now = new Date()
    val thisMorning = new Date(now.getYear, now.getMonth, now.getDate)
    thisMorning.getTime()
  }

  def midnightBeforeEarliestWorkload(workloads: Seq[QueuePaxAndWorkLoads]): Long = {
    val minWorkloadTime = workloads.map(qwl => qwl._1.map(_.time).min).min
    val dateTimeOfMinWorkload = new Date(minWorkloadTime)

    new Date(dateTimeOfMinWorkload.getYear, dateTimeOfMinWorkload.getMonth, dateTimeOfMinWorkload.getDate).getTime()
  }
}

object WorkloadsHelpers extends WorkloadsHelpers

case class WorkloadResponse(terminals: Seq[TerminalWorkload])

case class TerminalWorkload(terminalName: String,
                            queues: Seq[QueuePaxAndWorkLoads])

trait Time {
  def time: Long
}

case class WL(time: Long, workload: Double) extends Time

case class Pax(time: Long, pax: Double) extends Time

case class WorkloadsNotReady()

case class DeskRec(time: Long, desks: Int)

case class WorkloadTimeslot(time: Long, workload: Double, pax: Int, desRec: Int, waitTimes: Int)


object PassengerQueueTypes {
  def egatePercentage = 0.6d

  type PaxTypeAndQueueCounts = List[SplitsPaxTypeAndQueueCount]
}

sealed trait SplitCounts

case class ApiPaxTypeAndQueueCount(passengerType: PaxType, queueType: String, paxCount: Double) extends SplitCounts

object PassengerSplits {
  type QueueType = String

  case class SplitsPaxTypeAndQueueCount(passengerType: PaxType, queueType: QueueType, paxCount: Int)

  case object FlightsNotFound

  case class FlightNotFound(carrierCode: String, flightCode: String, scheduledArrivalDateTime: MilliDate)

  case class VoyagePaxSplits(destinationPort: String, carrierCode: String,
                             voyageNumber: String,
                             totalPaxCount: Int,
                             scheduledArrivalDateTime: MilliDate,
                             paxSplits: List[SplitsPaxTypeAndQueueCount])

}

trait WorkloadsApi {
  def getWorkloads(pointInTime: Long): Future[Either[WorkloadsNotReady, PortLoads]]
}


case class PortLoads(loads: PortPaxAndWorkLoads[QueuePaxAndWorkLoads])

case class DeskStat(desks: Option[Int], waitTime: Option[Int])

case class ActualDeskStats(desks: Map[String, Map[String, Map[Long, DeskStat]]])

case class TerminalCrunchResult(queuesAndCrunchResults: List[(QueueName, Either[NoCrunchAvailable, CrunchResult])])

object Crunch {
  type MillisSinceEpoch = Long

  case class CrunchState(
                          crunchFirstMinuteMillis: MillisSinceEpoch,
                          numberOfMinutes: Int,
                          flights: Set[ApiFlightWithSplits],
                          crunchMinutes: Set[CrunchMinute])

  case class CrunchMinute(
                           terminalName: TerminalName,
                           queueName: QueueName,
                           minute: MillisSinceEpoch,
                           paxLoad: Double,
                           workLoad: Double,
                           deskRec: Int,
                           waitTime: Int,
                           deployedDesks: Option[Int] = None,
                           deployedWait: Option[Int] = None,
                           actDesks: Option[Int] = None,
                           actWait: Option[Int] = None)

  case class CrunchMinutes(crunchMinutes: Set[CrunchMinute])

}

//todo the size of this api is already upsetting me, can we make it smaller while keeping autowiring?
trait Api extends FlightsApi with WorkloadsApi {

  def airportInfoByAirportCode(code: String): Future[Option[AirportInfo]]

  def airportInfosByAirportCodes(codes: Set[String]): Future[Map[String, AirportInfo]]

  def getTerminalCrunchResult(terminalName: TerminalName, pointInTime: Long): Future[TerminalCrunchResult]

  def processWork(terminalName: TerminalName, queueName: QueueName, workloads: List[Double], desks: List[Int]): QueueSimulationResult

  def getTerminalSimulations(terminalName: TerminalName, workloads: Map[QueueName, List[Double]], desks: Map[QueueName, List[Int]]): TerminalSimulationResultsFull

  def airportConfiguration(): AirportConfig

  def saveShifts(rawShifts: String): Unit

  def getShifts(pointIntTime: Long): Future[String]

  def saveFixedPoints(rawFixedPoints: String): Unit

  def getFixedPoints(pointIntTime: Long): Future[String]

  def saveStaffMovements(staffMovements: Seq[StaffMovement]): Unit

  def getStaffMovements(pointIntTime: Long): Future[Seq[StaffMovement]]

  def getActualDeskStats(pointInTime: Long): Future[ActualDeskStats]

  def getCrunchState(pointInTime: Long): Future[Option[CrunchState]]
}
