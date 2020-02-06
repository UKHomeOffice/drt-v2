package manifests.queues

import controllers.ArrivalGenerator.arrival
import drt.shared.PaxTypes._
import drt.shared.Queues.Queue
import drt.shared.SplitRatiosNs.SplitSources.{Historical, TerminalAverage}
import drt.shared.Terminals.{T2, Terminal}
import drt.shared._
import drt.shared.airportconfig.Bhx
import manifests.passengers.{BestAvailableManifest, ManifestPassengerProfile}
import passengersplits.core.PassengerTypeCalculatorValues.{CountryCodes, DocumentType}
import passengersplits.parsing.VoyageManifestParser.PaxAge
import queueus._
import services.SDate
import services.crunch.CrunchTestLike

class SplitsCalculatorSpec extends CrunchTestLike {
  val config: AirportConfig = Bhx.config

  def apiManifest(passengerProfiles: List[ManifestPassengerProfile]): BestAvailableManifest = {
    val bestManifest = BestAvailableManifest(
      Historical,
      PortCode("LHR"),
      PortCode("USA"),
      VoyageNumber("234"),
      CarrierCode("SA"),
      SDate("2019-06-22T06:24:00Z"),
      passengerProfiles
    )
    bestManifest
  }

  val testArrival = arrival(iata = "SA0234", schDt = "2019-06-22T06:24:00Z", actPax = Option(100), terminal = T2, origin = PortCode("USA"))

  "When adjusting adult EGate use based on under age pax in API Data using an eGate split of 50%" >> {
    val terminalQueueAllocationMap: Map[Terminal, Map[PaxType, List[(Queue, Double)]]] = Map(T2 -> Map(
      EeaMachineReadable -> List(Queues.EGate -> 0.5, Queues.EeaDesk -> 0.5),
      B5JPlusNational -> List(Queues.EGate -> 0.5, Queues.EeaDesk -> 0.5),
      EeaBelowEGateAge -> List(Queues.EeaDesk -> 1.0),
      B5JPlusNationalBelowEGateAge -> List(Queues.EeaDesk -> 1.0)
    ))
    val testPaxTypeAllocator = PaxTypeQueueAllocation(
      B5JPlusTypeAllocator(),
      TerminalQueueAllocatorWithFastTrack(terminalQueueAllocationMap))

    val splitsCalculator = SplitsCalculator(testPaxTypeAllocator, config.terminalPaxSplits, ChildEGateAdjustments(1.0))

    "Given 4 EEA adults and 1 EEA child with a 1.0 adjustment per child" +
      "Then I should expect 3 EEA Adults to Desk, 1 EEA child to desk and 1 EEA Adult to eGates" >> {

      val manifest = apiManifest(List(
        ManifestPassengerProfile(Nationality(CountryCodes.UK), Option(DocumentType.Passport), Option(PaxAge(35)), Option(false)),
        ManifestPassengerProfile(Nationality(CountryCodes.UK), Option(DocumentType.Passport), Option(PaxAge(35)), Option(false)),
        ManifestPassengerProfile(Nationality(CountryCodes.UK), Option(DocumentType.Passport), Option(PaxAge(35)), Option(false)),
        ManifestPassengerProfile(Nationality(CountryCodes.UK), Option(DocumentType.Passport), Option(PaxAge(35)), Option(false)),
        ManifestPassengerProfile(Nationality(CountryCodes.UK), Option(DocumentType.Passport), Option(PaxAge(4)), Option(false))
      ))
      val expected = Splits(
        Set(
          ApiPaxTypeAndQueueCount(PaxTypes.EeaBelowEGateAge, Queues.EeaDesk, 1, Option(Map(Nationality(CountryCodes.UK) -> 1))),
          ApiPaxTypeAndQueueCount(PaxTypes.EeaBelowEGateAge, Queues.EeaDesk, 1, Option(Map(Nationality(CountryCodes.UK) -> 1))),
          ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 3, Option(Map(Nationality(CountryCodes.UK) -> 2))),
          ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, 1, Option(Map(Nationality(CountryCodes.UK) -> 2)))
        ),
        Historical,
        None,
        PaxNumbers
      )
      val result = splitsCalculator.bestSplitsForArrival(manifest, testArrival)

