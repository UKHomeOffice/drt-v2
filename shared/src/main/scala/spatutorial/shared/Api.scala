package drt.shared

import java.util.Date

import drt.shared.FlightsApi._
import drt.shared.PassengerQueueTypes.PaxTypeAndQueueCounts
import drt.shared.PassengerSplits.{FlightNotFound, PaxTypeAndQueueCount, VoyagePaxSplits}

import scala.collection.immutable._
import scala.concurrent.Future


object DeskAndPaxTypeCombinations {
  val egate = "egate eea-machine-readable"
  val deskEeaNonMachineReadable = "desk eea-non-machine-readable"
  val deskEea = "eeaDesk eea-machine-readable"
  val nationalsDeskVisa = "nationalsDesk national-visa"
  val nationalsDeskNonVisa = "nationalsDesk national-non-visa"
}

case class MilliDate(millisSinceEpoch: Long) extends Ordered[MilliDate] {
  def compare(that: MilliDate) = millisSinceEpoch.compare(that.millisSinceEpoch)
}

object FlightParsing {
  val iataRe = """(\w\w)(\d{1,4})(\w)?""".r

  def parseIataToCarrierCodeVoyageNumber(iata: String): Option[(String, String)] = {
    iata match {
      case iataRe(carriercode, voyageNumber, suffix) => Option((carriercode, voyageNumber))
      case what => None
    }
  }


}

case class SplitR(name: String, size: Double)
case class ApiSplits(splits: List[ApiPaxTypeAndQueueCount], source: String)

case class ApiFlightWithSplits(apiFlight: ApiFlight, splits: ApiSplits)

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
                      ICAO: String,
                      IATA: String,
                      Origin: String,
                      SchDT: String,
                      PcpTime: Long)

trait SDateLike {

  def ddMMyyString: String = f"${getDate}%02d/${getMonth}%02d/${getFullYear - 2000}%02d"

  def getFullYear(): Int

  def getMonth(): Int

  def getDate(): Int

  def getHours(): Int

  def getMinutes(): Int

  def millisSinceEpoch: Long

  def addDays(daysToAdd: Int): SDateLike

  def addHours(hoursToAdd: Int): SDateLike

  def toLocalDateTimeString(): String = f"${getFullYear()}-${getMonth()}%02d-${getDate()}%02d ${getHours()}%02d:${getMinutes()}%02d"

  def toApiFlightString(): String = f"${getFullYear()}-${getMonth()}%02d-${getDate()}%02dT${getHours()}%02d:${getMinutes()}%02d"

  override def toString: String = f"${getFullYear()}-${getMonth()}%02d-${getDate()}%02dT${getHours()}%02d${getMinutes()}%02d"
}


object CrunchResult {
  def empty = CrunchResult(0, 0, Vector[Int](), Nil)
}

case class CrunchResult(
                         firstTimeMillis: Long,
                         intervalMillis: Long,
                         recommendedDesks: IndexedSeq[Int],
                         waitTimes: Seq[Int])

case class NoCrunchAvailable()

case class SimulationResult(recommendedDesks: IndexedSeq[DeskRec], waitTimes: Seq[Int])

object FlightsApi {

  case class Flights(flights: List[ApiFlight])

  case class FlightsWithSplits(flights: List[ApiFlightWithSplits])

  type QueuePaxAndWorkLoads = (Seq[WL], Seq[Pax])

  type TerminalName = String

  type QueueName = String
}

trait FlightsApi {
  def flights(startTimeEpoch: Long, endTimeEpoch: Long): Flights

  def flightsWithSplits(startTimeEpoch: Long, endTimeEpoch: Long): Future[FlightsWithSplits]
}

case class AirportInfo(airportName: String, city: String, country: String, code: String)

trait WorkloadsHelpers {
  val oneMinute = 60000L

  def queueWorkloadsForPeriod(workloads: Map[String, Seq[WL]], periodMinutes: NumericRange[Long]): Map[String, List[Double]] = {
    workloads.mapValues((qwl: Seq[WL]) => {
      val queuesMinutesFoldedIntoWholeDay = foldQueuesMinutesIntoDay(periodMinutes, workloadToWorkLoadByTime(qwl))
      queuesWorkloadByMinuteAsFullyPopulatedWorkloadSeq(queuesMinutesFoldedIntoWholeDay)
    })
  }

  def foldQueuesMinutesIntoDay(allMins: NumericRange[Long], workloadsByMinute: Map[Long, Double]): Map[Long, Double] = {
    allMins.foldLeft(Map[Long, Double]()) {
      (minuteMap, minute) => minuteMap + (minute -> workloadsByMinute.getOrElse(minute, 0d))
    }
  }

