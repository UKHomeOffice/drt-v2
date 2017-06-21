package passengersplits.core

import akka.event.LoggingAdapter
import passengersplits.parsing.VoyageManifestParser.PassengerInfoJson
import drt.shared.{PassengerQueueTypes, PaxType}
import drt.shared.PassengerQueueTypes.PaxTypeAndQueueCounts
import drt.shared.PassengerSplits.SplitsPaxTypeAndQueueCount
import drt.shared.PaxTypes._
import org.slf4j.LoggerFactory

import scala.collection.immutable.Iterable
import scala.collection.immutable.Seq

trait PassengerQueueCalculator {

  import drt.shared.Queues._
  import drt.shared.PaxType

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

object PassengerQueueCalculator extends PassengerQueueCalculator {
  def convertPassengerInfoToPaxQueueCounts(ps: Seq[PassengerInfoJson], egatePercentage: Double): PassengerQueueTypes.PaxTypeAndQueueCounts = {
    val paxTypes = PassengerTypeCalculator.paxTypes(ps)
    val paxTypeCounts = countPassengerTypes(paxTypes)
    val queuePaxCounts = calculateQueuePaxCounts(paxTypeCounts, egatePercentage)
    queuePaxCounts
  }
}


object PassengerTypeCalculator {
  type PassengerType = PaxType

  def paxTypes(passengerInfos: Seq[PassengerInfoJson]) = {
    passengerInfos.map {
      (pi) =>
        if (pi.InTransitFlag == "Y") Transit else
          paxType(pi.EEAFlag, pi.DocumentIssuingCountryCode, pi.DocumentType)
    }
  }


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
    val Ireland = "IRL"
    val Italy = "ITA"
    val Latvia = "LCA"
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
    val Sweden = "SWI"
    val UK = "GBR"
  }

  val EEACountries = {
    import CountryCodes._
    Set(
      Austria, Belgium, Bulgaria, Croatia, Cyprus, Czech,
      Denmark, Estonia, Finland, France, Germany, Greece, Hungary, Ireland,
      Italy, Latvia, Lithuania, Luxembourg, Malta, Netherlands, Poland,
      Portugal, Romania, Slovakia, Slovenia, Spain, Sweden,
      UK
    )
  }

  object DocType {
    val Visa = "V"
    val Passport = "P"
  }

  val nonMachineReadableCountries = {
    import CountryCodes._
    Set(Italy, Greece, Slovakia, Portugal)
  }

  val EEA = "EEA"

  def paxType(eeaFlag: String, documentCountry: String, documentType: Option[String]): PassengerType = {
    (eeaFlag, documentCountry, documentType) match {
      case (EEA, country, _) if nonMachineReadableCountries contains (country) => EeaNonMachineReadable
      case (EEA, country, _) if (EEACountries contains country) => EeaMachineReadable
      case ("", country, Some(DocType.Visa)) if !(EEACountries contains country) => VisaNational
      case ("", country, Some(DocType.Passport)) if !(EEACountries contains country) => NonVisaNational
      case _ => VisaNational
    }
  }
}
