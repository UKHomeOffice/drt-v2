package passengersplits

import org.specs2.mutable.Specification
import passengersplits.WholePassengerQueueSplits._
import uk.gov.homeoffice.drt.ports.PaxTypes._
import uk.gov.homeoffice.drt.ports.Queues._
import uk.gov.homeoffice.drt.ports.{ApiPaxTypeAndQueueCount, PaxType, PaxTypeAndQueue, PaxTypes}
import uk.gov.homeoffice.drt.time.SDate

import scala.collection.immutable.{HashMap, Map}

class WholePassengerQueueSplitsSpec extends Specification {
  "Given a split with the highest utilisation but zero in current group" >> {
    "When I ask for the highestAlreadyUtilisedNonZero I should get a non-zero current split" >> {
      val splitsTotal: Map[PaxTypeAndQueue, Int] = Map(
        PaxTypeAndQueue(PaxTypes.EeaMachineReadable, EeaDesk) -> 10,
        PaxTypeAndQueue(PaxTypes.EeaMachineReadable, EGate) -> 5,
      )
      val splitsSoFar: Map[PaxTypeAndQueue, Int] = Map(
        PaxTypeAndQueue(PaxTypes.EeaMachineReadable, EeaDesk) -> 9,
        PaxTypeAndQueue(PaxTypes.EeaMachineReadable, EGate) -> 0,
      )
      val splitsGroup: Map[PaxTypeAndQueue, Int] = Map(
        PaxTypeAndQueue(PaxTypes.EeaMachineReadable, EeaDesk) -> 0,
        PaxTypeAndQueue(PaxTypes.EeaMachineReadable, EGate) -> 1,
      )
      val result = highestAlreadyUtilisedNonZero(splitsTotal, splitsSoFar, splitsGroup)

      result === PaxTypeAndQueue(PaxTypes.EeaMachineReadable, EGate)

      success
    }
  }

  "Given total splits, splits distributed so far, and a split totalling under the group size" >> {
    "I should get back a split totalling the desired group size where the lowest utilised splits have been incremented" >> {
      val splitsTotal: Map[PaxTypeAndQueue, Int] = Map(
        PaxTypeAndQueue(PaxTypes.EeaMachineReadable, EeaDesk) -> 51,
        PaxTypeAndQueue(PaxTypes.EeaMachineReadable, EGate) -> 11,
      )
      val splitsSoFar: Map[PaxTypeAndQueue, Int] = Map(
        PaxTypeAndQueue(PaxTypes.EeaMachineReadable, EeaDesk) -> 16,
        PaxTypeAndQueue(PaxTypes.EeaMachineReadable, EGate) -> 4,
      )
      val splitsGroup: Map[PaxTypeAndQueue, Int] = Map(
        PaxTypeAndQueue(PaxTypes.EeaMachineReadable, EeaDesk) -> 15,
        PaxTypeAndQueue(PaxTypes.EeaMachineReadable, EGate) -> 3,
      )
      val result = increaseToDesiredSize(splitsGroup, 20, splitsTotal, splitsSoFar)

      val expected = Map(
        PaxTypeAndQueue(PaxTypes.EeaMachineReadable, EeaDesk) -> 17,
        PaxTypeAndQueue(PaxTypes.EeaMachineReadable, EGate) -> 3,
      )
      result === expected

      success
    }
  }

  "Given total splits, splits distributed so far, and a split totalling over the group size" >> {
    "I should get back a split totalling the desired group size where the highest utilised splits have been decremented" >> {
      val splitsTotal: Map[PaxTypeAndQueue, Int] = Map(
        PaxTypeAndQueue(PaxTypes.EeaMachineReadable, EeaDesk) -> 51,
        PaxTypeAndQueue(PaxTypes.EeaMachineReadable, EGate) -> 11,
      )
      val splitsSoFar: Map[PaxTypeAndQueue, Int] = Map(
        PaxTypeAndQueue(PaxTypes.EeaMachineReadable, EeaDesk) -> 16,
        PaxTypeAndQueue(PaxTypes.EeaMachineReadable, EGate) -> 4,
      )
      val splitsGroup: Map[PaxTypeAndQueue, Int] = Map(
        PaxTypeAndQueue(PaxTypes.EeaMachineReadable, EeaDesk) -> 17,
        PaxTypeAndQueue(PaxTypes.EeaMachineReadable, EGate) -> 5,
      )
      val result = decreaseToDesiredSize(splitsGroup, 20, splitsTotal, splitsSoFar)

      val expected = Map(
        PaxTypeAndQueue(PaxTypes.EeaMachineReadable, EeaDesk) -> 17,
        PaxTypeAndQueue(PaxTypes.EeaMachineReadable, EGate) -> 3,
      )
      result === expected

      success
    }
  }