  def workloadPeriodByQueue(workloads: Map[String, (Seq[WL], Seq[Pax])], periodMinutes: NumericRange[Long]): Map[String, List[Double]] = {
    loadPeriodByQueue(workloads, periodMinutes, workloadByMillis)
  }

  def paxloadPeriodByQueue(workloads: Map[String, QueuePaxAndWorkLoads], periodMinutes: NumericRange[Long]): Map[String, List[Double]] = {
    loadPeriodByQueue(workloads, periodMinutes, paxloadByMillis)
  }

  def loadPeriodByQueue(workloads: Map[String, QueuePaxAndWorkLoads], periodMinutes: NumericRange[Long], loadByMillis: QueuePaxAndWorkLoads => Map[Long, Double]): Map[String, List[Double]] = {
    workloads.mapValues(qwl => {
      val allPaxloadByMinuteForThisQueue: Map[Long, Double] = loadByMillis(qwl)
      val queuesMinutesFoldedIntoWholeDay = foldQueuesMinutesIntoDay(periodMinutes, allPaxloadByMinuteForThisQueue)
      queuesWorkloadByMinuteAsFullyPopulatedWorkloadSeq(queuesMinutesFoldedIntoWholeDay)
    })
  }

  def minutesForPeriod(startFromMilli: Long, numberOfHours: Int): NumericRange[Long] = {
    wholeDaysMinutesFromAllQueues(startFromMilli, numberOfHours)
  }

  def workloadsByPeriod(workloadsByMinute: Seq[WL], n: Int): scala.Seq[WL] =
    workloadsByMinute.grouped(n).toSeq.map((g: Seq[WL]) => WL(g.head.time, g.map(_.workload).sum))

  def queuesWorkloadByMinuteAsFullyPopulatedWorkloadSeq(res: Map[Long, Double]): List[Double] = {
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

case class DeskRec(time: Long, desks: Int)

case class WorkloadTimeslot(time: Long, workload: Double, pax: Int, desRec: Int, waitTimes: Int)


object PassengerQueueTypes {
//
//  object Queues {
//    val eeaDesk = "desk"
//    val egate = "egate"
//    val nationalsDesk = "nationalsDesk"
//  }

//  object PaxTypes {
//    val EeaNonMachineReadable = "eea-non-machine-readable"
//    val NationalVisa = "national-visa"
//    val EeaMachineReadable = "eea-machine-readable"
//    val NonNationalVisa = "national-non-visa"
//  }

  def egatePercentage = 0.6d

  type PaxTypeAndQueueCounts = List[PaxTypeAndQueueCount]
}

case class ApiPaxTypeAndQueueCount(passengerType: PaxType, queueType: String, paxCount: Int)

object PassengerSplits {
  type QueueType = String

  case class PaxTypeAndQueueCount(passengerType: PaxType, queueType: QueueType, paxCount: Int)

  case object FlightsNotFound

  case class FlightNotFound(carrierCode: String, flightCode: String, scheduledArrivalDateTime: MilliDate)

  case class VoyagePaxSplits(destinationPort: String, carrierCode: String,
                             voyageNumber: String,
                             totalPaxCount: Int,
                             scheduledArrivalDateTime: MilliDate,
                             paxSplits: List[PaxTypeAndQueueCount])

}

trait WorkloadsApi {
  def getWorkloads(): Future[Map[TerminalName, Map[QueueName, QueuePaxAndWorkLoads]]]
}

//todo the size of this api is already upsetting me, can we make it smaller while keeping autowiring?
trait Api extends FlightsApi with WorkloadsApi {

  def welcomeMsg(name: String): String

  def flightSplits(portCode: String, flightCode: String, scheduledDateTime: MilliDate): Future[Either[FlightNotFound, VoyagePaxSplits]]

  def airportInfoByAirportCode(code: String): Future[Option[AirportInfo]]

  def airportInfosByAirportCodes(codes: Set[String]): Future[Map[String, AirportInfo]]

  def getLatestCrunchResult(terminalName: TerminalName, queueName: QueueName): Future[Either[NoCrunchAvailable, CrunchResult]]

  def processWork(terminalName: TerminalName, queueName: QueueName, workloads: List[Double], desks: List[Int]): SimulationResult

  def airportConfiguration(): AirportConfig

  def saveShifts(rawShifts: String): Unit

  def getShifts(): Future[String]

  def saveStaffMovements(staffMovements: Seq[StaffMovement]): Unit

  def getStaffMovements(): Future[Seq[StaffMovement]]
}
