package services

import org.joda.time.DateTime
import services.PassengerInfoRouterActor.{PaxTypeAndQueueCount, VoyagePaxSplits, VoyagesPaxSplits}
import services.PassengerQueueTypes.{PaxTypes, Queues}
import services.PaxLoad.PaxType
import spatutorial.shared.ApiFlight
import utest.{TestSuite, _}

import scala.collection.immutable.Iterable


object PaxLoad {
  type PaxType = (String, String)
}

case class PaxLoad(time: DateTime, nbPax: Double, paxType: PaxType)

case class SplitRatio(paxType: PaxType, ratio: Double)

object PaxLoadCalculator {
  def splitRatios(flight: ApiFlight) = List(
    SplitRatio((PaxTypes.eeaMachineReadable, Queues.eeaDesk), 0.5),
    SplitRatio((PaxTypes.eeaMachineReadable, Queues.eGate), 0.5)
  )
  val paxOffFlowRate = 20

  def getWorkload(paxLoad: PaxLoad): WL = {
    WL(paxLoad.time.getMillis() / 1000, paxLoad.nbPax)
  }

  def getQueueWorkloads(flights: List[ApiFlight]) = {

    val voyagePaxSplits: List[VoyagePaxSplits] = flights
      .map(flight => getVoyagePaxSplitsFromApiFlight(flight, splitRatios(flight)))
    val paxLoadsByDesk = voyagePaxSplits
      .map(vps => calculateVoyagePaxLoadByDesk(vps, paxOffFlowRate))

    val queueWorkloads: List[Iterable[QueueWorkloads]] = paxLoadsByDesk
      .map(paxloadsToQueueWorkloads)
    queueWorkloads
      .reduceLeft((a, b) => combineQueues(a.toList, b.toList))
  }


  def paxloadsToQueueWorkloads(queuePaxloads: Map[String, Seq[PaxLoad]]): Iterable[QueueWorkloads] = {
    queuePaxloads.map((queuePaxload: (String, Seq[PaxLoad])) =>
      QueueWorkloads(
        queuePaxload._1,
        queuePaxload._2.map((paxLoad: PaxLoad) => getWorkload(paxLoad)),
        queuePaxload._2.map((paxLoad: PaxLoad) => Pax(paxLoad.time.getMillis() / 1000, paxLoad.nbPax))
      )
    )
  }

  def getFlightPaxSplits(flight: ApiFlight, splitRatios: List[SplitRatio]) = {
    splitRatios.map(splitRatio => PaxTypeAndQueueCount(splitRatio.paxType._1, splitRatio.paxType._2, splitRatio.ratio * flight.ActPax))
  }

  val paxType = (PassengerQueueTypes.PaxTypes.eeaMachineReadable, PassengerQueueTypes.Queues.eeaDesk)

  def getVoyagePaxSplitsFromApiFlight(apiFlight: ApiFlight, splitRatios: List[SplitRatio]): VoyagePaxSplits = {
    VoyagePaxSplits(apiFlight.AirportID, apiFlight.IATA, apiFlight.ActPax, DateTime.parse(apiFlight.SchDT), getFlightPaxSplits(apiFlight, splitRatios))
  }

  def calculateVoyagePaxLoadByDesk(voyagePaxSplits: VoyagePaxSplits, flowRate: => Int): Map[String, Seq[PaxLoad]] = {
    val firstMinute = voyagePaxSplits.scheduledArrivalDateTime
    val groupedByDesk: Map[String, Seq[PaxTypeAndQueueCount]] = voyagePaxSplits.paxSplits.groupBy(_.queueType)
    groupedByDesk.mapValues(
      (paxTypeAndCount: Seq[PaxTypeAndQueueCount]) => {
        val totalPax = paxTypeAndCount.map(_.paxCount).sum
        val headPaxType = paxTypeAndCount.head
        calcPaxLoad(firstMinute, totalPax, (headPaxType.passengerType, headPaxType.queueType), flowRate).reverse
      }
    )
  }

  def calcPaxLoad(currMinute: DateTime, remaining: Double, paxType: PaxType, flowRate: Int): List[PaxLoad] = {
    if (remaining <= flowRate)
      PaxLoad(currMinute, remaining, paxType) :: Nil
    else
      calcPaxLoad(currMinute.plusMinutes(1), remaining - flowRate, paxType, flowRate) :::
        PaxLoad(currMinute, flowRate, paxType) :: Nil
  }

