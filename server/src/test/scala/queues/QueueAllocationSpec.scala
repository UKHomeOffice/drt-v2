package queues

import manifests.passengers.{BestAvailableManifest, ManifestPassengerProfile}
import org.specs2.mutable.Specification
import passengersplits.core.PassengerTypeCalculatorValues.DocumentType
import queueus._
import services.SDate
import uk.gov.homeoffice.drt.Nationality
import uk.gov.homeoffice.drt.arrivals.SplitStyle.PaxNumbers
import uk.gov.homeoffice.drt.arrivals.{CarrierCode, Splits, VoyageNumber}
import uk.gov.homeoffice.drt.ports.PaxTypes._
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources._
import uk.gov.homeoffice.drt.ports.Terminals._
import uk.gov.homeoffice.drt.ports._

class QueueAllocationSpec extends Specification {
  val terminalQueueAllocationMap: Map[Terminal, Map[PaxType, List[(Queue, Double)]]] = Map(T1 -> Map(
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
  val testQueueAllocator: TerminalQueueAllocator = TerminalQueueAllocator(terminalQueueAllocationMap)

  "Given a BestAvailableManifest with 1 GBP passenger " +
    "then I should get a Splits of 100% EEA to EGate" >> {

    val bestManifest = BestAvailableManifest(
      Historical,
      PortCode("LHR"),
      PortCode("JHB"),
      VoyageNumber("234"),
      CarrierCode("SA"),
      SDate("2019-02-22T06:24:00Z"),
      List(
        ManifestPassengerProfile(Nationality("GBR"), Option(DocumentType.Passport), Option(PaxAge(21)), false, None)
      ),
      None
    )

    val expected = Splits(Set(ApiPaxTypeAndQueueCount(
      PaxTypes.EeaMachineReadable,
      Queues.EGate, 1,
      Option(Map(Nationality("GBR") -> 1)),
      Option(Map(PaxAge(21) -> 1))
    )), Historical, None, PaxNumbers)

    val result = PaxTypeQueueAllocation(DefaultPaxTypeAllocator, testQueueAllocator).toSplits(T1, bestManifest)

    result === expected
  }

  "Given a BestAvailableManifest with 1 GBP passenger and 1 ZAF passenger " +
    "then I should get a Splits of 50% EEA to EGate and 50% visa national to Non EEA Desk" >> {

    val bestManifest = BestAvailableManifest(
      Historical,
      PortCode("LHR"),
      PortCode("JHB"),
      VoyageNumber("234"),
      CarrierCode("SA"),
      SDate("2019-02-22T06:24:00Z"),
      List(
        ManifestPassengerProfile(Nationality("GBR"), Option(DocumentType.Passport), Option(PaxAge(21)), false, None),
        ManifestPassengerProfile(Nationality("ZAF"), Option(DocumentType.Passport), Option(PaxAge(21)), false, None)
      ),
      None
    )

    val expected = Splits(
      Set(
        ApiPaxTypeAndQueueCount(
          PaxTypes.EeaMachineReadable,
          Queues.EGate,
          1,
          Option(Map(Nationality("GBR") -> 1)),
          Option(Map(PaxAge(21) -> 1))
        ),
        ApiPaxTypeAndQueueCount(
          PaxTypes.VisaNational,
          Queues.NonEeaDesk,
          1,
          Option(Map(Nationality("ZAF") -> 1)),
          Option(Map(PaxAge(21) -> 1))
        )
      ),
      Historical,
      None,
      PaxNumbers
    )

    val result = PaxTypeQueueAllocation(DefaultPaxTypeAllocator, testQueueAllocator).toSplits(T1, bestManifest)

    result === expected
  }

  "Given a BestAvailableManifest for LHR with 1 B5J National " +
    "then I should get a Splits containing 0.75 pax of type B5JPlus to EGate and .25 B5JPlus to EEADesk" >> {

    val bestManifest = BestAvailableManifest(
      Historical,
      PortCode("LHR"),
      PortCode("USA"),
      VoyageNumber("234"),
      CarrierCode("SA"),
      SDate("2019-07-22T06:24:00Z"),
      List(ManifestPassengerProfile(Nationality("USA"), Option(DocumentType.Passport), Option(PaxAge(21)), true, None)),
      None
    )

    val expected = Splits(
      Set(
        ApiPaxTypeAndQueueCount(
          PaxTypes.B5JPlusNational,
          Queues.EGate,
          0.75,
          Option(Map(Nationality("USA") -> 0.75)),
          Option(Map(PaxAge(21) -> 0.75))
        ),
        ApiPaxTypeAndQueueCount(
          PaxTypes.B5JPlusNational,
          Queues.EeaDesk,
          0.25,
          Option(Map(Nationality("USA") -> 0.25)),
          Option(Map(PaxAge(21) -> 0.25))
        )
      ),
      Historical,
      None,
      PaxNumbers
    )

    val result = PaxTypeQueueAllocation(B5JPlusTypeAllocator, testQueueAllocator).toSplits(T1, bestManifest)

    result === expected
  }

  "Given a BestAvailableManifest with an under 12 B5J National we should get 1 passenger to EEADesk" >> {

    val bestManifest = BestAvailableManifest(
      Historical,
      PortCode("LHR"),
      PortCode("USA"),
      VoyageNumber("234"),
      CarrierCode("SA"),
      SDate("2019-06-22T06:24:00Z"),
      List(
        ManifestPassengerProfile(Nationality("USA"), Option(DocumentType.Passport), Option(PaxAge(11)), false, None)
      ),
      None
    )

    val expected = Splits(
      Set(
        ApiPaxTypeAndQueueCount(
          PaxTypes.B5JPlusNationalBelowEGateAge,
          Queues.EeaDesk,
          1,
          Option(Map(Nationality("USA") -> 1)),
          Option(Map(PaxAge(11) -> 1))
        )
      ),
      Historical,
      None,
      PaxNumbers
    )

    val result = PaxTypeQueueAllocation(
      B5JPlusTypeAllocator,
      testQueueAllocator
    ).toSplits(T1, bestManifest)

    result === expected
  }

  "Given a BestAvailableManifest with an under 12 EU National we should get 1 passenger to EEADesk" >> {

    val bestManifest = BestAvailableManifest(
      Historical,
      PortCode("LHR"),
      PortCode("USA"),
      VoyageNumber("234"),
      CarrierCode("SA"),
      SDate("2019-06-22T06:24:00Z"),
      List(
        ManifestPassengerProfile(Nationality("GBR"), Option(DocumentType.Passport), Option(PaxAge(11)), false, None)
      ),
      None
    )


    val expected = Splits(
      Set(
        ApiPaxTypeAndQueueCount(
          PaxTypes.EeaBelowEGateAge,
          Queues.EeaDesk,
          1,
          Option(Map(Nationality("GBR") -> 1)),
          Option(Map(PaxAge(11) -> 1))
        )
      ),
      Historical,
      None,
      PaxNumbers
    )

    val result = PaxTypeQueueAllocation(
      B5JPlusTypeAllocator,
      testQueueAllocator
    ).toSplits(T1, bestManifest)

    result === expected
  }

  "Given a BestAvailableManifest with 1 GBR PCP passenger and 1 GBR Transit Passenger " +
    "Then we should get back a split with the Transit" >> {

    val bestManifest = BestAvailableManifest(
      Historical,
      PortCode("LHR"),
      PortCode("USA"),
      VoyageNumber("234"),
      CarrierCode("SA"),
      SDate("2019-01-22T06:24:00Z"),
      List(
        ManifestPassengerProfile(Nationality("GBR"), Option(DocumentType.Passport), Option(PaxAge(11)), false, None),
        ManifestPassengerProfile(Nationality("GBR"), Option(DocumentType.Passport), Option(PaxAge(11)), true, None)
      ),
      None
    )

    val expected = Splits(
      Set(
        ApiPaxTypeAndQueueCount(
          PaxTypes.EeaBelowEGateAge,
          Queues.EeaDesk,
          1,
          Option(Map(Nationality("GBR") -> 1)),
          Option(Map(PaxAge(11) -> 1))
        ),
        ApiPaxTypeAndQueueCount(
          PaxTypes.Transit,
          Queues.Transfer,
          1,
          Option(Map(Nationality("GBR") -> 1)),
          Option(Map(PaxAge(11) -> 1))
        )
      ),
      Historical,
      None,
      PaxNumbers
    )

    val result = PaxTypeQueueAllocation(
      B5JPlusWithTransitTypeAllocator,
      testQueueAllocator
    ).toSplits(T1, bestManifest)

    result === expected
  }

  val fastTrackQueueAllocator: TerminalQueueAllocatorWithFastTrack = TerminalQueueAllocatorWithFastTrack(terminalQueueAllocationMap)

  "Given a BestAvailableManifest with 10 NonEEA Passengers on a Flight with FastTrack at LHR " +
    "Then I should get 0.8 Pax to NonEEA Queue and 0.1 to FastTrack" >> {

    val bestManifest = BestAvailableManifest(
      Historical,
      PortCode("LHR"),
      PortCode("USA"),
      VoyageNumber("234"),
      CarrierCode("SA"),
      SDate("2019-01-22T06:24:00Z"),
      List(
        ManifestPassengerProfile(Nationality("ZWE"), Option(DocumentType.Passport), Option(PaxAge(22)), false, None)
      ),
      None
    )

    val expected = Splits(
      Set(
        ApiPaxTypeAndQueueCount(
          PaxTypes.VisaNational,
          Queues.FastTrack,
          0.1,
          Option(Map(Nationality("ZWE") -> 0.1)),
          Option(Map(PaxAge(22) -> 0.1))
        ),
        ApiPaxTypeAndQueueCount(
          PaxTypes.VisaNational,
          Queues.NonEeaDesk,
          0.9,
          Option(Map(Nationality("ZWE") -> 0.9)),
          Option(Map(PaxAge(22) -> 0.9))
        )
      ),
      Historical,
      None,
      PaxNumbers
    )

    val result = PaxTypeQueueAllocation(
      B5JPlusWithTransitTypeAllocator,
      fastTrackQueueAllocator
    ).toSplits(T1, bestManifest)

    result === expected
  }

  "Given a BestAvailableManifest with 1 NonEEA Passenger on a Flight with no FastTrack at LHR " +
    "Then I should get 1 Pax to NonEEA Queue and 0 to FastTrack" >> {

    val bestManifest = BestAvailableManifest(
      Historical,
      PortCode("LHR"),
      PortCode("USA"),
      VoyageNumber("234"),
      CarrierCode("TM"),
      SDate("2019-01-22T06:24:00Z"),
      List(
        ManifestPassengerProfile(Nationality("ZWE"), Option(DocumentType.Passport), Option(PaxAge(22)), false, None)
      ),
      None
    )

    val expected = Splits(
      Set(
        ApiPaxTypeAndQueueCount(
          PaxTypes.VisaNational,
          Queues.NonEeaDesk,
          1,
          Option(Map(Nationality("ZWE") -> 1)),
          Option(Map(PaxAge(22) -> 1))
        )
      ),
      Historical,
      None,
      PaxNumbers
    )

    val result = PaxTypeQueueAllocation(
      B5JPlusWithTransitTypeAllocator,
      fastTrackQueueAllocator
    ).toSplits(T1, bestManifest)

    result === expected
  }

  "Given a BestAvailableManifest with 1 NonEEA Passengers on a Flight with FastTrack and an ICAO code at LHR " +
    "Then I should get 0.8 Pax to NonEEA Queue and 0.1 to FastTrack" >> {

    val bestManifest = BestAvailableManifest(
      Historical,
      PortCode("LHR"),
      PortCode("USA"),
      VoyageNumber("234"),
      CarrierCode("SAA"),
      SDate("2019-01-22T06:24:00Z"),
      List(
        ManifestPassengerProfile(Nationality("ZWE"), Option(DocumentType.Passport), Option(PaxAge(22)), false, None)
      ),
      None
    )

    val expected = Splits(
      Set(
        ApiPaxTypeAndQueueCount(
          PaxTypes.VisaNational,
          Queues.FastTrack,
          0.1,
          Option(Map(Nationality("ZWE") -> 0.1)),
          Option(Map(PaxAge(22) -> 0.1))
        ),
        ApiPaxTypeAndQueueCount(
          PaxTypes.VisaNational,
          Queues.NonEeaDesk,
          0.9,
          Option(Map(Nationality("ZWE") -> 0.9)),
          Option(Map(PaxAge(22) -> 0.9))
        )
      ),
      Historical,
      None,
      PaxNumbers
    )

    val result = PaxTypeQueueAllocation(
      B5JPlusWithTransitTypeAllocator,
      fastTrackQueueAllocator
    ).toSplits(T1, bestManifest)

    result === expected
  }
}
