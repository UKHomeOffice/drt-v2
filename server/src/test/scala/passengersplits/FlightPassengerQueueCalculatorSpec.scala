package passengersplits

import drt.shared.PaxTypes._
import drt.shared.{ApiPaxTypeAndQueueCount, PaxType, Queues}
import org.specs2.matcher.Matchers
import org.specs2.mutable.Specification
import org.specs2.specification.Tables
import passengersplits.core.PassengerTypeCalculator.PaxTypeInfo
import passengersplits.core.PassengerTypeCalculatorValues.CountryCodes
import passengersplits.core.{PassengerQueueCalculator, PassengerTypeCalculator}
import passengersplits.parsing.VoyageManifestParser.{PassengerInfoJson, VoyageManifest}

import scala.collection.immutable


class FlightPassengerQueueCalculatorSpec extends Specification with Matchers with Tables {
  "Information about a passenger and their document type tells us what passenger type they are" >> {
    s2"""$passengerType"""
  }

  import PassengerQueueCalculator._
  import Queues._

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
        ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 20),
        ApiPaxTypeAndQueueCount(EeaNonMachineReadable, Queues.EeaDesk, 10),
        ApiPaxTypeAndQueueCount(NonVisaNational, Queues.NonEeaDesk, 10),
        ApiPaxTypeAndQueueCount(VisaNational, Queues.NonEeaDesk, 5)
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
        ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 100),
        ApiPaxTypeAndQueueCount(EeaNonMachineReadable, EeaDesk, 15),
        ApiPaxTypeAndQueueCount(NonVisaNational, NonEeaDesk, 50),
        ApiPaxTypeAndQueueCount(VisaNational, NonEeaDesk, 10)
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
        ApiPaxTypeAndQueueCount(VisaNational, NonEeaDesk, 10),
        ApiPaxTypeAndQueueCount(NonVisaNational, NonEeaDesk, 50)
      )
      val calculatedDeskCounts = calculateQueuePaxCounts(passengerTypeCounts, 0d)
      calculatedDeskCounts.toSet === expectedDeskPaxCounts
    }

    "Given some passenger info parsed from the AdvancePassengerInfo" in {
      "Given a German national " in {
        "When we calculate passenger types THEN they are assigned to the EEA desk" in {
          val passengerInfos = PassengerInfoJson(Passport, "DEU", "EEA", None, None, "N", None, Some("DEU"), None) :: Nil
          val voyageManifest = VoyageManifest("DC", "LGW", "BCN", "2643", "FR", "", "", passengerInfos)
          PassengerQueueCalculator.convertVoyageManifestIntoPaxTypeAndQueueCounts("STN", voyageManifest) should beEqualTo(List(
            ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 1)))
        }
      }
      "Given a GBR (UK) national" in {
        "When we calculate passenger types then they are assigned to the EeaDesk" in {
          val passengerInfos = PassengerInfoJson(Passport, "GBR", "EEA", None, None, "N", None, Some("GBR"), None) :: Nil
          val voyageManifest = VoyageManifest("DC", "LGW", "BCN", "2643", "FR", "", "", passengerInfos)
          PassengerQueueCalculator.convertVoyageManifestIntoPaxTypeAndQueueCounts("STN", voyageManifest) should beEqualTo(List(
            ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 1)))
        }
      }
    }
  }


  "Visa Countries" in {
    "can load from csv " in {
      val countriesList: immutable.Seq[Either[String, Product with Serializable with Object]] = PassengerTypeCalculator.loadCountries()
      val errors = countriesList.collect { case Left(e) => e }

      "with no errors" in {
        errors should beEmpty
      }
      "classifying visaCountries" in {
        val visaRequiredCountryCode = PassengerTypeCalculator.visaCountries.head.code3Letter
        "given a non eu passenger if their country is visa nat then they're a visa-national to the nonEeaDesk" in {
          val passengerInfos = PassengerInfoJson(Passport, visaRequiredCountryCode, "EEA", None, None, "N", None, Some(visaRequiredCountryCode), None) :: Nil
          val voyageManifest = VoyageManifest("DC", "LGW", "BCN", "2643", "FR", "", "", passengerInfos)
          PassengerQueueCalculator.convertVoyageManifestIntoPaxTypeAndQueueCounts("STN", voyageManifest) should beEqualTo(List(
            ApiPaxTypeAndQueueCount(VisaNational, NonEeaDesk, 1)))

        }
        "Number of visaCountries is 112" in {
          PassengerTypeCalculator.visaCountyCodes.size == 112
        }
        "All country codes are 3 characters" in {
          PassengerTypeCalculator.countries.forall(_.code3Letter.length == 3)
        }
      }
    }

  }


  def passengerType = {
    import CountryCodes._
    val lebanon = "LBN"
    val israel = "ISR"
    val haiti = "HTI"
    s2"""${
      "DocumentType" | "DocumentIssuingCountryCode" | "PassengerType" |>
        "P" ! Germany ! EeaMachineReadable |
        "P" ! "NZL" ! NonVisaNational |
        "P" ! "AUS" ! NonVisaNational |
        "P" ! lebanon ! VisaNational |
        "P" ! israel ! NonVisaNational |
        "P" ! haiti ! VisaNational |
        "I" ! Greece ! EeaNonMachineReadable |
        "I" ! Italy ! EeaNonMachineReadable |
        "I" ! Portugal ! EeaNonMachineReadable |
        "P" ! Latvia ! EeaMachineReadable |
        "P" ! Latvia ! EeaMachineReadable |
        "I" ! Slovakia ! EeaNonMachineReadable | {
        (docType, documentCountry, passengerType) =>
          PassengerTypeCalculator.mostAirports(PaxTypeInfo(None, "N", documentCountry, Option(docType))) must_== passengerType
      }
    }"""
  }

  val Passport = Some("P")


}
