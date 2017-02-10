package passengersplits.core
import passengersplits.core
import core.PassengerQueueTypes.{QueueType, PaxTypeAndQueueCount}
import core.PassengerTypeCalculator.PassengerType
import passengersplits.parsing.PassengerInfoParser.PassengerInfoJson

import scala.collection.immutable.Iterable

object PassengerQueueTypes {
  object Desks {
    val eeaDesk = 'desk
    val egate = 'egate
    val nationalsDesk = 'nationalsDesk
  }

  object PaxTypes {
    val EEANONMACHINEREADABLE = "eea-non-machine-readable"
    val NATIONALVISA = "national-visa"
    val EEAMACHINEREADABLE = "eea-machine-readable"
    val NATIONALNONVISA = "national-non-visa"
  }

  val egatePercentage = 0.6

  type QueueType = Symbol
//  type PaxTypeAndQueue = (PassengerType, QueueType)
  case class PaxTypeAndQueueCount(passengerType: String, queueType: Symbol, paxCount: Int)
  type PaxTypeAndQueueCounts = Seq[PaxTypeAndQueueCount]
}

trait PassengerQueueCalculator {
  import PassengerQueueTypes._
  import PassengerQueueTypes.Desks._
  import PassengerQueueTypes.PaxTypes._

  def calculateQueuePaxCounts(paxTypeCounts: Map[PassengerType, Int]): PaxTypeAndQueueCounts = {
    val queues: Iterable[PaxTypeAndQueueCount] = paxTypeCounts flatMap calculateQueuesFromPaxTypes
    queues.toSeq
  }

  def calculateQueuesFromPaxTypes(paxTypeAndCount: (PassengerType, Int)): Seq[PaxTypeAndQueueCount] = {
    paxTypeAndCount match {
      case (EEANONMACHINEREADABLE, c) =>
        Seq(PaxTypeAndQueueCount(EEANONMACHINEREADABLE, eeaDesk, c))
      case (EEAMACHINEREADABLE, paxCount) =>
        val egatePaxCount = (egatePercentage * paxCount).toInt
        Seq(
          PaxTypeAndQueueCount(EEAMACHINEREADABLE, eeaDesk,  (paxCount - egatePaxCount)),
          PaxTypeAndQueueCount(EEAMACHINEREADABLE, egate, egatePaxCount)
        )
      case (otherPaxType, c) => Seq(PaxTypeAndQueueCount(otherPaxType, nationalsDesk, c))
    }
  }

  type PassengerType = String

  def countPassengerTypes(passengerTypes: Seq[PassengerType]): Map[PassengerType, Int] = {
    passengerTypes.groupBy((x) => x).mapValues(_.length)
  }

}

object PassengerQueueCalculator extends PassengerQueueCalculator {
  def convertPassengerInfoToPaxQueueCounts(ps: Seq[PassengerInfoJson]): PassengerQueueTypes.PaxTypeAndQueueCounts = {
    val paxTypes = PassengerTypeCalculator.paxTypes(ps)
    val paxTypeCounts = countPassengerTypes(paxTypes)
    val queuePaxCounts = calculateQueuePaxCounts(paxTypeCounts)
    queuePaxCounts
  }
}


object PassengerTypeCalculator {
  type PassengerType = String

  def paxTypes(passengerInfos: Seq[PassengerInfoJson]) = {
    passengerInfos.map {
      (pi) =>
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
      case (EEA, country, _) if nonMachineReadableCountries contains (country) => "eea-non-machine-readable"
      case (EEA, country, _) if (EEACountries contains country) => "eea-machine-readable"
      case ("", country, Some(DocType.Visa)) if !(EEACountries contains country) => "national-visa"
      case ("", country, Some(DocType.Passport)) if !(EEACountries contains country) => "national-non-visa"
      case _ => "national-visa"
    }
  }
}
