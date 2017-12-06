package passengersplits.core

import drt.shared.PassengerSplits.PaxTypeAndQueueCounts
import drt.shared.PaxTypes._
import drt.shared.{ApiPaxTypeAndQueueCount, PaxType}
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.core.PassengerTypeCalculator.{mostAirports, passengerInfoFields, whenTransitMatters}
import passengersplits.parsing.VoyageManifestParser
import passengersplits.parsing.VoyageManifestParser.PassengerInfoJson

trait PassengerQueueCalculator {

  import drt.shared.PaxType
  import drt.shared.Queues._

  def calculateQueuePaxCounts(paxTypeCountAndNats: Map[PaxType, (Int, Option[Map[String, Double]])], egatePercentage: Double): PaxTypeAndQueueCounts = {
    val queues: Iterable[ApiPaxTypeAndQueueCount] = paxTypeCountAndNats flatMap {
      case (pType, (pCount, pNats)) =>
        if (egatePercentage == 0)
          calculateQueuesFromPaxTypesWithoutEgates(pType, pCount, pNats, egatePercentage)
        else
          calculateQueuesFromPaxTypes(pType, pCount, pNats, egatePercentage)
    }

    val sortedQueues = queues.toList.sortBy(_.passengerType.toString)
    sortedQueues
  }

