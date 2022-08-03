package passengersplits

import org.specs2.mutable.Specification

class FlightPassengerLoadsSpec extends Specification {
  "Given some numbers I should be able to produce some whole passenger workloads" >> {
    "" >> {
      val totalPassengers = 100
      val eeaToDesk = 5
      val paxOffRate = 20

      val finalPaxByMinute: scala.List[Int] = paxPerMinute(totalPassengers, eeaToDesk, paxOffRate)

      finalPaxByMinute === List(1, 1, 1, 1, 1)
    }
  }

  private def paxPerMinute(totalPassengers: Int, eeaToDesk: Int, paxOffRate: Int) = {
    val minutesOff = totalPassengers.toDouble / paxOffRate
    val paxPerMinuteDecimal = eeaToDesk / minutesOff

    val paxByMinute = (1 to minutesOff.toInt).foldLeft(List[Int]()) {
      case (paxByMinute, minute) =>
        val roundedDecimalPaxForMinute = paxPerMinuteDecimal * minute
        val paxThisMinute = Math.round(roundedDecimalPaxForMinute).toInt - paxByMinute.sum
        println(s"$minute: $paxThisMinute")
        paxThisMinute :: paxByMinute
    }

    (if (paxByMinute.sum < eeaToDesk) (eeaToDesk - paxByMinute.sum) :: paxByMinute else paxByMinute).reverse
  }
}
