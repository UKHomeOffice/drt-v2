package services

import org.joda.time.DateTime
import services.PassengerInfoRouterActor.{PaxTypeAndQueueCount, VoyagePaxSplits, VoyagesPaxSplits}
import services.PassengerQueueTypes.{Desks, PaxTypes}
import services.PaxLoad.PaxType
import spatutorial.shared.ApiFlight
import utest.{TestSuite, _}


object PaxLoad {
  type PaxType = (String, Symbol)
}

case class PaxLoad(time: DateTime, nbPax: Int, paxType: PaxType) {

}

object PaxLoadCalculator {
  val paxType = (PassengerQueueTypes.PaxTypes.EEAMACHINEREADABLE, PassengerQueueTypes.Desks.eeaDesk)

  def flightWithSplits(apiFlight: ApiFlight): VoyagePaxSplits = {
    VoyagePaxSplits(apiFlight.AirportID, apiFlight.IATA, apiFlight.ActPax, DateTime.parse(apiFlight.SchDT), List(
      PaxTypeAndQueueCount(PaxTypes.EEAMACHINEREADABLE, Desks.eeaDesk, 1)
    ))
  }

  //  def getVoyagePaxSplitsFromApiFlight(apiFlight: ApiFlight): VoyagePaxSplits = {
  //
  //  }

  def getQueueWorkLoadsFromFlights(flights: Seq[ApiFlight]): Seq[QueueWorkloads] = {

    Seq(QueueWorkloads("name", Seq(WL(100, 100)), Seq(Pax(100, 100))))
  }

  def calculateVoyagePaxLoadByDesk(voyagePaxSplits: VoyagePaxSplits, flowRate: => Int): Map[Symbol, Seq[PaxLoad]] = {
    val firstMinute = voyagePaxSplits.scheduledArrivalDateTime
    val groupedByDesk: Map[Symbol, Seq[PaxTypeAndQueueCount]] = voyagePaxSplits.paxSplits.groupBy(_.queueType)
    groupedByDesk.mapValues(
      (paxTypeAndCount: Seq[PaxTypeAndQueueCount]) => {
        val totalPax = paxTypeAndCount.map(_.paxCount).sum
        val headPaxType = paxTypeAndCount.head
        calcPaxLoad(firstMinute, totalPax, (headPaxType.passengerType, headPaxType.queueType), flowRate).reverse
      }
    )
  }

  def calcPaxLoad(currMinute: DateTime, remaining: Int, paxType: PaxType, flowRate: Int): List[PaxLoad] = {
    if (remaining <= flowRate)
      PaxLoad(currMinute, remaining, paxType) :: Nil
    else
      calcPaxLoad(currMinute.plusMinutes(1), remaining - flowRate, paxType, flowRate) :::
        PaxLoad(currMinute, flowRate, paxType) :: Nil
  }
}

object PassengerQueueTypes {

  object Desks {
    val eeaDesk = 'desk
    val egate = 'egate
    val nationalsDesk = 'nationalsDesk
  }

  object PaxTypes {
    val EEANONMACHINEREADABLE = "eea-non-machine-readable"
    val NATIONALVISA = "national-visa"
    val EEAMACHINEREADABLE = "eea-machine-readable"
    val NATIONALNONVISA = "national-non-visa"
  }

  val egatePercentage = 0.6

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

  case class PaxTypeAndQueueCount(passengerType: String, queueType: Symbol, paxCount: Int)

}

case class WorkloadResponse(terminals: Seq[TerminalWorkload])

case class TerminalWorkload(terminalName: String,
                            queues: Seq[QueueWorkloads])

case class QueueWorkloads(queueName: String,
                          workloadsByMinute: Seq[WL],
                          paxByMinute: Seq[Pax]
                         ) {
  //  def workloadsByPeriod(n: Int): Iterator[WL] = workloadsByMinute.grouped(n).map(g => WL(g.head.time, g.map(_.workload).sum))

  //  def deskRecByPeriod(n: Int): Iterator[DeskRec] = deskRec.grouped(n).map(_.max)
  //
  //  def paxByPeriod(n: Int) = workloadsByMinute.grouped(n).map(_.sum)
}

case class WL(time: Long, workload: Double)

case class Pax(time: Long, pax: Int)

case class DeskRec(time: Long, desks: Int)

case class WorkloadTimeslot(time: Long, workload: Double, pax: Int, desRec: Int, waitTimes: Int)

object WorkloadCalculatorTests extends TestSuite {
  def tests = TestSuite {
    'WorkloadCalculator - {

      "Given an APIFlight, we should get back a VoyagePaxSplits" - {
        val flights = List(
          ApiFlight(
            Operator = "BA",
            Status = "",
            EstDT = "",
            ActDT = "",
            EstChoxDT = "",
            ActChoxDT = "",
            Gate = "",
            Stand = "",
            MaxPax = 1,
            ActPax = 50,
            TranPax = 0,
            RunwayID = "",
            BaggageReclaimId = "",
            FlightID = 1,
            AirportID = "LHR",
            Terminal = "",
            ICAO = "BAA0001",
            IATA = "BA0001",
            Origin = "",
            SchDT = "2020-01-01T00:00:00"
          )
        )

        val voyagesPaxSplits = flights.map(flight => PaxLoadCalculator.flightWithSplits(flight))

        assert(voyagesPaxSplits == List(
          VoyagePaxSplits("LHR", "BA0001", 50, DateTime.parse(flights(0).SchDT),
            List(PaxTypeAndQueueCount(PaxTypes.EEAMACHINEREADABLE, Desks.eeaDesk, 1))
          )
        ))
      }

      "Given a list of flights, then we should get back a list of QueueWorkloads" - {
        val flights = List(
          ApiFlight(
            Operator = "BA",
            Status = "",
            EstDT = "",
            ActDT = "",
            EstChoxDT = "",
            ActChoxDT = "",
            Gate = "",
            Stand = "",
            MaxPax = 1,
            ActPax = 50,
            TranPax = 0,
            RunwayID = "",
            BaggageReclaimId = "",
            FlightID = 1,
            AirportID = "",
            Terminal = "",
            ICAO = "",
            IATA = "",
            Origin = "",
            SchDT = "2020-01-01T00:00:00"
          )
        )
        // assuming 1 split (EEA), off rate of 20, processing time of 1 minute.
        val queueWorkloads = PaxLoadCalculator.getQueueWorkLoadsFromFlights(flights)
        assert(queueWorkloads == List(
          QueueWorkloads(
            "EEA",
            List(
              WL(1577836800, 20),
              WL(1577836860, 20),
              WL(1577836920, 10)
            ),
            List(
              Pax(1577836800, 20),
              Pax(1577836860, 20),
              Pax(1577836920, 10)
            )
          )
        ))
      }
    }
  }
}