  def calculateQueuesFromPaxTypesWithoutEgates(paxType: PaxType, paxCount: Int, paxNats: Option[Map[String, Double]], egatePercentage: Double): Seq[ApiPaxTypeAndQueueCount] = {
    paxType match {
      case EeaNonMachineReadable =>
        Seq(ApiPaxTypeAndQueueCount(EeaNonMachineReadable, EeaDesk, paxCount, paxNats))
      case EeaMachineReadable =>
        Seq(ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, paxCount, paxNats))
      case Transit => Seq(ApiPaxTypeAndQueueCount(Transit, Transfer, paxCount, paxNats))
      case otherPaxType => Seq(ApiPaxTypeAndQueueCount(otherPaxType, NonEeaDesk, paxCount, paxNats))
    }
  }

  def calculateQueuesFromPaxTypes(paxType: PaxType, paxCount: Int, paxNats: Option[Map[String, Double]], egatePercentage: Double): Seq[ApiPaxTypeAndQueueCount] = {
    paxType match {
      case EeaNonMachineReadable =>
        Seq(ApiPaxTypeAndQueueCount(EeaNonMachineReadable, EeaDesk, paxCount, paxNats))
      case EeaMachineReadable =>
        val egatePaxCount = (egatePercentage * paxCount).toInt
        Seq(
          ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, paxCount - egatePaxCount, paxNats),
          ApiPaxTypeAndQueueCount(EeaMachineReadable, EGate, egatePaxCount, paxNats)
        )
      case otherPaxType => Seq(ApiPaxTypeAndQueueCount(otherPaxType, NonEeaDesk, paxCount, paxNats))
    }
  }

  def countPassengerTypes(paxTypeAndNationalities: Seq[(PaxType, Option[String])]): Map[PaxType, (Int, Option[Map[String, Double]])] = {
    val typeToTuples: Map[PaxType, Seq[(PaxType, Option[String])]] = paxTypeAndNationalities
      .groupBy {
        case (pt, _) => pt
      }

    val paxTypeCountAndNats: Map[PaxType, (Int, Option[Map[String, Double]])] = typeToTuples
      .mapValues {
        case ptNats =>
          val natCounts: Map[String, Double] = ptNats
            .groupBy {
              case (_, maybeNat) => maybeNat
            }
            .collect {
              case (Some(nat), pax) => (nat, pax.length.toDouble)
            }
          if (natCounts.values.sum == ptNats.length)
            Tuple2(ptNats.length, Some(natCounts))
          else
            Tuple2(ptNats.length, None)
      }
    paxTypeCountAndNats
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

  lazy val EUCountries: Set[String] = {
    Set(
      Austria,
      Belgium,
      Bulgaria,
      Croatia,
      Cyprus,
      Czech,
      Denmark,
      Estonia,
      Finland,
      France,
      Germany,
      Greece,
      Hungary,
      Ireland,
      Italy,
      Latvia,
      Lithuania,
      Luxembourg,
      Malta,
      Netherlands,
      Poland,
      Portugal,
      Romania,
      Slovakia,
      Slovenia,
      Spain,
      Sweden,
      UK
    )
  }

  lazy val EEACountries: Set[String] = {
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
  val log: Logger = LoggerFactory.getLogger(getClass)

  def convertVoyageManifestIntoPaxTypeAndQueueCounts(portCode: String, manifest: VoyageManifestParser.VoyageManifest): List[ApiPaxTypeAndQueueCount] = {
    val paxTypeFn = manifest.ArrivalPortCode match {
      case "LHR" => whenTransitMatters(portCode)
      case _ => mostAirports
    }
    val paxInManifest = manifest.PassengerList
    val byIdGrouped: Map[Option[String], List[PassengerInfoJson]] = paxInManifest.groupBy(_.PassengerIdentifier)
    val uniquePax: Seq[PassengerInfoJson] = if (byIdGrouped.size > 1) byIdGrouped.values.toList.collect {
      case head :: _ => head
    } else paxInManifest
    val paxTypeInfos: Seq[PassengerTypeCalculator.PaxTypeInfo] = uniquePax.map(passengerInfoFields)
    val paxTypes: Seq[(PaxType, Option[String])] = paxTypeInfos.map(pti => Tuple2(paxTypeFn(pti), pti.nationalityCode))
    distributeToQueues(paxTypes)
  }

  private def distributeToQueues(paxTypeAndNationalities: Seq[(PaxType, Option[String])]): List[ApiPaxTypeAndQueueCount] = {
    val paxTypeCountAndNats: Map[PaxType, (Int, Option[Map[String, Double]])] = countPassengerTypes(paxTypeAndNationalities)
    val disabledEgatePercentage = 0d

    calculateQueuePaxCounts(paxTypeCountAndNats, disabledEgatePercentage)
  }
}


object PassengerTypeCalculator {
  val log: Logger = LoggerFactory.getLogger(getClass)

  import PassengerTypeCalculatorValues._

  case class PaxTypeInfo(disembarkationPortCode: Option[String], inTransitFlag: String, documentCountry: String, documentType: Option[String], nationalityCode: Option[String])

  def isEea(country: String): Boolean = EEACountries contains country

  def isNonMachineReadable(country: String): Boolean = nonMachineReadableCountries contains country

  def passengerInfoFields(pi: PassengerInfoJson) = PaxTypeInfo(pi.DisembarkationPortCode, pi.InTransitFlag, pi.DocumentIssuingCountryCode, pi.DocumentType, pi.NationalityCountryCode)

  def transitMatters(portCode: String): PartialFunction[PaxTypeInfo, PaxType] = {
    case PaxTypeInfo(_, "Y", _, _, _) => Transit
    case PaxTypeInfo(Some(disembarkPortCode), _, _, _, _) if disembarkPortCode.nonEmpty && disembarkPortCode != portCode => Transit
  }

  val countryAndDocumentTypes: PartialFunction[PaxTypeInfo, PaxType] = {
    case PaxTypeInfo(_, _, country, Some(docType), _) if isEea(country) && docType == DocType.Passport => EeaMachineReadable
    case PaxTypeInfo(_, _, country, _, _) if isEea(country) => EeaNonMachineReadable
    case PaxTypeInfo(_, _, country, _, _) if !isEea(country) && isVisaNational(country) => VisaNational
    case PaxTypeInfo(_, _, country, _, _) if !isEea(country) => NonVisaNational
  }

  def isVisaNational(countryCode: String): Boolean = visaCountyCodes.contains(countryCode)

  val defaultToVisaNational: PartialFunction[PaxTypeInfo, PaxType] = {
    case _ => VisaNational
  }

  val mostAirports: PartialFunction[PaxTypeInfo, PaxType] = countryAndDocumentTypes orElse defaultToVisaNational

  def whenTransitMatters(portCode: String): PartialFunction[PaxTypeInfo, PaxType] = transitMatters(portCode) orElse mostAirports

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