  "Given 22 pax, 20 each minute and splits of 1, 3 & 18 (4.55%, 13.64% & 81.82%)" >> {
    val splits: Map[PaxTypeAndQueue, Int] = Map(
      PaxTypeAndQueue(PaxTypes.NonVisaNational, NonEeaDesk) -> 1,
      PaxTypeAndQueue(PaxTypes.EeaMachineReadable, EeaDesk) -> 3,
      PaxTypeAndQueue(PaxTypes.EeaMachineReadable, EGate) -> 18,
    )
    val distributed = distributePaxOverSplitsAndMinutes(22, 20, splits, 0L)

    distributed === Map(
      0L -> Map(
        PaxTypeAndQueue(PaxTypes.NonVisaNational, NonEeaDesk) -> 1,
        PaxTypeAndQueue(PaxTypes.EeaMachineReadable, EeaDesk) -> 3,
        PaxTypeAndQueue(PaxTypes.EeaMachineReadable, EGate) -> 16
      ),
      60000L -> Map(
        PaxTypeAndQueue(PaxTypes.NonVisaNational, NonEeaDesk) -> 0,
        PaxTypeAndQueue(PaxTypes.EeaMachineReadable, EeaDesk) -> 0,
        PaxTypeAndQueue(PaxTypes.EeaMachineReadable, EGate) -> 2
      ),
    )
  }

  "Given some pax, 20 each minute and splits of 1, 3" >> {
    val splits: Map[PaxTypeAndQueue, Int] = Map(
      PaxTypeAndQueue(PaxTypes.EeaMachineReadable, EeaDesk) -> 9,
      PaxTypeAndQueue(PaxTypes.EeaMachineReadable, EGate) -> 11,
      PaxTypeAndQueue(PaxTypes.EeaNonMachineReadable, EeaDesk) -> 7,
      PaxTypeAndQueue(PaxTypes.NonVisaNational, NonEeaDesk) -> 13,
      PaxTypeAndQueue(PaxTypes.NonVisaNational, EGate) -> 17,
      PaxTypeAndQueue(PaxTypes.VisaNational, NonEeaDesk) -> 2,
      PaxTypeAndQueue(PaxTypes.VisaNational, EGate) -> 5,
      PaxTypeAndQueue(PaxTypes.EeaBelowEGateAge, EeaDesk) -> 8,
    )
    val totalPax = splits.values.sum
    val distributed = distributePaxOverSplitsAndMinutes(totalPax, 20, splits, 0L)
    val perMinute = distributed.values.map(_.values.sum)

    perMinute === Iterable(20, 20, 20, 12)
  }

  "Given some whole pax splits and some other stuff, I should get pax loads totalling the sum of the whole pax splits" >> {
    val wholePaxSplits = List(
      ApiPaxTypeAndQueueCount(B5JPlusNational, EeaDesk, 0.0, Some(Map()), Some(Map())),
      ApiPaxTypeAndQueueCount(B5JPlusNational, EGate, 1.0, Some(Map()), Some(Map())),
      ApiPaxTypeAndQueueCount(VisaNational, NonEeaDesk, 1.0, Some(Map()), Some(Map())),
      ApiPaxTypeAndQueueCount(EeaBelowEGateAge, EeaDesk, 1.0, Some(Map()), Some(Map())),
      ApiPaxTypeAndQueueCount(NonVisaNational, NonEeaDesk, 2.0, Some(Map()), Some(Map())),
      ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 6.0, Some(HashMap()), Some(HashMap())),
      ApiPaxTypeAndQueueCount(EeaMachineReadable, EGate, 17.0, Some(HashMap()), Some(HashMap())),
      ApiPaxTypeAndQueueCount(GBRNationalBelowEgateAge, EeaDesk, 18.0, Some(Map()), Some(HashMap())),
      ApiPaxTypeAndQueueCount(GBRNational, EeaDesk, 44.0, Some(Map()), Some(HashMap())),
      ApiPaxTypeAndQueueCount(GBRNational, EGate, 84.0, Some(Map()), Some(HashMap())))
    val pcpPax = 174
    val pcpStart = SDate("2023-08-17T22:50:00Z")
    val dayStart = pcpStart.getLocalLastMidnight.millisSinceEpoch
    val dayEnd = pcpStart.getLocalNextMidnight.millisSinceEpoch
    val range = dayStart to dayEnd by 60000L
    val queueLoads = wholePaxLoadsPerQueuePerMinute(range, pcpPax, wholePaxSplits, processingTime, (_, _) => Open, (_, _) => List(), pcpStart)

