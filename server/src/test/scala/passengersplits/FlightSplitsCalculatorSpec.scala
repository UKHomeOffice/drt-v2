package passengersplits

import drt.shared.Nationality
import drt.shared.PaxTypes._
import org.specs2.matcher.Matchers
import org.specs2.mutable.Specification
import org.specs2.specification.Tables
import org.specs2.specification.core.Fragments
import passengersplits.core.PassengerTypeCalculator
import passengersplits.core.PassengerTypeCalculator.PaxTypeInfo
import passengersplits.core.PassengerTypeCalculatorValues.{CountryCodes, DocumentType}


class FlightSplitsCalculatorSpec extends Specification with Matchers with Tables {
  "Information about a passenger and their document type tells us what passenger type they are" >> {
    s2"""$passengerType"""
  }

  "Visa Countries" in {
    "can load from csv " in {
      val countriesList: Seq[Either[String, PassengerTypeCalculator.Country]] = PassengerTypeCalculator.loadCountries()
      val errors = countriesList.collect { case Left(e) => e }

      "with no errors" in {
        errors should beEmpty
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
          PassengerTypeCalculator.mostAirports(PaxTypeInfo(None, "N", Nationality(documentCountry), Option(DocumentType(docType)), None)) must_== passengerType
      }
    }"""
  }
}
