package queues

import drt.shared.{PaxTypes, _}
import manifests.passengers.{BestAvailableManifest, ManifestPassengerProfile}
import org.specs2.mutable.Specification
import passengersplits.core.PassengerTypeCalculatorValues.DocType
import queueus._
import services.SDate

class QueueAllocationSpec extends Specification {

  "Given a BestAvailableManifest with 1 GBP passenger " +
    "then I should get a Splits of 100% EEA to EGate" >> {

    val bestManifest = BestAvailableManifest(
      "DC",
      "LHR",
      "JHB",
      "234",
      "SA",
      SDate("2019-02-22T06:24:00Z"),
      List(
        ManifestPassengerProfile("GBR", Some(DocType.Passport), Some(21), Some(false))
      )
    )

    val expected = Splits(Set(ApiPaxTypeAndQueueCount(
      PaxTypes.EeaMachineReadable,
      Queues.EGate, 1,
      Some(Map("GBR" -> 1))
    )), "DC", None, Ratio)

    val result = PaxTypeQueueAllocation(DefaultPaxTypeAllocator, DefaultQueueAllocator).toSplits(bestManifest)

    result === expected
  }

  "Given a BestAvailableManifest with 1 GBP passenger and 1 ZAF passenger " +
    "then I should get a Splits of 50% EEA to EGate and 50% visa national to Non EEA Desk" >> {

    val bestManifest = BestAvailableManifest(
      "DC",
      "LHR",
      "JHB",
      "234",
      "SA",
      SDate("2019-02-22T06:24:00Z"),
      List(
        ManifestPassengerProfile("GBR", Some(DocType.Passport), Some(21), Some(false)),
        ManifestPassengerProfile("ZAF", Some(DocType.Passport), Some(21), Some(false))
      )
    )

    val expected = Splits(
      Set(
        ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, 1, Some(Map("GBR" -> 1))),
        ApiPaxTypeAndQueueCount(PaxTypes.VisaNational, Queues.NonEeaDesk, 1, Some(Map("ZAF" -> 1)))
      ),
      "DC",
      None,
      Ratio
    )

    val result = PaxTypeQueueAllocation(DefaultPaxTypeAllocator, DefaultQueueAllocator).toSplits(bestManifest)

    result === expected
  }
  "Given a BestAvailableManifest for LHR with 1 Transit passenger " +
    "then I should get a Splits containing 1 pax of type Transit" >> {

    val bestManifest = BestAvailableManifest(
      "DC",
      "LHR",
      "JHB",
      "234",
      "SA",
      SDate("2019-02-22T06:24:00Z"),
      List(ManifestPassengerProfile("GBR", Some(DocType.Passport), Some(21), Some(true)))
    )

    val expected = Splits(
      Set(
        ApiPaxTypeAndQueueCount(PaxTypes.Transit, Queues.Transfer, 1, Some(Map("GBR" -> 1)))
      ),
      "DC",
      None,
      Ratio
    )

    val result = PaxTypeQueueAllocation(DefaultWithTransitPaxTypeAllocator, DefaultQueueAllocator).toSplits(bestManifest)

    result === expected
  }

  "Given a BestAvailableManifest for LHR with 1 B5J National after June 2019 and a B5JPlus start date of 2019-06-01" +
    "then I should get a Splits containing 0.75 pax of type B5JPlus to EGate and .25 B5JPlus to EEADesk" >> {

    val bestManifest = BestAvailableManifest(
      "DC",
      "LHR",
      "USA",
      "234",
      "SA",
      SDate("2019-07-22T06:24:00Z"),
      List(ManifestPassengerProfile("USA", Some(DocType.Passport), Some(21), Some(true)))
    )

    val b5JStartDate = SDate("2019-06-01T00:00:00Z")
    val expected = Splits(
      Set(
        ApiPaxTypeAndQueueCount(PaxTypes.B5JPlusNational, Queues.EeaDesk, 0.25, Some(Map("USA" -> 0.25))),
        ApiPaxTypeAndQueueCount(PaxTypes.B5JPlusNational, Queues.EGate, 0.75, Some(Map("USA" -> 0.75)))
      ),
      "DC",
      None,
      Ratio
    )

    val result = PaxTypeQueueAllocation(B5JPlusTypeAllocator(b5JStartDate), B5JPlusQueueAllocator(b5JStartDate)).toSplits(bestManifest)

    result === expected
  }

  "Given a BestAvailableManifest for LHR with 1 B5J National before April 2019 and a B5JPlus start date of 2019-06-01" +
    "then I should get a Splits containing 1 pax of type B5JPlus to NonEEADesk" >> {

    val bestManifest = BestAvailableManifest(
      "DC",
      "LHR",
      "USA",
      "234",
      "SA",
      SDate("2019-03-22T06:24:00Z"),
      List(ManifestPassengerProfile("USA", Some(DocType.Passport), Some(21), Some(true)))
    )

    val b5JStartDate = SDate("2019-06-01T00:00:00Z")

    val expected = Splits(
      Set(
        ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, Queues.NonEeaDesk, 1, Some(Map("USA" -> 1)))
      ),
      "DC",
      None,
      Ratio
    )

    val result = PaxTypeQueueAllocation(B5JPlusTypeAllocator(b5JStartDate), B5JPlusQueueAllocator(b5JStartDate)).toSplits(bestManifest)

    result === expected
  }

  "Given a BestAvailableManifest for LHR with 2 B5J Nationals, one in Transit, after June 2019 and a B5JPlus start date of 2019-06-01" +
    "then I should get a Splits containing 0.75 pax of type B5JPlus to EGate, .25 B5JPlus to EEADesk and 1 Transit to Transfer" >> {

    val bestManifest = BestAvailableManifest(
      "DC",
      "LHR",
      "USA",
      "234",
      "SA",
      SDate("2019-06-22T06:24:00Z"),
      List(
        ManifestPassengerProfile("USA", Some(DocType.Passport), Some(21), Some(false)),
        ManifestPassengerProfile("USA", Some(DocType.Passport), Some(21), Some(true))
      )
    )

    val b5JStartDate = SDate("2019-06-01T00:00:00Z")

    val expected = Splits(
      Set(
        ApiPaxTypeAndQueueCount(PaxTypes.B5JPlusNational, Queues.EeaDesk, 0.25, Some(Map("USA" -> 0.25))),
        ApiPaxTypeAndQueueCount(PaxTypes.B5JPlusNational, Queues.EGate, 0.75, Some(Map("USA" -> 0.75))),
        ApiPaxTypeAndQueueCount(PaxTypes.Transit, Queues.Transfer, 1, Some(Map("USA" -> 1)))
      ),
      "DC",
      None,
      Ratio
    )

    val result = PaxTypeQueueAllocation(
      B5JPlusWithTransitTypeAllocator(b5JStartDate),
      B5JPlusQueueAllocator(b5JStartDate)
    ).toSplits(bestManifest)

    result === expected
  }
}