  def combineWorkloads(l1: Seq[WL], l2: Seq[WL]) = {
    def foldInto(agg: Map[Long, Double], list: List[WL]): Map[Long, Double] = list.foldLeft(agg)(
      (agg, wl) => {
        val cv = agg.getOrElse(wl.time, 0d)
        agg + (wl.time -> (cv + wl.workload))
      }
    )
    val res1 = foldInto(Map[Long, Double](), l1.toList)
    val res2 = foldInto(res1, l2.toList).map(timeWorkload => WL(timeWorkload._1, timeWorkload._2)).toSeq

    res2
  }

  def combinePaxLoads(l1: Seq[Pax], l2: Seq[Pax]) = {
    def foldInto(agg: Map[Long, Double], list: List[Pax]): Map[Long, Double] = list.foldLeft(agg)(
      (agg, pax) => {
        val cv = agg.getOrElse(pax.time, 0d)
        agg + (pax.time -> (cv + pax.pax))
      }
    )
    val res1 = foldInto(Map[Long, Double](), l1.toList)
    val res2 = foldInto(res1, l2.toList).map(timeWorkload => Pax(timeWorkload._1, timeWorkload._2)).toSeq

    res2
  }

  def combineQueues(l1: List[QueueWorkloads], l2: List[QueueWorkloads]) = {
    def foldInto(agg: Map[String, QueueWorkloads], list: List[QueueWorkloads]) = list.foldLeft(agg)(
      (agg, qw) => {
        val cv = agg.getOrElse(qw.queueName, QueueWorkloads(qw.queueName, Seq[WL](), Seq[Pax]()))
        agg + (
          qw.queueName ->
            QueueWorkloads(
              qw.queueName,
              combineWorkloads(cv.workloadsByMinute, qw.workloadsByMinute),
              combinePaxLoads(cv.paxByMinute, qw.paxByMinute)
            )
          )
      }
    )
    val res1 = foldInto(Map[String, QueueWorkloads](), l1)
    val res2 = foldInto(res1, l2).map(qw => qw._2)
    res2
  }
}

object PassengerQueueTypes {

  object Queues {
    val eeaDesk = "eeaDesk"
    val eGate = "eGate"
    val nonEeaDesk = "nonEeaDesk"
  }

  object PaxTypes {
    val eeaNonMachineReadable = "eeaNonMachineReadable"
    val visaNational = "visaNational"
    val eeaMachineReadable = "eeaMachineReadable"
    val nonVisaNational = "nonVisaNational"
  }

  val eGatePercentage = 0.6

  type QueueType = Symbol

  //  type PaxTypeAndQueue = (PassengerType, QueueType)
  case class PaxTypeAndQueueCount(passengerType: String, queueType: Symbol, paxCount: Int)

  type PaxTypeAndQueueCounts = Seq[PaxTypeAndQueueCount]
}

object PassengerInfoRouterActor {
  type PaxTypeAndQueueCounts = Seq[PaxTypeAndQueueCount]

  type FlightCode = String

  case class VoyagePaxSplits(destinationPort: String,
                             flightCode: FlightCode,
                             totalPaxCount: Int,
                             scheduledArrivalDateTime: DateTime,
                             paxSplits: PaxTypeAndQueueCounts)

  case class VoyagesPaxSplits(voyageSplits: List[VoyagePaxSplits])

  case class PaxTypeAndQueueCount(passengerType: String, queueType: String, paxCount: Double)

}

case class WorkloadResponse(terminals: Seq[TerminalWorkload])

case class TerminalWorkload(terminalName: String,
                            queues: Seq[QueueWorkloads])

case class QueueWorkloads(queueName: String,
                          workloadsByMinute: Seq[WL],
                          paxByMinute: Seq[Pax]
                         ) {
  //  def workloadsByPeriod(n: Int): Iterator[WL] = workloadsByMinute.grouped(n).map(g => WL(g.head.time, g.map(_.workload).sum))

  //  def paxByPeriod(n: Int) = workloadsByMinute.grouped(n).map(_.sum)
}

case class WL(time: Long, workload: Double)

case class Pax(time: Long, pax: Double)

case class DeskRec(time: Long, desks: Int)

case class WorkloadTimeslot(time: Long, workload: Double, pax: Int, desRec: Int, waitTimes: Int)

object WorkloadCalculatorTests extends TestSuite {
  def createApiFlight(iataFlightCode: String, airportCode: String, totalPax: Int, scheduledDatetime: String): ApiFlight =
    ApiFlight(
      Operator = "",
      Status = "",
      EstDT = "",
      ActDT = "",
      EstChoxDT = "",
      ActChoxDT = "",
      Gate = "",
      Stand = "",
      MaxPax = 1,
      ActPax = totalPax,
      TranPax = 0,
      RunwayID = "",
      BaggageReclaimId = "",
      FlightID = 1,
      AirportID = airportCode,
      Terminal = "",
      ICAO = "",
      IATA = iataFlightCode,
      Origin = "",
      SchDT = scheduledDatetime
    )

