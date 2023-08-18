package passengersplits

import org.specs2.mutable.Specification
import passengersplits.WholePassengerQueueSplits.{paxLoadsPerMinute, wholePassengerSplits, wholePaxLoadsPerQueuePerMinute}
import uk.gov.homeoffice.drt.time.SDate
import uk.gov.homeoffice.drt.ports.PaxTypes.{B5JPlusNational, EeaBelowEGateAge, EeaMachineReadable, EeaNonMachineReadable, GBRNational, GBRNationalBelowEgateAge, NonVisaNational, Transit, VisaNational}
import uk.gov.homeoffice.drt.ports.Queues.{EGate, EeaDesk, NonEeaDesk, Open, Queue, Transfer}
import uk.gov.homeoffice.drt.ports.{ApiPaxTypeAndQueueCount, PaxType}

import scala.collection.immutable.HashMap

class WholePassengerQueueSplitsSpec extends Specification {
  "Given some whole pax splits and some other stuff, I should get pax loads totalling the sum of the whole pax splits" >> {
    val wholePaxSplits = List(
      ApiPaxTypeAndQueueCount(B5JPlusNational,EeaDesk,0.0,Some(Map()),Some(Map())),
      ApiPaxTypeAndQueueCount(B5JPlusNational,EGate,1.0,Some(Map()),Some(Map())),
      ApiPaxTypeAndQueueCount(VisaNational,NonEeaDesk,1.0,Some(Map()),Some(Map())),
      ApiPaxTypeAndQueueCount(EeaBelowEGateAge,EeaDesk,1.0,Some(Map()),Some(Map())),
      ApiPaxTypeAndQueueCount(NonVisaNational,NonEeaDesk,2.0,Some(Map()),Some(Map())),
      ApiPaxTypeAndQueueCount(EeaMachineReadable,EeaDesk,6.0,Some(HashMap()),Some(HashMap())),
      ApiPaxTypeAndQueueCount(EeaMachineReadable,EGate,17.0,Some(HashMap()),Some(HashMap())),
      ApiPaxTypeAndQueueCount(GBRNationalBelowEgateAge,EeaDesk,18.0,Some(Map()),Some(HashMap())),
      ApiPaxTypeAndQueueCount(GBRNational,EeaDesk,44.0,Some(Map()),Some(HashMap())),
      ApiPaxTypeAndQueueCount(GBRNational,EGate,84.0,Some(Map()),Some(HashMap())))
    val pcpPax = 174
    val date = SDate("2023-08-17T22:53:00Z")
    val dayStart = date.getLocalLastMidnight.millisSinceEpoch
    val dayEnd = date.getLocalNextMidnight.millisSinceEpoch
    val range = dayStart to dayEnd by 60000L
    val queueLoads = wholePaxLoadsPerQueuePerMinute(range, pcpPax, wholePaxSplits, processingTime, (_, _) => Open, (_, _) => List(), date.addDays(-1))
    queueLoads.values.map(_.map(_._2.size).sum).sum === pcpPax
  }
  //  "Given some splits and a total number of passengers I should get a set of pax type and queue counts with whole numbers of passengers" >> {
  //    val splits = Set(
  //      ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 33.3, None, None),
  //      ApiPaxTypeAndQueueCount(EeaMachineReadable, EGate, 33.3, None, None),
  //      ApiPaxTypeAndQueueCount(EeaNonMachineReadable, EeaDesk, 33.3, None, None),
  //    )
  //    val totalPax = 10
  //
  //    val expected = Set(
  //      ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 3, None, None),
  //      ApiPaxTypeAndQueueCount(EeaMachineReadable, EGate, 3, None, None),
  //      ApiPaxTypeAndQueueCount(EeaNonMachineReadable, EeaDesk, 4, None, None),
  //    )
  //
  //    wholePassengerSplits(totalPax, splits) must_== expected
  //  }
  //
  //  "Given some splits containing transfer passengers and a total number of passengers I should get a set of pax type and queue counts with whole numbers of passengers" >> {
  //    val splits = Set(
  //      ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 1, None, None),
  //      ApiPaxTypeAndQueueCount(EeaMachineReadable, EGate, 3, None, None),
  //      ApiPaxTypeAndQueueCount(EeaMachineReadable, Transfer, 3, None, None),
  //    )
  //    val totalPax = 10
  //
  //    val expected = Set(
  //      ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 1, None, None),
  //      ApiPaxTypeAndQueueCount(EeaMachineReadable, EGate, 9, None, None))
  //
  //    wholePassengerSplits(totalPax, splits) must_== expected
  //  }
  //
  //  "Given some splits containing transfer passengers and a total number of passengers I should get a set of pax type and queue counts with whole numbers of passengers" >> {
  //    val splits = Set(
  //      ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 1, None, None),
  //      ApiPaxTypeAndQueueCount(EeaMachineReadable, EGate, 3, None, None),
  //      ApiPaxTypeAndQueueCount(EeaMachineReadable, Transfer, 3, None, None),
  //    )
  //    val totalPax = 10
  //
  //    val expected = Set(
  //      ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 1, None, None),
  //      ApiPaxTypeAndQueueCount(EeaMachineReadable, EGate, 9, None, None))
  //
  //    wholePassengerSplits(totalPax, splits) must_== expected
  //  }
  //
  //  "Given some numbers I should be able to produce some whole passenger workloads" >> {
  //    val totalPassengers = 100
  //    val eeaToDesk = 5
  //    val paxOffRate = 20
  //    val processingTime = 25d
  //
  //    val finalPaxByMinute = paxLoadsPerMinute(totalPassengers, eeaToDesk, paxOffRate, processingTime)
  //
  //    finalPaxByMinute === Map(
  //      1 -> List(25d),
  //      2 -> List(25d),
  //      3 -> List(25d),
  //      4 -> List(25d),
  //      5 -> List(25d))
  //  }
  //
  //  "Given some splits and a total number of passengers I should get the breakdown of whole passenger loads by minute" >> {
  //    val splits = Set(
  //      ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 1, None, None),
  //      ApiPaxTypeAndQueueCount(EeaMachineReadable, EGate, 3, None, None),
  //      ApiPaxTypeAndQueueCount(EeaNonMachineReadable, EeaDesk, 3, None, None),
  //    )
  //    val totalPax = 10
  //
  //    val wholeSplits = wholePassengerSplits(totalPax, splits)
  //    val firstMinute = SDate("2022-08-01T00:00")
  //    val minuteMilli = firstMinute.millisSinceEpoch
  //
  //    val expected = Map(
  //      EeaDesk -> Map(firstMinute.millisSinceEpoch -> List(25.0, 30.0, 30.0, 30.0, 30.0, 30.0)),
  //      EGate -> Map(firstMinute.millisSinceEpoch -> List(20.0, 20.0, 20.0, 20.0)))
  //
  //    wholePaxPerQueuePerMinute(minuteMilli to minuteMilli, totalPax, wholeSplits, processingTime, (_, _) => Open, (_, _) => List(), firstMinute) should ===(expected)
  //  }
  //
  //  "Given some percentage splits and a total number of passengers I should get the breakdown of whole passenger loads by minute with no rounding errors" >> {
  //    val splits = Set(
  //      ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 33.3d, None, None),
  //      ApiPaxTypeAndQueueCount(EeaMachineReadable, EGate, 33.3d, None, None),
  //      ApiPaxTypeAndQueueCount(EeaNonMachineReadable, EeaDesk, 33.3d, None, None),
  //    )
  //    val totalPax = 10
  //
  //    val wholeSplits = wholePassengerSplits(totalPax, splits)
  //    val firstMinute = SDate("2022-08-01T00:00")
  //    val minuteMilli = firstMinute.millisSinceEpoch
  //
  //    val expected = Map(
  //      EeaDesk -> Map(firstMinute.millisSinceEpoch -> List(25.0, 25.0, 25.0, 30.0, 30.0, 30.0, 30.0)),
  //      EGate -> Map(firstMinute.millisSinceEpoch -> List(20.0, 20.0, 20.0)))
  //
  //    wholePaxPerQueuePerMinute(minuteMilli to minuteMilli, totalPax, wholeSplits, processingTime, (_, _) => Open, (_, _) => List(), firstMinute) should ===(expected)
  //  }
  //
  //  "Given some splits and a processing window outside of the pcp arrival time I should get no split minutes" >> {
  //    val splits = Set(ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 33.3d, None, None))
  //    val totalPax = 10
  //
  //    val wholeSplits = wholePassengerSplits(totalPax, splits)
  //    val firstMinute = SDate("2022-08-01T00:00")
  //
  //    val expected = Map()
  //
  //    wholePaxPerQueuePerMinute(0L to 1L, totalPax, wholeSplits, processingTime, (_, _) => Open, (_, _) => List(), firstMinute) should ===(expected)
  //  }

  private def processingTime(paxType: PaxType, queue: Queue): Double = (paxType, queue) match {
    case (EeaMachineReadable, EeaDesk) => 25d
    case (EeaMachineReadable, EGate) => 20d
    case (EeaNonMachineReadable, EeaDesk) => 30d
    case (VisaNational, NonEeaDesk) => 30d
    case (NonVisaNational, NonEeaDesk) => 30d
    case (EeaBelowEGateAge, EeaDesk) => 30d
    case (B5JPlusNational, EeaDesk) => 30d
    case (GBRNationalBelowEgateAge, EeaDesk) => 30d
    case (B5JPlusNational, EGate) => 30d
    case (GBRNational, EeaDesk) => 30d
    case (GBRNational, EGate) => 30d
  }
}
