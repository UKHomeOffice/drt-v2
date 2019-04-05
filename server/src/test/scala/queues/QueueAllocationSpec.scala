package queues

import drt.shared.PaxTypes._
import drt.shared.{PaxTypes, _}
import manifests.passengers.{BestAvailableManifest, ManifestPassengerProfile}
import org.specs2.mutable.Specification
import passengersplits.core.PassengerTypeCalculatorValues.DocType
import queueus._
import services.SDate

class QueueAllocationSpec extends Specification {

  val terminalQueueAllocationMap: Map[String, Map[PaxType, List[(String, Double)]]] = Map("T1" -> Map(
    EeaMachineReadable -> List(Queues.EGate -> 1.0),
    EeaNonMachineReadable -> List(Queues.EeaDesk -> 1.0),
    Transit -> List(Queues.Transfer -> 1.0),
    NonVisaNational -> List(Queues.NonEeaDesk -> 1.0),
    VisaNational -> List(Queues.NonEeaDesk -> 1.0),
    B5JPlusNationalBelowEGateAge -> List(Queues.EeaDesk -> 1),
    EeaBelowEGateAge -> List(Queues.EeaDesk -> 1.0),
    B5JPlusNational -> List(
      Queues.EGate -> 0.75,
      Queues.EeaDesk -> 0.25
    )
  ))
  val testQueueAllocator = TerminalQueueAllocator(terminalQueueAllocationMap)

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
    )), "DC", None, PaxNumbers)

    val result = PaxTypeQueueAllocation(DefaultPaxTypeAllocator, testQueueAllocator).toSplits("T1", bestManifest)

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
      PaxNumbers
    )

    val result = PaxTypeQueueAllocation(DefaultPaxTypeAllocator, testQueueAllocator).toSplits("T1", bestManifest)

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
        ApiPaxTypeAndQueueCount(PaxTypes.B5JPlusNational, Queues.EGate, 0.75, Some(Map("USA" -> 0.75))),
        ApiPaxTypeAndQueueCount(PaxTypes.B5JPlusNational, Queues.EeaDesk, 0.25, Some(Map("USA" -> 0.25)))
      ),
      "DC",
      None,
      PaxNumbers
    )

    val result = PaxTypeQueueAllocation(B5JPlusTypeAllocator(b5JStartDate), testQueueAllocator).toSplits("T1", bestManifest)

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
      PaxNumbers
    )

    val result = PaxTypeQueueAllocation(B5JPlusTypeAllocator(b5JStartDate), testQueueAllocator).toSplits("T1", bestManifest)

    result === expected
  }

  "Given a BestAvailableManifest with an under 12 B5J National we should get 1 passenger to EEADesk" >> {

    val bestManifest = BestAvailableManifest(
      "DC",
      "LHR",
      "USA",
      "234",
      "SA",
      SDate("2019-06-22T06:24:00Z"),
      List(
        ManifestPassengerProfile("USA", Some(DocType.Passport), Some(11), Some(false))
      )
    )

    val b5JStartDate = SDate("2019-06-01T00:00:00Z")

    val expected = Splits(
      Set(
        ApiPaxTypeAndQueueCount(PaxTypes.B5JPlusNationalBelowEGateAge, Queues.EeaDesk, 1, Some(Map("USA" -> 1)))
      ),
      "DC",
      None,
      PaxNumbers
    )

    val result = PaxTypeQueueAllocation(
      B5JPlusTypeAllocator(b5JStartDate),
      testQueueAllocator
    ).toSplits("T1", bestManifest)

    result === expected
  }

  "Given a BestAvailableManifest with an under 12 EU National we should get 1 passenger to EEADesk" >> {

    val bestManifest = BestAvailableManifest(
      "DC",
      "LHR",
      "USA",
      "234",
      "SA",
      SDate("2019-06-22T06:24:00Z"),
      List(
        ManifestPassengerProfile("GBR", Some(DocType.Passport), Some(11), Some(false))
      )
    )

    val b5JStartDate = SDate("2019-06-01T00:00:00Z")

    val expected = Splits(
      Set(
        ApiPaxTypeAndQueueCount(PaxTypes.EeaBelowEGateAge, Queues.EeaDesk, 1, Some(Map("GBR" -> 1)))
      ),
      "DC",
      None,
      PaxNumbers
    )

    val result = PaxTypeQueueAllocation(
      B5JPlusTypeAllocator(b5JStartDate),
      testQueueAllocator
    ).toSplits("T1", bestManifest)

    result === expected
  }

  "Given a BestAvailableManifest with 1 GBR PCP passenger and 1 GBR Transit Passenger before B5J is turned on " +
    "Then we should get back a split with the Transit" >> {

    val bestManifest = BestAvailableManifest(
      "DC",
      "LHR",
      "USA",
      "234",
      "SA",
      SDate("2019-01-22T06:24:00Z"),
      List(
        ManifestPassengerProfile("GBR", Some(DocType.Passport), Some(11), Some(false)),
        ManifestPassengerProfile("GBR", Some(DocType.Passport), Some(11), Some(true))
      )
    )

    val b5JStartDate = SDate("2019-06-01T00:00:00Z")

    val expected = Splits(
      Set(
        ApiPaxTypeAndQueueCount(PaxTypes.EeaBelowEGateAge, Queues.EeaDesk, 1, Some(Map("GBR" -> 1))),
        ApiPaxTypeAndQueueCount(PaxTypes.Transit, Queues.Transfer, 1, Some(Map("GBR" -> 1)))
      ),
      "DC",
      None,
      PaxNumbers
    )

    val result = PaxTypeQueueAllocation(
      B5JPlusWithTransitTypeAllocator(b5JStartDate),
      testQueueAllocator
    ).toSplits("T1", bestManifest)

    result === expected
  }

  val fastTrackQueueAllocator = TerminalQueueAllocatorWithFastTrack(terminalQueueAllocationMap)

  "Given a BestAvailableManifest with 10 NonEEA Passengers on a Flight with FastTrack at LHR " +
    "Then I should get 0.8 Pax to NonEEA Queue and 0.2 to FastTrack" >> {

    val bestManifest = BestAvailableManifest(
      "DC",
      "LHR",
      "USA",
      "234",
      "SA",
      SDate("2019-01-22T06:24:00Z"),
      List(
        ManifestPassengerProfile("ZWE", Some(DocType.Passport), Some(22), Some(false))
      )
    )

    val b5JStartDate = SDate("2019-06-01T00:00:00Z")

    val expected = Splits(
      Set(
        ApiPaxTypeAndQueueCount(PaxTypes.VisaNational, Queues.FastTrack, 0.2, Some(Map("ZWE" -> 0.2))),
        ApiPaxTypeAndQueueCount(PaxTypes.VisaNational, Queues.NonEeaDesk, 0.8, Some(Map("ZWE" -> 0.8)))
      ),
      "DC",
      None,
      PaxNumbers
    )

    val result = PaxTypeQueueAllocation(
      B5JPlusWithTransitTypeAllocator(b5JStartDate),
      fastTrackQueueAllocator
    ).toSplits("T1", bestManifest)

    result === expected
  }

  "Given a BestAvailableManifest with 1 NonEEA Passenger on a Flight with no FastTrack at LHR " +
    "Then I should get 1 Pax to NonEEA Queue and 0 to FastTrack" >> {

    val bestManifest = BestAvailableManifest(
      "DC",
      "LHR",
      "USA",
      "234",
      "TM",
      SDate("2019-01-22T06:24:00Z"),
      List(
        ManifestPassengerProfile("ZWE", Some(DocType.Passport), Some(22), Some(false))
      )
    )

    val b5JStartDate = SDate("2019-06-01T00:00:00Z")

    val expected = Splits(
      Set(
        ApiPaxTypeAndQueueCount(PaxTypes.VisaNational, Queues.NonEeaDesk, 1, Some(Map("ZWE" -> 1)))
      ),
      "DC",
      None,
      PaxNumbers
    )

    val result = PaxTypeQueueAllocation(
      B5JPlusWithTransitTypeAllocator(b5JStartDate),
      fastTrackQueueAllocator
    ).toSplits("T1", bestManifest)

    result === expected
  }

  "Given a BestAvailableManifest with 1 NonEEA Passengers on a Flight with FastTrack and an ICAO code at LHR " +
    "Then I should get 0.8 Pax to NonEEA Queue and 0.2 to FastTrack" >> {

    val bestManifest = BestAvailableManifest(
      "DC",
      "LHR",
      "USA",
      "234",
      "SAA",
      SDate("2019-01-22T06:24:00Z"),
      List(
        ManifestPassengerProfile("ZWE", Some(DocType.Passport), Some(22), Some(false))
      )
    )

    val b5JStartDate = SDate("2019-06-01T00:00:00Z")

    val expected = Splits(
      Set(
        ApiPaxTypeAndQueueCount(PaxTypes.VisaNational, Queues.FastTrack, 0.2, Some(Map("ZWE" -> 0.2))),
        ApiPaxTypeAndQueueCount(PaxTypes.VisaNational, Queues.NonEeaDesk, 0.8, Some(Map("ZWE" -> 0.8)))
      ),
      "DC",
      None,
      PaxNumbers
    )

    val result = PaxTypeQueueAllocation(
      B5JPlusWithTransitTypeAllocator(b5JStartDate),
      fastTrackQueueAllocator
    ).toSplits("T1", bestManifest)

    result === expected
  }
}
