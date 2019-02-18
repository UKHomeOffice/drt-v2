package manifests.passengers

import drt.shared.PaxType
import drt.shared.PaxTypes._
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.core.PassengerTypeCalculatorValues._
import passengersplits.core.PassengerTypeCalculatorValues.DocType
import passengersplits.parsing.VoyageManifestParser.PassengerInfoJson

object PassengerTypeCalculator {
  val log: Logger = LoggerFactory.getLogger(getClass)

  case class PaxTypeInfo(disembarkationPortCode: Option[String], inTransitFlag: String, documentCountry: String, documentType: Option[String], nationalityCode: Option[String])

  def isEea(country: String): Boolean = EEACountries contains country

  def isNonMachineReadable(country: String): Boolean = nonMachineReadableCountries contains country

  def passengerInfoFields(pi: PassengerInfoJson) = PaxTypeInfo(pi.DisembarkationPortCode, pi.InTransitFlag, pi.DocumentIssuingCountryCode, pi.DocumentType, pi.NationalityCountryCode)

  def transitMatters(portCode: String): PartialFunction[ManifestPassengerProfile, PaxType] = {
    case ManifestPassengerProfile(_, _, _, Some(true)) => Transit
  }

  val countryAndDocumentTypes: PartialFunction[ManifestPassengerProfile, PaxType] = {
    case ManifestPassengerProfile(country, Some(docType), _, _) if isEea(country) && docType == DocType.Passport => EeaMachineReadable
    case ManifestPassengerProfile(country, _, _, _) if isEea(country) => EeaNonMachineReadable
    case ManifestPassengerProfile(country, _, _, _) if !isEea(country) && isVisaNational(country) => VisaNational
    case ManifestPassengerProfile(country, _, _, _) if !isEea(country) => NonVisaNational
  }

  def isVisaNational(countryCode: String): Boolean = visaCountyCodes.contains(countryCode)

  val defaultToVisaNational: PartialFunction[Any, PaxType] = {
    case _ => VisaNational
  }

  val mostAirports: PartialFunction[ManifestPassengerProfile, PaxType] = countryAndDocumentTypes orElse defaultToVisaNational

  def whenTransitMatters(portCode: String): PartialFunction[ManifestPassengerProfile, PaxType] = transitMatters(portCode) orElse mostAirports

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
