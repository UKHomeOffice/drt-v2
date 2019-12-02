package passengersplits.core

import drt.shared.PaxTypes._
import drt.shared.{Nationality, PaxType, PortCode}
import org.slf4j.{Logger, LoggerFactory}

object PassengerTypeCalculator {
  val log: Logger = LoggerFactory.getLogger(getClass)

  import PassengerTypeCalculatorValues._

  case class PaxTypeInfo(disembarkationPortCode: Option[PortCode], inTransitFlag: String, documentCountry: Nationality, documentType: Option[DocumentType], nationalityCode: Option[Nationality])

  def isEea(country: Nationality): Boolean = EEACountries contains country.code

  def isB5JPlus(country: Nationality): Boolean = B5JPlusCountries contains country.code

  val countryAndDocumentTypes: PartialFunction[PaxTypeInfo, PaxType] = {
    case PaxTypeInfo(_, _, country, Some(docType), _) if isEea(country) && docType == DocumentType.Passport => EeaMachineReadable
    case PaxTypeInfo(_, _, country, _, _) if isEea(country) => EeaNonMachineReadable
    case PaxTypeInfo(_, _, country, _, _) if !isEea(country) && isVisaNational(country) => VisaNational
    case PaxTypeInfo(_, _, country, _, _) if !isEea(country) => NonVisaNational
  }

  def isVisaNational(countryCode: Nationality): Boolean = visaCountyCodes.contains(countryCode.code)

  val defaultToVisaNational: PartialFunction[PaxTypeInfo, PaxType] = {
    case _ => VisaNational
  }

  val mostAirports: PartialFunction[PaxTypeInfo, PaxType] = countryAndDocumentTypes orElse defaultToVisaNational

  case class Country(name: String, code3Letter: String, isVisaRequired: Boolean)

  lazy val loadedCountries: Seq[Either[String, Country]] = loadCountries()
  lazy val countries: Seq[Country] = loadedCountries.collect { case Right(c) => c }
  lazy val visaCountries: Seq[Country] = countries.filter(_.isVisaRequired)
  lazy val visaCountyCodes: Set[String] = visaCountries.map(_.code3Letter).toSet

  def loadCountries(): Seq[Either[String, Country]] = {
    log.info(s"Loading countries for passengerTypeCalculator")
    val countryInfoStream = getClass.getClassLoader.getResourceAsStream("countrycodes.csv")
    val asScala = scala.io.Source.fromInputStream(countryInfoStream).getLines().drop(1)
    val visaCountries: Iterator[Either[String, Country]] = for {
      (l, idx) <- asScala.zipWithIndex
    } yield {
      l.split(",", -1) match {
        case Array(name, threeLetterCode, "visa") =>
          Right(Country(name, threeLetterCode, isVisaRequired = true))
        case Array(name, threeLetterCode, _) =>
          Right(Country(name, threeLetterCode, isVisaRequired = false))
        case e =>
          Left(s"error in $idx ${e.toList}")
      }
    }
    visaCountries.toList
  }
}
