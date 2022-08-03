package passengersplits

import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.ports.ApiPaxTypeAndQueueCount
import uk.gov.homeoffice.drt.ports.PaxTypes.{EeaMachineReadable, EeaNonMachineReadable}
import uk.gov.homeoffice.drt.ports.Queues.{EGate, EeaDesk, Transfer}

class FlightPassengerLoadsSpec extends Specification {
  "Given some splits and a total number of passengers I should get a set of pax type and queue counts with whole numbers of passengers" >> {
    val splits = Set(
      ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 1, None, None),
      ApiPaxTypeAndQueueCount(EeaMachineReadable, EGate, 3, None, None),
      ApiPaxTypeAndQueueCount(EeaNonMachineReadable, EeaDesk, 3, None, None),
    )
    val totalPax = 10

    val expected = Set(
      ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 1, None, None),
      ApiPaxTypeAndQueueCount(EeaMachineReadable, EGate, 4, None, None),
      ApiPaxTypeAndQueueCount(EeaNonMachineReadable, EeaDesk, 5, None, None),
    )

    wholePassengerSplits(totalPax, splits) must_== expected
  }

  "Given some splits containing transfer passengers and a total number of passengers I should get a set of pax type and queue counts with whole numbers of passengers" >> {
    val splits = Set(
      ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 1, None, None),
      ApiPaxTypeAndQueueCount(EeaMachineReadable, EGate, 3, None, None),
      ApiPaxTypeAndQueueCount(EeaMachineReadable, Transfer, 3, None, None),
    )
    val totalPax = 10

    val expected = Set(
      ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 1, None, None),
      ApiPaxTypeAndQueueCount(EeaMachineReadable, EGate, 9, None, None))

    wholePassengerSplits(totalPax, splits) must_== expected
  }

  private def wholePassengerSplits(totalPax: Int, splits: Set[ApiPaxTypeAndQueueCount]): Set[ApiPaxTypeAndQueueCount] = {
    val splitsMinusTransfer = splits.filterNot(_.queueType == Transfer)
    val totalSplitsPax = splits.toList.map(_.paxCount).sum
    val totalSplits = splitsMinusTransfer.size
    splitsMinusTransfer.foldLeft(Set[ApiPaxTypeAndQueueCount]()) {
      case (actualSplits, nextSplit) =>
        val countSoFar = actualSplits.map(_.paxCount).sum
        val proposedCount = Math.round((nextSplit.paxCount / totalSplitsPax) * totalPax).toInt

        val finalSplit = actualSplits.size == totalSplits - 1
        val actualCount = if (proposedCount + countSoFar <= totalPax && !finalSplit)
          proposedCount
        else totalPax - countSoFar

        actualSplits + nextSplit.copy(paxCount = actualCount)
    }
  }

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
        paxThisMinute :: paxByMinute
    }

    (if (paxByMinute.sum < eeaToDesk) (eeaToDesk - paxByMinute.sum) :: paxByMinute else paxByMinute).reverse
  }
}