    queueLoads.values.map(_.map(_._2.size).sum).sum === pcpPax
  }

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

    wholePassengerSplits(totalPax, splits).toSet must_== expected
  }

  "Given some splits containing transfer passengers and a total number of passengers" >> {
    "I should get a set of pax type and queue counts with whole numbers of passengers" >> {
      val splits = Set(
        ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 1, None, None),
        ApiPaxTypeAndQueueCount(EeaMachineReadable, EGate, 3, None, None),
        ApiPaxTypeAndQueueCount(EeaMachineReadable, Transfer, 3, None, None),
      )
      val totalPax = 10

      val expected = Set(
        ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 1, None, None),
        ApiPaxTypeAndQueueCount(EeaMachineReadable, EGate, 9, None, None))

      wholePassengerSplits(totalPax, splits).toSet must_== expected
    }
  }

  "Given some splits containing transfer passengers and a total number of passengers" >> {
    "I should get a set of pax type and queue counts with whole numbers of passengers" >> {
      val splits = Set(
        ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 1, None, None),
        ApiPaxTypeAndQueueCount(EeaMachineReadable, EGate, 3, None, None),
        ApiPaxTypeAndQueueCount(EeaMachineReadable, Transfer, 3, None, None),
      )
      val totalPax = 10

      val expected = Set(
        ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 1, None, None),
        ApiPaxTypeAndQueueCount(EeaMachineReadable, EGate, 9, None, None))

      wholePassengerSplits(totalPax, splits).toSet must_== expected
    }
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

    val result = wholePaxLoadsPerQueuePerMinute(
      minuteMilli to minuteMilli,
      totalPax,
      wholeSplits,
      processingTime,
      (_, _) => Open,
      (_, _) => List(),
      firstMinute,
    )
    result should ===(expected)
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

    val result = wholePaxLoadsPerQueuePerMinute(
      minuteMilli to minuteMilli,
      totalPax,
      wholeSplits,
      processingTime,
      (_, _) => Open,
      (_, _) => List(),
      firstMinute,
    )
    result should ===(expected)
  }

  "Given some splits and a processing window outside of the pcp arrival time I should get no split minutes" >> {
    val splits = Set(ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 33.3d, None, None))
    val totalPax = 10

    val wholeSplits = wholePassengerSplits(totalPax, splits)
    val firstMinute = SDate("2022-08-01T00:00")

    val expected = Map()

    val result = wholePaxLoadsPerQueuePerMinute(
      0L to 1L,
      totalPax,
      wholeSplits,
      processingTime,
      (_, _) => Open,
      (_, _) => List(),
      firstMinute,
    )
    result should ===(expected)
  }

  "Given some passengers arriving inside and outside of the processing window" >> {
    "I should only see the queue status function being called for minutes inside the processing window" >> {
      val splits = Set(ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 33.3d, None, None))
      val totalPax = 100

      val wholeSplits = wholePassengerSplits(totalPax, splits)

      var queueStatusMinutesLookedAt = Set.empty[Long]

      wholePaxLoadsPerQueuePerMinute(
        0L to 60000L by 60000L,
        totalPax,
        wholeSplits,
        processingTime,
        (_, millis) => {
          queueStatusMinutesLookedAt = queueStatusMinutesLookedAt + millis
          Open
        },
        (_, _) => List(),
        SDate(0L),
      )

      queueStatusMinutesLookedAt should ===(Set(0L, 60000L))
    }
  }

  private def processingTime(paxType: PaxType, queue: Queue): Double = Map[(PaxType, Queue), Double](
    (EeaMachineReadable, EeaDesk) -> 25d,
    (EeaMachineReadable, EGate) -> 20d,
    (EeaNonMachineReadable, EeaDesk) -> 30d,
    (VisaNational, NonEeaDesk) -> 30d,
    (NonVisaNational, NonEeaDesk) -> 30d,
    (EeaBelowEGateAge, EeaDesk) -> 30d,
    (B5JPlusNational, EeaDesk) -> 30d,
    (GBRNationalBelowEgateAge, EeaDesk) -> 30d,
    (B5JPlusNational, EGate) -> 30d,
    (GBRNational, EeaDesk) -> 30d,
    (GBRNational, EGate) -> 30d,
  )((paxType, queue))
}
