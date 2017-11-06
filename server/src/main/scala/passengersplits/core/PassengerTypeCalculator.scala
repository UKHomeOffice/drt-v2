package passengersplits.core

import drt.shared.PassengerSplits.{ PaxTypeAndQueueCounts, SplitsPaxTypeAndQueueCount}
import drt.shared.PaxType
import drt.shared.PaxTypes._
import org.slf4j.LoggerFactory
import passengersplits.core.PassengerTypeCalculator.{mostAirports, passengerInfoFields, whenTransitMatters}
import passengersplits.parsing.VoyageManifestParser
import passengersplits.parsing.VoyageManifestParser.PassengerInfoJson

import scala.collection.immutable.{Iterable, Seq}

trait PassengerQueueCalculator {

  import drt.shared.PaxType
  import drt.shared.Queues._

  def calculateQueuePaxCounts(paxTypeCounts: Map[PaxType, Int], egatePercentage: Double): PaxTypeAndQueueCounts = {
    val queues: Iterable[SplitsPaxTypeAndQueueCount] = paxTypeCounts flatMap (ptaq =>
      if (egatePercentage == 0)
        calculateQueuesFromPaxTypesWithoutEgates(ptaq, egatePercentage)
      else
        calculateQueuesFromPaxTypes(ptaq, egatePercentag = egatePercentage))

    val sortedQueues = queues.toList.sortBy(_.passengerType.toString)
    sortedQueues
  }

  def calculateQueuesFromPaxTypesWithoutEgates(paxTypeAndCount: (PaxType, Int), egatePercentag: Double): Seq[SplitsPaxTypeAndQueueCount] = {
    paxTypeAndCount match {
      case (EeaNonMachineReadable, paxCount) =>
        Seq(SplitsPaxTypeAndQueueCount(EeaNonMachineReadable, EeaDesk, paxCount))
      case (EeaMachineReadable, paxCount) =>
        Seq(SplitsPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, paxCount))
      case (Transit, c) => Seq(SplitsPaxTypeAndQueueCount(Transit, Transfer, c))
      case (otherPaxType, c) => Seq(SplitsPaxTypeAndQueueCount(otherPaxType, NonEeaDesk, c))
    }
  }

  def calculateQueuesFromPaxTypes(paxTypeAndCount: (PaxType, Int), egatePercentag: Double): Seq[SplitsPaxTypeAndQueueCount] = {
    paxTypeAndCount match {
      case (EeaNonMachineReadable, paxCount) =>
        Seq(SplitsPaxTypeAndQueueCount(EeaNonMachineReadable, EeaDesk, paxCount))
      case (EeaMachineReadable, paxCount) =>
        val egatePaxCount = (egatePercentag * paxCount).toInt
        Seq(
          SplitsPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, (paxCount - egatePaxCount)),
          SplitsPaxTypeAndQueueCount(EeaMachineReadable, EGate, egatePaxCount)
        )
      case (otherPaxType, c) => Seq(SplitsPaxTypeAndQueueCount(otherPaxType, NonEeaDesk, c))
    }
  }

  def countPassengerTypes(passengerTypes: Seq[PaxType]): Map[PaxType, Int] = {
    passengerTypes.groupBy((x) => x).mapValues(_.length)
  }

}

object PassengerTypeCalculatorValues {


  object CountryCodes {
    val Austria = "AUT"
    val Australia = "AUS"
    val Belgium = "BEL"
    val Bulgaria = "BGR"
    val Croatia = "HRV"
    val Cyprus = "CYP"
    val Czech = "CZE"
    val Denmark = "DNK"
    val Estonia = "EST"
    val Finland = "FIN"
    val France = "FRA"
    val Germany = "DEU"
    val Greece = "GRC"
    val Hungary = "HUN"
    val Iceland = "ISL"
    val Ireland = "IRL"
    val Italy = "ITA"
    val Latvia = "LVA"
    val Liechtenstein = "LIE"
    val Norway = "NOR"
    val Lithuania = "LTU"
    val Luxembourg = "LUX"
    val Malta = "MLT"
    val Netherlands = "NLD"
    val Poland = "POL"
    val Portugal = "PRT"
    val Romania = "ROU"
    val Slovakia = "SVK"
    val Slovenia = "SVN"
    val Spain = "ESP"
    val Sweden = "SWE"
    val Switzerland = "CHE"
    val UK = "GBR"
  }

  import CountryCodes._

  lazy val EUCountries = {
    Set(
      Austria, Belgium, Bulgaria, Croatia, Cyprus, Czech,
      Denmark, Estonia, Finland, France, Germany, Greece, Hungary, Ireland,
      Italy, Latvia, Lithuania, Luxembourg, Malta, Netherlands, Poland,
      Portugal, Romania, Slovakia, Slovenia, Spain, Sweden,
      UK
    )
  }