      result === expected
    }

    "Given 0 EEA adults, 4 B5J Nationals and 1 B5J child with a 1.0 adjustment per child" +
      "Then I should expect 3 EEA Adults to Desk, 1 B5J child to desk and 1 B5J Adult to eGates" >> {

      val manifest = apiManifest(List(
        ManifestPassengerProfile(Nationality(CountryCodes.USA), Option(DocumentType.Passport), Option(PaxAge(35)), Option(false)),
        ManifestPassengerProfile(Nationality(CountryCodes.USA), Option(DocumentType.Passport), Option(PaxAge(35)), Option(false)),
        ManifestPassengerProfile(Nationality(CountryCodes.USA), Option(DocumentType.Passport), Option(PaxAge(35)), Option(false)),
        ManifestPassengerProfile(Nationality(CountryCodes.USA), Option(DocumentType.Passport), Option(PaxAge(35)), Option(false)),
        ManifestPassengerProfile(Nationality(CountryCodes.USA), Option(DocumentType.Passport), Option(PaxAge(4)), Option(false))
      ))
      val expected = Splits(
        Set(
          ApiPaxTypeAndQueueCount(PaxTypes.B5JPlusNationalBelowEGateAge, Queues.EeaDesk, 1, Option(Map(Nationality(CountryCodes.USA) -> 1))),
          ApiPaxTypeAndQueueCount(PaxTypes.B5JPlusNational, Queues.EeaDesk, 3, Option(Map(Nationality(CountryCodes.USA) -> 2))),
          ApiPaxTypeAndQueueCount(PaxTypes.B5JPlusNational, Queues.EGate, 1, Option(Map(Nationality(CountryCodes.USA) -> 2)))
        ),
        Historical,
        None,
        PaxNumbers
      )
      val result = splitsCalculator.bestSplitsForArrival(manifest, testArrival)

      result === expected
    }

    "Given 2 EEA adults, 3 EEA children with a 1.0 adjustment per child" +
      "Then I should expect 2 EEA Adults to Desk, 3 EEA child to desk and 0 EEA Adults to eGates" >> {

      val manifest = apiManifest(List(
        ManifestPassengerProfile(Nationality(CountryCodes.UK), Option(DocumentType.Passport), Option(PaxAge(35)), Option(false)),
        ManifestPassengerProfile(Nationality(CountryCodes.UK), Option(DocumentType.Passport), Option(PaxAge(35)), Option(false)),
        ManifestPassengerProfile(Nationality(CountryCodes.UK), Option(DocumentType.Passport), Option(PaxAge(4)), Option(false)),
        ManifestPassengerProfile(Nationality(CountryCodes.UK), Option(DocumentType.Passport), Option(PaxAge(4)), Option(false)),
        ManifestPassengerProfile(Nationality(CountryCodes.UK), Option(DocumentType.Passport), Option(PaxAge(4)), Option(false))
      ))
      val expected = Splits(
        Set(
          ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, 0, Option(Map(Nationality(CountryCodes.UK) -> 1))),
          ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 2, Option(Map(Nationality(CountryCodes.UK) -> 1))),
          ApiPaxTypeAndQueueCount(PaxTypes.EeaBelowEGateAge, Queues.EeaDesk, 3, Option(Map(Nationality(CountryCodes.UK) -> 3)))
        ),
        Historical,
        None,
        PaxNumbers
      )
      val result = splitsCalculator.bestSplitsForArrival(manifest, testArrival)

      result === expected
    }
    "Given 2 B5J adults, 3 B5J children with a 1.0 adjustment per child" +
      "Then I should expect 2 B5J Adults to Desk, 3 B5J child to desk and 0 B5J Adults to eGates" >> {

      val manifest = apiManifest(List(
        ManifestPassengerProfile(Nationality(CountryCodes.USA), Option(DocumentType.Passport), Option(PaxAge(35)), Option(false)),
        ManifestPassengerProfile(Nationality(CountryCodes.USA), Option(DocumentType.Passport), Option(PaxAge(35)), Option(false)),
        ManifestPassengerProfile(Nationality(CountryCodes.USA), Option(DocumentType.Passport), Option(PaxAge(4)), Option(false)),
        ManifestPassengerProfile(Nationality(CountryCodes.USA), Option(DocumentType.Passport), Option(PaxAge(4)), Option(false)),
        ManifestPassengerProfile(Nationality(CountryCodes.USA), Option(DocumentType.Passport), Option(PaxAge(4)), Option(false))
      ))
      val expected = Splits(
        Set(
          ApiPaxTypeAndQueueCount(PaxTypes.B5JPlusNational, Queues.EGate, 0, Option(Map(Nationality(CountryCodes.USA) -> 1))),
          ApiPaxTypeAndQueueCount(PaxTypes.B5JPlusNational, Queues.EeaDesk, 2, Option(Map(Nationality(CountryCodes.USA) -> 1))),
          ApiPaxTypeAndQueueCount(PaxTypes.B5JPlusNationalBelowEGateAge, Queues.EeaDesk, 3, Option(Map(Nationality(CountryCodes.USA) -> 3)))
        ),
        Historical,
        None,
        PaxNumbers
      )
      val result = splitsCalculator.bestSplitsForArrival(manifest, testArrival)

      result === expected
    }
  }
  "When adjusting adult EGate use based on under age pax in API Data using an eGate split of 100%" >> {
    val terminalQueueAllocationMap: Map[Terminal, Map[PaxType, List[(Queue, Double)]]] = Map(T2 -> Map(
      EeaMachineReadable -> List(Queues.EGate -> 1.0),
      EeaBelowEGateAge -> List(Queues.EeaDesk -> 1.0)
    ))
    val testPaxTypeAllocator = PaxTypeQueueAllocation(
      B5JPlusTypeAllocator(),
      TerminalQueueAllocatorWithFastTrack(terminalQueueAllocationMap))


    "Given 4 EEA adults and 1 EEA child with a 1.0 adjustment per child" +
    "Then I should expect 1 EEA Adults to Desk, 1 EEA child to desk and 3 EEA Adult to eGates" >> {
      val splitsCalculator = SplitsCalculator(testPaxTypeAllocator, config.terminalPaxSplits, ChildEGateAdjustments(1.0))
      val manifest = apiManifest(List(
        ManifestPassengerProfile(Nationality(CountryCodes.UK), Option(DocumentType.Passport), Option(PaxAge(35)), Option(false)),
        ManifestPassengerProfile(Nationality(CountryCodes.UK), Option(DocumentType.Passport), Option(PaxAge(35)), Option(false)),
        ManifestPassengerProfile(Nationality(CountryCodes.UK), Option(DocumentType.Passport), Option(PaxAge(35)), Option(false)),
        ManifestPassengerProfile(Nationality(CountryCodes.UK), Option(DocumentType.Passport), Option(PaxAge(35)), Option(false)),
        ManifestPassengerProfile(Nationality(CountryCodes.UK), Option(DocumentType.Passport), Option(PaxAge(4)), Option(false))
      ))

      val expected = Splits(
        Set(
          ApiPaxTypeAndQueueCount(PaxTypes.EeaBelowEGateAge, Queues.EeaDesk, 1, Option(Map(Nationality(CountryCodes.UK) -> 1))),
          ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 1, None),
          ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, 3, Option(Map(Nationality(CountryCodes.UK) -> 4)))
        ),
        Historical,
        None,
        PaxNumbers
      )
      val result = splitsCalculator.bestSplitsForArrival(manifest, testArrival)

      result === expected
    }

    "Given 4 EEA adults and 1 EEA child with a 0.0 adjustment per child" +
    "Then I should expect 0 EEA Adults to Desk, 1 EEA child to desk and 4 EEA Adult to eGates" >> {

      val splitsCalculator = SplitsCalculator(testPaxTypeAllocator, config.terminalPaxSplits, ChildEGateAdjustments(0.0))

      val manifest = apiManifest(List(
        ManifestPassengerProfile(Nationality(CountryCodes.UK), Option(DocumentType.Passport), Option(PaxAge(35)), Option(false)),
        ManifestPassengerProfile(Nationality(CountryCodes.UK), Option(DocumentType.Passport), Option(PaxAge(35)), Option(false)),
        ManifestPassengerProfile(Nationality(CountryCodes.UK), Option(DocumentType.Passport), Option(PaxAge(35)), Option(false)),
        ManifestPassengerProfile(Nationality(CountryCodes.UK), Option(DocumentType.Passport), Option(PaxAge(35)), Option(false)),
        ManifestPassengerProfile(Nationality(CountryCodes.UK), Option(DocumentType.Passport), Option(PaxAge(4)), Option(false))
      ))
      val expected = Splits(
        Set(
          ApiPaxTypeAndQueueCount(PaxTypes.EeaBelowEGateAge, Queues.EeaDesk, 1, Option(Map(Nationality(CountryCodes.UK) -> 1))),
          ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, 4, Option(Map(Nationality(CountryCodes.UK) -> 4)))
        ),
        Historical,
        None,
        PaxNumbers
      )
      val result = splitsCalculator.bestSplitsForArrival(manifest, testArrival)

      result === expected
    }
  }

  "Given a splits calculator with BHX's terminal pax splits " +
    "When I ask for the default splits for T2 " +
    "I should see no EGate split" >> {

    val paxTypeQueueAllocation = PaxTypeQueueAllocation(
      B5JPlusWithTransitTypeAllocator(),
      TerminalQueueAllocatorWithFastTrack(config.terminalPaxTypeQueueAllocation))
    val splitsCalculator = SplitsCalculator(paxTypeQueueAllocation, config.terminalPaxSplits)
    val result = splitsCalculator.terminalDefaultSplits(T2)

    val expected = Set(Splits(Set(
      ApiPaxTypeAndQueueCount(NonVisaNational, Queues.NonEeaDesk, 4.0, None),
      ApiPaxTypeAndQueueCount(EeaNonMachineReadable, Queues.EeaDesk, 0.0, None),
      ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 92.0, None),
      ApiPaxTypeAndQueueCount(VisaNational, Queues.NonEeaDesk, 4.0, None)),
      TerminalAverage, None, Percentage))

    result === expected
  }
}
