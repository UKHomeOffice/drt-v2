package passengersplits

import core.{PassengerQueueCalculator, PassengerTypeCalculator, PassengerTypeCalculatorValues}
import drt.shared.PassengerSplits.SplitsPaxTypeAndQueueCount
import drt.shared.PaxTypes._
import drt.shared.{PaxType, PaxTypeAndQueue, Queues}
import org.specs2.matcher.Matchers
import org.specs2.mutable.Specification
import org.specs2.specification.Tables
import passengersplits.core.PassengerTypeCalculator.PaxTypeInfo
import passengersplits.parsing.VoyageManifestParser
import passengersplits.parsing.VoyageManifestParser.{PassengerInfoJson, VoyageManifest}
import services.workloadcalculator.PaxLoadCalculator.PaxTypeAndQueueCount
//import core.PassengerQueueTypes.PaxTypeAndQueueCount
import core.PassengerTypeCalculatorValues.CountryCodes
import org.specs2.mutable.Specification
import org.specs2.matcher.Matchers
import org.specs2.specification.Tables



//import PassengerInfoParser.PassengerInfoJson
class InvestigateLatviaVisaNationalMisClassification extends Specification with Matchers with Tables {
  "Read the RYR2634 flight" >> {
    val parsed = File("/home/lance/dev/data/apisplits/huntfor2643/")
  }
  import Queues._
  import PassengerQueueCalculator._

}

//import PassengerInfoParser.PassengerInfoJson
class FlightPassengerQueueCalculatorSpec extends Specification with Matchers with Tables {
  "Information about a passenger and their document type tells us what passenger type they are" >> {
    s2"""$passengerType"""
  }

  import Queues._
  import PassengerQueueCalculator._

  val UK = "UK"
  "Information about a passenger type is used to inform what queue we think they'll go to." >> {
    "Given a list of passenger types, count by passenger type" in {
      val passengerTypes = EeaMachineReadable ::
        NonVisaNational ::
        NonVisaNational ::
        VisaNational ::
        EeaNonMachineReadable ::
        Nil
      val passengerTypeCounts = countPassengerTypes(passengerTypes)
      val expectedpassengerTypeCounts = Map(
        EeaMachineReadable -> 1,
        EeaNonMachineReadable -> 1,
        NonVisaNational -> 2,
        VisaNational -> 1
      )
      expectedpassengerTypeCounts should beEqualTo(passengerTypeCounts)
    }

    "Given counts of passenger types, " +
      "And a 'machineRead to desk percentage' of 60% " +
      "Then we can generate counts of passenger types in queues" in {
      val passengerTypeCounts: Map[PaxType, Int] = Map(
        EeaMachineReadable -> 20,
        EeaNonMachineReadable -> 10,
        NonVisaNational -> 10,
        VisaNational -> 5
      )
      val calculatedDeskCounts = calculateQueuePaxCounts(passengerTypeCounts, 0)
      calculatedDeskCounts.toSet should beEqualTo(List(
        SplitsPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 20),
        SplitsPaxTypeAndQueueCount(EeaNonMachineReadable, Queues.EeaDesk, 10),
        SplitsPaxTypeAndQueueCount(NonVisaNational, Queues.NonEeaDesk, 10),
        SplitsPaxTypeAndQueueCount(VisaNational, Queues.NonEeaDesk, 5)
      ).toSet)
    }
    "Given different counts of passenger types, " +
      "And a 'machineRead to desk percentage' of 80% " +
      "Then we can generate counts of passenger types in queues" in {
      val passengerTypeCounts: Map[PaxType, Int] = Map(
        EeaMachineReadable -> 100,
        EeaNonMachineReadable -> 15,
        NonVisaNational -> 50,
        VisaNational -> 10
      )
      val expectedDeskPaxCounts = Set(
        SplitsPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 100),
        SplitsPaxTypeAndQueueCount(EeaNonMachineReadable, EeaDesk, 15),
        SplitsPaxTypeAndQueueCount(NonVisaNational, NonEeaDesk, 50),
        SplitsPaxTypeAndQueueCount(VisaNational, NonEeaDesk, 10)
      )
      val calculatedDeskCounts = calculateQueuePaxCounts(passengerTypeCounts, 0d)
      calculatedDeskCounts.toSet should beEqualTo(expectedDeskPaxCounts)
    }
    "Given just some nationals on visa and non visa" +
      "Then we can generate counts of types of passengers in queues" in {
      val passengerTypeCounts: Map[PaxType, Int] = Map(
        NonVisaNational -> 50,
        VisaNational -> 10
      )
      val expectedDeskPaxCounts = Set(
        SplitsPaxTypeAndQueueCount(VisaNational, NonEeaDesk, 10),
        SplitsPaxTypeAndQueueCount(NonVisaNational, NonEeaDesk, 50)
      )
      val calculatedDeskCounts = calculateQueuePaxCounts(passengerTypeCounts, 0d)
      calculatedDeskCounts.toSet === expectedDeskPaxCounts
    }

    "Given some passenger info parsed from the AdvancePassengerInfo" in {
      "Given a German national " in {
        "When we calculate passenger types THEN they are assigned to the EEA desk" in {
          val passengerInfos = PassengerInfoJson(Passport, "DEU", "EEA", None, None, "N", None, Some("DEU")) :: Nil
          val voyageManifest = VoyageManifest("DC", "LGW", "BCN", "2643", "FR", "", "", passengerInfos)
          PassengerQueueCalculator.convertVoyageManifestIntoPaxTypeAndQueueCounts(voyageManifest) should beEqualTo(List(
            SplitsPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 1)))
        }
      }
      "Given a GBR (UK) national" in {
        "When we calculate passenger types then they are assigned to the EeaDesk" in {
          val passengerInfos = PassengerInfoJson(Passport, "GBR", "EEA", None, None, "N", None, Some("GBR")) :: Nil
          val voyageManifest = VoyageManifest("DC", "LGW", "BCN", "2643", "FR", "", "", passengerInfos)
          PassengerQueueCalculator.convertVoyageManifestIntoPaxTypeAndQueueCounts(voyageManifest) should beEqualTo(List(
            SplitsPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 1)))
        }
      }
    }
  }


  def passengerType = {
    import CountryCodes._
    import PassengerTypeCalculatorValues.EEA
    val passport = "P"
    val Visa = "V"
    s2"""${
      "NationalityCountryEEAFlag" | "DocumentIssuingCountryCode" | "DocumentType" | "PassengerType" |>
        "EEA" ! Germany ! passport ! EeaMachineReadable |
        "" ! "NZL" ! passport ! NonVisaNational |
        "" ! "NZL" ! Visa ! VisaNational |
        "" ! "AUS" ! Visa ! VisaNational |
        EEA ! Greece ! passport ! EeaNonMachineReadable |
        EEA ! Italy ! passport ! EeaNonMachineReadable |
        EEA ! Portugal ! passport ! EeaNonMachineReadable |
        EEA ! Latvia ! passport ! EeaMachineReadable |
        EEA ! Latvia ! passport ! EeaMachineReadable |
        EEA ! Slovakia ! passport ! EeaNonMachineReadable | {
        (countryFlag, documentCountry, documentType, passengerType) =>
          PassengerTypeCalculator.mostAirports(PaxTypeInfo("N", countryFlag, documentCountry, Option(documentType))) must_== passengerType
      }
    }"""
  }

  val Passport = Some("P")

  //  val EeaNonMachineReadable = "eea-non-machine-readable"
  //  val VisaNational = "national-visa"
  //  val EeaMachineReadable = "eea-machine-readable"
  //  val NonVisaNational = "national-non-visa"


}
