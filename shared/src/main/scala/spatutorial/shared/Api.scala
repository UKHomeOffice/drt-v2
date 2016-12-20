package spatutorial.shared

import java.util.Date
import spatutorial.shared.FlightsApi._
import scala.collection.immutable._
import scala.concurrent.Future

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

case class CrunchResult(recommendedDesks: IndexedSeq[Int], waitTimes: Seq[Int])

object CrunchResult {
  def empty = CrunchResult(Vector[Int](), Nil)
}

case class NoCrunchAvailable()

case class SimulationResult(recommendedDesks: IndexedSeq[DeskRec], waitTimes: Seq[Int])

object FlightsApi {
  case class Flights(flights: List[ApiFlight])

  type QueuePaxAndWorkLoads = (Seq[WL], Seq[Pax])

  type TerminalName = String

  type QueueName = String
}

trait FlightsApi {
  def flights(startTimeEpoch: Long, endTimeEpoch: Long): Flights
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


trait WorkloadsApi {
  def getWorkloads(): Future[Map[TerminalName, Map[QueueName, QueuePaxAndWorkLoads]]]
}

//todo the size of this api is already upsetting me, can we make it smaller while keeping autowiring?
trait Api extends FlightsApi with WorkloadsApi {

  def welcomeMsg(name: String): String

  def airportInfoByAirportCode(code: String): Future[Option[AirportInfo]]

  def airportInfosByAirportCodes(codes: Set[String]): Future[Map[String, AirportInfo]]

  //  def crunch(terminalName: TerminalName, queueName: QueueName, workloads: List[Double]): Future[CrunchResult]

  def getLatestCrunchResult(terminalName: TerminalName, queueName: QueueName): Future[Either[NoCrunchAvailable, CrunchResult]]

  def processWork(terminalName: TerminalName, queueName: QueueName, workloads: List[Double], desks: List[Int]): SimulationResult

  def airportConfiguration(): AirportConfig
}
