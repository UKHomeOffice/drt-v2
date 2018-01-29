package passengersplits.core

import drt.shared.PassengerSplits.PaxTypeAndQueueCounts
import drt.shared.PaxTypes._
import drt.shared.Queues.EeaDesk
import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios, SplitSources}
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.core.PassengerTypeCalculator.{mostAirports, passengerInfoFields, whenTransitMatters}
import passengersplits.parsing.VoyageManifestParser
import passengersplits.parsing.VoyageManifestParser.PassengerInfoJson
import services.{FastTrackPercentages, SplitsProvider}
import services.workloadcalculator.PaxLoadCalculator.Load


case class SplitsCalculator(portCode: String, csvSplitsProvider: SplitsProvider.SplitProvider, portDefaultSplits: Set[SplitRatio]) {

  val log: Logger = LoggerFactory.getLogger(getClass)

  def splitsForArrival(manifest: VoyageManifestParser.VoyageManifest, arrival: Arrival): ApiSplits = {
    val paxTypeAndQueueCounts = SplitsCalculator.convertVoyageManifestIntoPaxTypeAndQueueCounts(portCode, manifest).toSet
    val withEgateAndFastTrack = addEgatesAndFastTrack(arrival, paxTypeAndQueueCounts)

    ApiSplits(withEgateAndFastTrack, SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages, Some(manifest.EventCode), PaxNumbers)
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
    csvSplitsProvider(fs.IATA, MilliDate(fs.Scheduled)).map(ratios => {
      val splitRatios: Set[SplitRatio] = ratios.splits.toSet
      splitRatios.map {
        case SplitRatio(ptqc, ratio) => ApiPaxTypeAndQueueCount(ptqc.passengerType, ptqc.queueType, ratio * 100, None)
      }
    })
  }

  def addEgatesAndFastTrack(arrival: Arrival, apiPaxTypeAndQueueCounts: Set[ApiPaxTypeAndQueueCount]): Set[ApiPaxTypeAndQueueCount] = {
    val csvSplits = csvSplitsProvider(arrival.IATA, MilliDate(arrival.Scheduled))
    val egatePercentage = egatePercentageFromSplit(csvSplits, 0.6)
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
      case s@ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, eeaPax, _) =>
        SplitsCalculator.splitQueue(egatePct, Queues.EGate, s, eeaPax)

      case s => s :: Nil
    }
  }

  def applyFastTrackSplits(ptaqc: Set[ApiPaxTypeAndQueueCount], fastTrackPercentages: FastTrackPercentages): Set[ApiPaxTypeAndQueueCount] = {
    val results = ptaqc.flatMap {
      case s@ApiPaxTypeAndQueueCount(NonVisaNational, Queues.NonEeaDesk, nonEeaPax, _) if fastTrackPercentages.nonVisaNational != 0 =>
        SplitsCalculator.splitQueue(fastTrackPercentages.nonVisaNational, Queues.FastTrack, s, nonEeaPax)

      case s@ApiPaxTypeAndQueueCount(VisaNational, Queues.NonEeaDesk, nonEeaPax, _) if fastTrackPercentages.visaNational != 0 =>
        SplitsCalculator.splitQueue(fastTrackPercentages.visaNational, Queues.FastTrack, s, nonEeaPax)

      case s => s :: Nil
    }
    log.debug(s"applied fastTrack $fastTrackPercentages got $ptaqc")
    results
  }
}

object SplitsCalculator {

  def countPassengerTypes(paxTypeAndNationalities: Seq[(PaxType, Option[String])]): Map[PaxType, (Int, Option[Map[String, Double]])] = paxTypeAndNationalities
    .groupBy {
      case (pt, _) => pt
    }
    .mapValues(ptNats => {
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
    })

  import drt.shared.PaxType
  import drt.shared.Queues._

  def calculateQueuePaxCounts(paxTypeCountAndNats: Map[PaxType, (Int, Option[Map[String, Double]])], egatePercentage: Double): PaxTypeAndQueueCounts = paxTypeCountAndNats
    .flatMap {
      case (pType, (pCount, pNats)) =>
        if (egatePercentage == 0)
          calculateQueuesFromPaxTypesWithoutEgates(pType, pCount, pNats, egatePercentage)
        else
          calculateQueuesFromPaxTypes(pType, pCount, pNats, egatePercentage)
    }
    .toList
    .sortBy(_.passengerType.toString)

  def calculateQueuesFromPaxTypesWithoutEgates(paxType: PaxType, paxCount: Int, paxNats: Option[Map[String, Double]], egatePercentage: Double): Seq[ApiPaxTypeAndQueueCount] = paxType match {
    case EeaNonMachineReadable =>
      Seq(ApiPaxTypeAndQueueCount(EeaNonMachineReadable, EeaDesk, paxCount, paxNats))
    case EeaMachineReadable =>
      Seq(ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, paxCount, paxNats))
    case Transit => Seq(ApiPaxTypeAndQueueCount(Transit, Transfer, paxCount, paxNats))
    case otherPaxType => Seq(ApiPaxTypeAndQueueCount(otherPaxType, NonEeaDesk, paxCount, paxNats))
  }

  def calculateQueuesFromPaxTypes(paxType: PaxType, paxCount: Int, paxNats: Option[Map[String, Double]], egatePercentage: Double): Seq[ApiPaxTypeAndQueueCount] = paxType match {
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

  def splitQueue(subSplitPercentage: Double, subSplitQueue: String, s: ApiPaxTypeAndQueueCount, totalPax: Load): Seq[ApiPaxTypeAndQueueCount] = {
    val newSplitPax = Math.round(totalPax * subSplitPercentage).toInt
    val remainingPax = totalPax - newSplitPax

    val actualRatio = remainingPax.toDouble / totalPax
    val newSplitNationalities = reduceNationalities(s, actualRatio)
    val remainingNationalities = reduceNationalities(s, 1 - actualRatio)

    List(
      s.copy(paxCount = newSplitPax, nationalities = newSplitNationalities, queueType = subSplitQueue),
      s.copy(paxCount = remainingPax, nationalities = remainingNationalities))
  }

  def reduceNationalities(split: ApiPaxTypeAndQueueCount, reductionFactor: Load): Option[Map[String, Load]] = split
    .nationalities
    .map(_.map {
      case (nat, natCount) => (nat, natCount * (1 - reductionFactor))
    })
}
