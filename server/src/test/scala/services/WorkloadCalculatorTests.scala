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
    VoyagePaxSplits("STN", "RY", "1234", 1, DateTime.now, List(
      PaxTypeAndQueueCount(PaxTypes.EEAMACHINEREADABLE, Desks.eeaDesk, 1)
    ))
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
      calcPaxLoad(currMinute.plusMinutes(1) , remaining - flowRate, paxType, flowRate) :::
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

  case class VoyagePaxSplits(destinationPort: String, carrierCode: String,
                             voyageNumber: String,
                             totalPaxCount: Int,
                             scheduledArrivalDateTime: DateTime,
                             paxSplits: PaxTypeAndQueueCounts)
  case class VoyagesPaxSplits(voyageSplits: List[VoyagePaxSplits])

  case class PaxTypeAndQueueCount(passengerType: String, queueType: Symbol, paxCount: Int)
}

object WorkloadCalculatorTests extends TestSuite{
  def tests = TestSuite {
    'WorkloadCalculator - {

      val flights = List(
        ApiFlight(Operator = "BA", Status = "", EstDT = "", ActDT = "", EstChoxDT = "", ActChoxDT = "", Gate = "", Stand = "", MaxPax = 1, ActPax = 100, TranPax = 0, RunwayID = "", BaggageReclaimId = "", FlightID = 1, AirportID = "", Terminal = "", ICAO = "", IATA = "", Origin = "", SchDT = "2020-01-01 00:00:00")
      )
      val voysPaxSplits = VoyagesPaxSplits(List(
        VoyagePaxSplits("STN", "RY", "1234", 1, DateTime.now, List(
          PaxTypeAndQueueCount(PaxTypes.EEAMACHINEREADABLE, Desks.eeaDesk, 1)
        )),
        VoyagePaxSplits("STN", "RY", "1234", 1, DateTime.now, List(
          PaxTypeAndQueueCount(PaxTypes.EEAMACHINEREADABLE, Desks.eeaDesk, 1)
        ))
      ))
      val loads = voysPaxSplits.voyageSplits
        .map(voyPaxSplits => PaxLoadCalculator.calculateVoyagePaxLoadByDesk(voyPaxSplits, 20))
      assert(loads == Nil)
    }
  }
}
