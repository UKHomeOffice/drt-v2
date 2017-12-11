package passengersplits.core

import controllers.SystemActors.SplitsProvider
import drt.shared.PassengerSplits.PaxTypeAndQueueCounts
import drt.shared.PaxTypes._
import drt.shared.Queues.{EGate, EeaDesk}
import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios, SplitSources}
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.core.PassengerTypeCalculator.{mostAirports, passengerInfoFields, whenTransitMatters}
import passengersplits.parsing.VoyageManifestParser
import passengersplits.parsing.VoyageManifestParser.PassengerInfoJson
import services.FastTrackPercentages
import services.workloadcalculator.PaxLoadCalculator.Load


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

case class SplitsCalculator(portCode: String, csvSplitsProvider: SplitsProvider, portDefaultSplits: Set[SplitRatio]) {

  val log: Logger = LoggerFactory.getLogger(getClass)

  def splitsForArrival(manifest: VoyageManifestParser.VoyageManifest, arrival: ApiFlightWithSplits): ApiSplits = {
    val paxTypeAndQueueCounts = SplitsCalculator.convertVoyageManifestIntoPaxTypeAndQueueCounts(portCode, manifest).toSet
    val withEgateAndFastTrack = addEgatesAndFastTrack(arrival, paxTypeAndQueueCounts)

    ApiSplits(withEgateAndFastTrack, SplitSources.ApiSplitsWithCsvPercentage, Some(manifest.EventCode), PaxNumbers)
  }

  def terminalAndHistoricSplits(fs: Arrival): Set[ApiSplits] = {
    val historical: Option[Set[ApiPaxTypeAndQueueCount]] = historicalSplits(fs)
    val portDefault: Set[ApiPaxTypeAndQueueCount] = portDefaultSplits.map {
      case SplitRatio(ptqc, ratio) => ApiPaxTypeAndQueueCount(ptqc.passengerType, ptqc.queueType, ratio, None)
    }

    val defaultSplits = Set(ApiSplits(portDefault.map(aptqc => aptqc.copy(paxCount = aptqc.paxCount * 100)), SplitSources.TerminalAverage, None, Percentage))

    historical match {
      case None => defaultSplits
      case Some(h) => Set(ApiSplits(h, SplitSources.Historical, None, Percentage)) ++ defaultSplits
    }
  }

  def historicalSplits(fs: Arrival): Option[Set[ApiPaxTypeAndQueueCount]] = {
    csvSplitsProvider(fs).map(ratios => {
      val splitRatios: Set[SplitRatio] = ratios.splits.toSet
      splitRatios.map {
        case SplitRatio(ptqc, ratio) => ApiPaxTypeAndQueueCount(ptqc.passengerType, ptqc.queueType, ratio * 100, None)
      }
    })
  }

  def addEgatesAndFastTrack(arrival: ApiFlightWithSplits, apiPaxTypeAndQueueCounts: Set[ApiPaxTypeAndQueueCount]): Set[ApiPaxTypeAndQueueCount] = {
    val csvSplits = csvSplitsProvider(arrival.apiFlight)
    val egatePercentage: Load = egatePercentageFromSplit(csvSplits, 0.6)
    val fastTrackPercentages: FastTrackPercentages = fastTrackPercentagesFromSplit(csvSplits, 0d, 0d)
    val ptqcWithCsvEgates = applyEgatesSplits(apiPaxTypeAndQueueCounts, egatePercentage)
    val ptqcwithCsvEgatesFastTrack = applyFastTrackSplits(ptqcWithCsvEgates, fastTrackPercentages)
    ptqcwithCsvEgatesFastTrack
  }

  def fastTrackPercentagesFromSplit(splitOpt: Option[SplitRatios], defaultVisaPct: Double, defaultNonVisaPct: Double): FastTrackPercentages = {
    val visaNational = splitOpt
      .map {
        ratios =>

          val splits = ratios.splits
          val visaNationalSplits = splits.filter(s => s.paxType.passengerType == PaxTypes.VisaNational)

          val totalVisaNationalSplit = visaNationalSplits.map(_.ratio).sum

          splits
            .find(p => p.paxType.passengerType == PaxTypes.VisaNational && p.paxType.queueType == Queues.FastTrack)
            .map(_.ratio / totalVisaNationalSplit).getOrElse(defaultVisaPct)
      }.getOrElse(defaultVisaPct)

    val nonVisaNational = splitOpt
      .map {
        ratios =>
          val splits = ratios.splits
          val totalNonVisaNationalSplit = splits.filter(s => s.paxType.passengerType == PaxTypes.NonVisaNational).map(_.ratio).sum

          splits
            .find(p => p.paxType.passengerType == PaxTypes.NonVisaNational && p.paxType.queueType == Queues.FastTrack)
            .map(_.ratio / totalNonVisaNationalSplit).getOrElse(defaultNonVisaPct)
      }.getOrElse(defaultNonVisaPct)
    FastTrackPercentages(visaNational, nonVisaNational)
  }

  def egatePercentageFromSplit(splitOpt: Option[SplitRatios], defaultPct: Double): Double = {
    splitOpt
      .map { x =>
        val splits = x.splits
        val interestingSplits = splits.filter(s => s.paxType.passengerType == PaxTypes.EeaMachineReadable)
        val interestingSplitsTotal = interestingSplits.map(_.ratio).sum
        splits
          .find(p => p.paxType.queueType == Queues.EGate)
          .map(_.ratio / interestingSplitsTotal).getOrElse(defaultPct)
      }.getOrElse(defaultPct)
  }

  def applyEgatesSplits(ptaqc: Set[ApiPaxTypeAndQueueCount], egatePct: Double): Set[ApiPaxTypeAndQueueCount] = {
    ptaqc.flatMap {
      case s@ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, count, _) =>
        val eeaDeskPax = Math.round(count * (1 - egatePct)).toInt
        s.copy(queueType = EGate, paxCount = count - eeaDeskPax) ::
          s.copy(queueType = EeaDesk, paxCount = eeaDeskPax) :: Nil
      case s => s :: Nil
    }
  }

  def applyFastTrackSplits(ptaqc: Set[ApiPaxTypeAndQueueCount], fastTrackPercentages: FastTrackPercentages): Set[ApiPaxTypeAndQueueCount] = {
    val results = ptaqc.flatMap {
      case s@ApiPaxTypeAndQueueCount(NonVisaNational, Queues.NonEeaDesk, count, _) if fastTrackPercentages.nonVisaNational != 0 =>
        val nonVisaNationalNonEeaDesk = Math.round(count * (1 - fastTrackPercentages.nonVisaNational)).toInt
        s.copy(queueType = Queues.FastTrack, paxCount = count - nonVisaNationalNonEeaDesk) ::
          s.copy(paxCount = nonVisaNationalNonEeaDesk) :: Nil
      case s@ApiPaxTypeAndQueueCount(VisaNational, Queues.NonEeaDesk, count, _) if fastTrackPercentages.visaNational != 0 =>
        val visaNationalNonEeaDesk = Math.round(count * (1 - fastTrackPercentages.visaNational)).toInt
        s.copy(queueType = Queues.FastTrack, paxCount = count - visaNationalNonEeaDesk) ::
          s.copy(paxCount = visaNationalNonEeaDesk) :: Nil
      case s => s :: Nil
    }
    log.debug(s"applied fastTrack $fastTrackPercentages got $ptaqc")
    results
  }
}

object SplitsCalculator {

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

  def distributeToQueues(paxTypeAndNationalities: Seq[(PaxType, Option[String])]): List[ApiPaxTypeAndQueueCount] = {
    val paxTypeCountAndNats: Map[PaxType, (Int, Option[Map[String, Double]])] = SplitsCalculator.countPassengerTypes(paxTypeAndNationalities)
    val disabledEgatePercentage = 0d

    SplitsCalculator.calculateQueuePaxCounts(paxTypeCountAndNats, disabledEgatePercentage)
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