  lazy val EEACountries = {
    val extras = Set(Iceland, Norway, Liechtenstein, Switzerland)
    EUCountries ++ extras
  }

  object DocType {
    val Visa = "V"
    val Passport = "P"
  }

  val nonMachineReadableCountries = Set(Italy, Greece, Slovakia, Portugal)

  val EEA = "EEA"
}

object PassengerQueueCalculator extends PassengerQueueCalculator {
  val log = LoggerFactory.getLogger(getClass)

  def convertVoyageManifestIntoPaxTypeAndQueueCounts(portCode: String, manifest: VoyageManifestParser.VoyageManifest): List[SplitsPaxTypeAndQueueCount] = {
    val paxTypeFn = manifest.ArrivalPortCode match {
      case "LHR" => whenTransitMatters(portCode)
      case _ => mostAirports
    }
    val paxInManifest = manifest.PassengerList
    val byIdGrouped: Map[Option[String], List[PassengerInfoJson]] = paxInManifest.groupBy(_.PassengerIdentifier)
    val uniquePax = if (byIdGrouped.size > 1) byIdGrouped.values.toList.collect {
      case head :: _ => head
    } else paxInManifest
    val paxTypes: Seq[PaxType] = uniquePax.map(passengerInfoFields).map(paxTypeFn)
    distributeToQueues(paxTypes)
  }

  private def distributeToQueues(paxTypes: Seq[PaxType]) = {
    val paxTypeCounts = countPassengerTypes(paxTypes)
    val disabledEgatePercentage = 0d

    calculateQueuePaxCounts(paxTypeCounts, disabledEgatePercentage)
  }

}


object PassengerTypeCalculator {
  val log = LoggerFactory.getLogger(getClass)

  import PassengerTypeCalculatorValues._

  case class PaxTypeInfo(disembarkationPortCode: Option[String], inTransitFlag: String, documentCountry: String, documentType: Option[String])

  def isEea(country: String) = EEACountries contains country

  def isNonMachineReadable(country: String) = nonMachineReadableCountries contains country

  def passengerInfoFields(pi: PassengerInfoJson) = PaxTypeInfo(pi.DisembarkationPortCode, pi.InTransitFlag, pi.DocumentIssuingCountryCode, pi.DocumentType)

  def transitMatters(portCode: String): PartialFunction[PaxTypeInfo, PaxType] = {
    case PaxTypeInfo(_, "Y", _, _) => Transit
    case PaxTypeInfo(Some(disembarkPortCode), _, _, _) if disembarkPortCode != portCode => Transit
  }

  val countryAndDocumentTypes: PartialFunction[PaxTypeInfo, PaxType] = {
    case PaxTypeInfo(_, _, country, Some(docType)) if isEea(country) && docType == DocType.Passport => EeaMachineReadable
    case PaxTypeInfo(_, _, country, _) if isEea(country) => EeaNonMachineReadable
    case PaxTypeInfo(_, _, country, _) if !isEea(country) && isVisaNational(country) => VisaNational
    case PaxTypeInfo(_, _, country, _) if !isEea(country) => NonVisaNational
  }

  def isVisaNational(countryCode: String) = visaCountyCodes.contains(countryCode)

  val defaultToVisaNational: PartialFunction[PaxTypeInfo, PaxType] = {
    case _ => VisaNational
  }

  val mostAirports = countryAndDocumentTypes orElse defaultToVisaNational

  def whenTransitMatters(portCode: String) = transitMatters(portCode) orElse mostAirports

  case class Country(name: String, code3Letter: String, isVisaRequired: Boolean)

  lazy val loadedCountries = loadCountries()
  lazy val countries = loadedCountries.collect { case Right(c) => c }
  lazy val visaCountries = countries.filter(_.isVisaRequired)
  lazy val visaCountyCodes = visaCountries.map(_.code3Letter).toSet

  def loadCountries(): Seq[Either[String, Country]] = {
    log.info(s"Loading countries for passengerTypeCalculator")
    val countryInfoStream = getClass.getClassLoader.getResourceAsStream("countrycodes.csv")
    val asScala = scala.io.Source.fromInputStream(countryInfoStream).getLines().drop(1)
    val visaCountries: Iterator[Either[String, Country]] = for {
      (l, idx) <- asScala.zipWithIndex
    } yield {
      l.split(",", -1) match {
        case Array(name, threeLetterCode, "visa") =>
          Right(Country(name, threeLetterCode, true))
        case Array(name, threeLetterCode, _) =>
          Right(Country(name, threeLetterCode, false))
        case e =>
          Left(s"error in $idx ${e.toList}")
      }
    }
    visaCountries.toList
  }
}
