package passengersplits

import drt.shared.PaxTypes._
import drt.shared.{ApiPaxTypeAndQueueCount, PaxType, Queues}
import org.specs2.matcher.Matchers
import org.specs2.mutable.Specification
import org.specs2.specification.Tables
import org.specs2.specification.core.Fragments
import passengersplits.core.PassengerTypeCalculator.PaxTypeInfo
import passengersplits.core.PassengerTypeCalculatorValues.CountryCodes
import passengersplits.core.{SplitsCalculator, PassengerTypeCalculator}
import passengersplits.parsing.VoyageManifestParser.{PassengerInfoJson, VoyageManifest}


class FlightSplitsCalculatorSpec extends Specification with Matchers with Tables {
  "Information about a passenger and their document type tells us what passenger type they are" >> {
    s2"""$passengerType"""
  }

  import SplitsCalculator._
  import Queues._

  val UK = "UK"
  "Information about a passenger type is used to inform what queue we think they'll go to." >> {
    "Given a list of passenger types, count by passenger type" in {

      val passengerTypes = Seq(
        Tuple2(EeaMachineReadable, None),
        Tuple2(NonVisaNational, None),
        Tuple2(NonVisaNational, None),
        Tuple2(VisaNational, None),
        Tuple2(EeaNonMachineReadable, None))

      val passengerTypeCounts = countPassengerTypes(passengerTypes)
      val expectedpassengerTypeCounts = Map(
        EeaMachineReadable -> Tuple2(1, None),
        EeaNonMachineReadable -> Tuple2(1, None),
        NonVisaNational -> Tuple2(2, None),
        VisaNational -> Tuple2(1, None)
      )
      passengerTypeCounts should beEqualTo(expectedpassengerTypeCounts)
    }

    "Given counts of passenger types, " +
      "And a 'machineRead to desk percentage' of 60% " +
      "Then we can generate counts of passenger types in queues" in {
      val paxTypeCountAndNats: Map[PaxType, (Int, Option[Map[String, Double]])] = Map(
        EeaMachineReadable -> Tuple2(20, None),
        EeaNonMachineReadable -> Tuple2(10, None),
        NonVisaNational -> Tuple2(10, None),
        VisaNational -> Tuple2(5, None)
      )
      val calculatedDeskCounts = calculateQueuePaxCounts(paxTypeCountAndNats, 0)
      calculatedDeskCounts.toSet should beEqualTo(List(
        ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 20, None),
        ApiPaxTypeAndQueueCount(EeaNonMachineReadable, Queues.EeaDesk, 10, None),
        ApiPaxTypeAndQueueCount(NonVisaNational, Queues.NonEeaDesk, 10, None),
        ApiPaxTypeAndQueueCount(VisaNational, Queues.NonEeaDesk, 5, None)
      ).toSet)
    }
    "Given different counts of passenger types, " +
      "And a 'machineRead to desk percentage' of 80% " +
      "Then we can generate counts of passenger types in queues" in {
      val paxTypeCountAndNats: Map[PaxType, (Int, Option[Map[String, Double]])] = Map(
        EeaMachineReadable -> Tuple2(100, None),
        EeaNonMachineReadable -> Tuple2(15, None),
        NonVisaNational -> Tuple2(50, None),
        VisaNational -> Tuple2(10, None)
      )
      val expectedDeskPaxCounts = Set(
        ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 100, None),
        ApiPaxTypeAndQueueCount(EeaNonMachineReadable, EeaDesk, 15, None),
        ApiPaxTypeAndQueueCount(NonVisaNational, NonEeaDesk, 50, None),
        ApiPaxTypeAndQueueCount(VisaNational, NonEeaDesk, 10, None)
      )
      val calculatedDeskCounts = calculateQueuePaxCounts(paxTypeCountAndNats, 0d)
      calculatedDeskCounts.toSet should beEqualTo(expectedDeskPaxCounts)
    }
    "Given just some nationals on visa and non visa" +
      "Then we can generate counts of types of passengers in queues" in {
      val paxTypeCountAndNats: Map[PaxType, (Int, Option[Map[String, Double]])] = Map(
        NonVisaNational -> Tuple2(50, None),
        VisaNational -> Tuple2(10, None)
      )
      val expectedDeskPaxCounts = Set(
        ApiPaxTypeAndQueueCount(VisaNational, NonEeaDesk, 10, None),
        ApiPaxTypeAndQueueCount(NonVisaNational, NonEeaDesk, 50, None)
      )
      val calculatedDeskCounts = calculateQueuePaxCounts(paxTypeCountAndNats, 0d)
      calculatedDeskCounts.toSet === expectedDeskPaxCounts
    }

    "Given some passenger info parsed from the AdvancePassengerInfo" in {
      "Given a German national " in {
        "When we calculate passenger types THEN they are assigned to the EEA desk" in {
          val passengerInfos = PassengerInfoJson(Passport, "DEU", "EEA", None, None, "N", None, Some("DEU"), None) :: Nil
          val voyageManifest = VoyageManifest("DC", "LGW", "BCN", "2643", "FR", "", "", passengerInfos)
          SplitsCalculator.convertVoyageManifestIntoPaxTypeAndQueueCounts("STN", voyageManifest) should beEqualTo(List(
            ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 1, Option(Map("DEU" -> 1.0)))))
        }
      }
      "Given a GBR (UK) national" in {
        "When we calculate passenger types then they are assigned to the EeaDesk" in {
          val passengerInfos = PassengerInfoJson(Passport, "GBR", "EEA", None, None, "N", None, Some("GBR"), None) :: Nil
          val voyageManifest = VoyageManifest("DC", "LGW", "BCN", "2643", "FR", "", "", passengerInfos)
          SplitsCalculator.convertVoyageManifestIntoPaxTypeAndQueueCounts("STN", voyageManifest) should beEqualTo(List(
            ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 1, Option(Map("GBR" -> 1.0)))))
        }
      }
    }
  }


  "Visa Countries" in {
    "can load from csv " in {
      val countriesList: Seq[Either[String, PassengerTypeCalculator.Country]] = PassengerTypeCalculator.loadCountries()
      val errors = countriesList.collect { case Left(e) => e }

      "with no errors" in {
        errors should beEmpty
      }
      "classifying visaCountries" in {
        val visaRequiredCountryCode = PassengerTypeCalculator.visaCountries.head.code3Letter
        "given a non eu passenger if their country is visa nat then they're a visa-national to the nonEeaDesk" in {
          val passengerInfos = PassengerInfoJson(Passport, visaRequiredCountryCode, "EEA", None, None, "N", None, Some(visaRequiredCountryCode), None) :: Nil
          val voyageManifest = VoyageManifest("DC", "LGW", "BCN", "2643", "FR", "", "", passengerInfos)
          SplitsCalculator.convertVoyageManifestIntoPaxTypeAndQueueCounts("STN", voyageManifest) should beEqualTo(List(
            ApiPaxTypeAndQueueCount(VisaNational, NonEeaDesk, 1, Option(Map(visaRequiredCountryCode -> 1.0)))))

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


  def passengerType: Fragments = {
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
          PassengerTypeCalculator.mostAirports(PaxTypeInfo(None, "N", documentCountry, Option(docType), None)) must_== passengerType
      }
    }"""
  }

  val Passport = Some("P")


}
