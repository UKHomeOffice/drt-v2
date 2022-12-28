package passengersplits

import org.specs2.mutable.Specification
import passengersplits.WholePassengerQueueSplits.{paxLoadsPerMinute, wholePassengerSplits, wholePaxPerQueuePerMinute}
import uk.gov.homeoffice.drt.time.SDate
import uk.gov.homeoffice.drt.ports.PaxTypes.{EeaMachineReadable, EeaNonMachineReadable}
import uk.gov.homeoffice.drt.ports.Queues.{EGate, EeaDesk, Open, Queue, Transfer}
import uk.gov.homeoffice.drt.ports.{ApiPaxTypeAndQueueCount, PaxType}

class WholePassengerQueueSplitsSpec extends Specification {
  "Given some splits and a total number of passengers I should get a set of pax type and queue counts with whole numbers of passengers" >> {
    val splits = Set(
      ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 33.3, None, None),
      ApiPaxTypeAndQueueCount(EeaMachineReadable, EGate, 33.3, None, None),
      ApiPaxTypeAndQueueCount(EeaNonMachineReadable, EeaDesk, 33.3, None, None),
    )
    val totalPax = 10

    val expected = Set(
      ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 3, None, None),
      ApiPaxTypeAndQueueCount(EeaMachineReadable, EGate, 3, None, None),
      ApiPaxTypeAndQueueCount(EeaNonMachineReadable, EeaDesk, 4, None, None),
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

  "Given some numbers I should be able to produce some whole passenger workloads" >> {
    val totalPassengers = 100
    val eeaToDesk = 5
    val paxOffRate = 20
    val processingTime = 25d

    val finalPaxByMinute = paxLoadsPerMinute(totalPassengers, eeaToDesk, paxOffRate, processingTime)

    finalPaxByMinute === Map(
      1 -> List(25d),
      2 -> List(25d),
      3 -> List(25d),
      4 -> List(25d),
      5 -> List(25d))
  }

  "Given some splits and a total number of passengers I should get the breakdown of whole passenger loads by minute" >> {
    val splits = Set(
      ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 1, None, None),
      ApiPaxTypeAndQueueCount(EeaMachineReadable, EGate, 3, None, None),
      ApiPaxTypeAndQueueCount(EeaNonMachineReadable, EeaDesk, 3, None, None),
    )
    val totalPax = 10

    val wholeSplits = wholePassengerSplits(totalPax, splits)
    val firstMinute = SDate("2022-08-01T00:00")
    val minuteMilli = firstMinute.millisSinceEpoch

    val expected = Map(
      EeaDesk -> Map(firstMinute.millisSinceEpoch -> List(25.0, 30.0, 30.0, 30.0, 30.0, 30.0)),
      EGate -> Map(firstMinute.millisSinceEpoch -> List(20.0, 20.0, 20.0, 20.0)))

    wholePaxPerQueuePerMinute(minuteMilli to minuteMilli, totalPax, wholeSplits, processingTime, (_, _) => Open, (_, _) => List(), firstMinute) should ===(expected)
  }

  "Given some percentage splits and a total number of passengers I should get the breakdown of whole passenger loads by minute with no rounding errors" >> {
    val splits = Set(
      ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 33.3d, None, None),
      ApiPaxTypeAndQueueCount(EeaMachineReadable, EGate, 33.3d, None, None),
      ApiPaxTypeAndQueueCount(EeaNonMachineReadable, EeaDesk, 33.3d, None, None),
    )
    val totalPax = 10

    val wholeSplits = wholePassengerSplits(totalPax, splits)
    val firstMinute = SDate("2022-08-01T00:00")
    val minuteMilli = firstMinute.millisSinceEpoch

    val expected = Map(
      EeaDesk -> Map(firstMinute.millisSinceEpoch -> List(25.0, 25.0, 25.0, 30.0, 30.0, 30.0, 30.0)),
      EGate -> Map(firstMinute.millisSinceEpoch -> List(20.0, 20.0, 20.0)))

    wholePaxPerQueuePerMinute(minuteMilli to minuteMilli, totalPax, wholeSplits, processingTime, (_, _) => Open, (_, _) => List(), firstMinute) should ===(expected)
  }

  "Given some splits and a processing window outside of the pcp arrival time I should get no split minutes" >> {
    val splits = Set(ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 33.3d, None, None))
    val totalPax = 10

    val wholeSplits = wholePassengerSplits(totalPax, splits)
    val firstMinute = SDate("2022-08-01T00:00")

    val expected = Map()

    wholePaxPerQueuePerMinute(0L to 1L, totalPax, wholeSplits, processingTime, (_, _) => Open, (_, _) => List(), firstMinute) should ===(expected)
  }

  private def processingTime(paxType: PaxType, queue: Queue): Double = (paxType, queue) match {
    case (EeaMachineReadable, EeaDesk) => 25d
    case (EeaMachineReadable, EGate) => 20d
    case (EeaNonMachineReadable, EeaDesk) => 30d
  }
}