  def tests = TestSuite {
    'WorkloadCalculator - {

      "Given an ApiFlight and a multi-split definition, we should get back corresponding splits of the APIFlight's passengers" - {
        val flight = createApiFlight("BA0001", "LHR", 50, "2020-01-01T00:00:00")
        val splitRatios = List(
          SplitRatio((PaxTypes.eeaMachineReadable, Queues.eeaDesk), 0.5),
          SplitRatio((PaxTypes.eeaMachineReadable, Queues.eGate), 0.5)
        )

        val expectedPaxSplits = List(
          PaxTypeAndQueueCount(PaxTypes.eeaMachineReadable, Queues.eeaDesk, 25),
          PaxTypeAndQueueCount(PaxTypes.eeaMachineReadable, Queues.eGate, 25)
        )
        val actualPaxSplits = PaxLoadCalculator.getFlightPaxSplits(flight, splitRatios)

        assert(expectedPaxSplits == actualPaxSplits)
      }

      "Given an ApiFlight and single split ratio, we should get back a VoyagePaxSplits containing a single pax split" - {
        val flight = createApiFlight("BA0001", "LHR", 50, "2020-01-01T00:00:00")
        val splitRatios = List(SplitRatio((PaxTypes.eeaMachineReadable, Queues.eeaDesk), 1.0))
        val voyagesPaxSplit = PaxLoadCalculator.getVoyagePaxSplitsFromApiFlight(flight, splitRatios)

        assert(voyagesPaxSplit ==
          VoyagePaxSplits("LHR", "BA0001", 50, DateTime.parse(flight.SchDT),
            List(PaxTypeAndQueueCount(PaxTypes.eeaMachineReadable, Queues.eeaDesk, 50))
          )
        )
      }

      "Given a map of queues to pax load, we should get back a list of queue workloads" - {
        val paxload = Map(
          Queues.eeaDesk ->
            Seq(
              PaxLoad(DateTime.parse("2020-01-01T00:00:00"), 20, (PaxTypes.eeaMachineReadable, Queues.eeaDesk)),
              PaxLoad(DateTime.parse("2020-01-01T00:01:00"), 20, (PaxTypes.eeaMachineReadable, Queues.eeaDesk))
            ),
          Queues.eGate ->
            Seq(PaxLoad(DateTime.parse("2020-01-01T00:00:00"), 20, (PaxTypes.eeaMachineReadable, Queues.eGate)))
        )

        val expected = List(
          QueueWorkloads(
            Queues.eeaDesk,
            Seq(WL(DateTime.parse("2020-01-01T00:00:00").getMillis / 1000, 20), WL(DateTime.parse("2020-01-01T00:01:00").getMillis / 1000, 20)),
            Seq(Pax(DateTime.parse("2020-01-01T00:00:00").getMillis / 1000, 20), Pax(DateTime.parse("2020-01-01T00:01:00").getMillis / 1000, 20))
          ),
          QueueWorkloads(
            Queues.eGate,
            Seq(WL(DateTime.parse("2020-01-01T00:00:00").getMillis / 1000, 20)),
            Seq(Pax(DateTime.parse("2020-01-01T00:00:00").getMillis / 1000, 20))
          )
        )

        val result = PaxLoadCalculator.paxloadsToQueueWorkloads(paxload)

        assert(result == expected)
      }

      "Given two lists of WL return an aggregation of them" - {
        val l1 = List(WL(1577836800, 40.0), WL(1577836860, 10.0))
        val l2 = List(WL(1577836800, 40.0), WL(1577836860, 10.0))
        val combined = PaxLoadCalculator.combineWorkloads(l1, l2)
        assert(combined == List(WL(1577836800, 80.0), WL(1577836860, 20.0)))
      }


      "Given a list of flights, then we should get back a list of QueueWorkloads" - {
        println("some stuff")
        val flights = List(
          createApiFlight("BA0001", "LHR", 50, "2020-01-01T00:00:00"),
          createApiFlight("BA0002", "LHR", 50, "2020-01-01T00:00:00")
        )

        val queueWorkloads = PaxLoadCalculator.getQueueWorkloads(flights)

        assert(queueWorkloads == List(
          QueueWorkloads(Queues.eeaDesk, List(WL(1577836800, 40.0), WL(1577836860, 10.0)), List(Pax(1577836800, 40.0), Pax(1577836860, 10.0))),
          QueueWorkloads(Queues.eGate, List(WL(1577836800, 40.0), WL(1577836860, 10.0)), List(Pax(1577836800, 40.0), Pax(1577836860, 10.0)))
        ))
      }
    }
  }
}
