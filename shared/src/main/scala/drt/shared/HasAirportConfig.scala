package drt.shared

import drt.shared.FlightsApi.{QueueName, TerminalName}
import drt.shared.PassengerSplits.QueueType
import drt.shared.PaxTypes._
import drt.shared.Queues.{EGate, EeaDesk, NonEeaDesk}
import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios, SplitSources}
import ujson.Js.Value
import upickle.Js
import upickle.default._
import upickle.default.{macroRW, ReadWriter => RW}


object Queues {
  val EeaDesk = "eeaDesk"
  val EGate = "eGate"
  val NonEeaDesk = "nonEeaDesk"
  val FastTrack = "fastTrack"
  val Transfer = "transfer"
  val QueueDesk = "queueDesk"

  val queueOrder = List(QueueDesk, EGate, EeaDesk, NonEeaDesk, FastTrack)

  def inOrder(queuesToSort: Seq[QueueName]): Seq[QueueName] = queueOrder.filter(q => queuesToSort.contains(q))

  val queueDisplayNames: Map[QueueName, String] = Map(
    EeaDesk -> "EEA",
    NonEeaDesk -> "Non-EEA",
    EGate -> "e-Gates",
    FastTrack -> "Fast Track",
    Transfer -> "Tx",
    QueueDesk -> "Desk"
  )

  val exportQueueOrderSansFastTrack = List(EeaDesk, NonEeaDesk, EGate)
  val exportQueueOrderWithFastTrack = List(EeaDesk, NonEeaDesk, EGate, FastTrack)
  val exportQueueDisplayNames: Map[QueueName, String] = Map(
    EeaDesk -> "EEA",
    NonEeaDesk -> "NON-EEA",
    EGate -> "E-GATES",
    FastTrack -> "FAST TRACK"
  )
}

sealed trait PaxType {
  def name: String = getClass.getSimpleName

  def cleanName: String = getClass.getSimpleName.dropRight(1)
}

object PaxType {
  def apply(paxTypeString: String): PaxType = paxTypeString match {
    case "EeaNonMachineReadable$" => EeaNonMachineReadable
    case "Transit$" => Transit
    case "VisaNational$" => VisaNational
    case "EeaMachineReadable$" => EeaMachineReadable
    case "NonVisaNational$" => NonVisaNational
    case "B5JPlusNational$" => B5JPlusNational
    case "EeaBelowEGateAge$" => EeaBelowEGateAge
    case "B5JPlusNationalBelowEGateAge$" => B5JPlusNationalBelowEGateAge
    case _ => UndefinedPaxType
  }

  implicit val paxTypeReaderWriter: ReadWriter[PaxType] =
    readwriter[Js.Value].bimap[PaxType](paxType => paxType.cleanName, (s: Value) => PaxType(s"${s.str}$$"))
}

object PaxTypes {

  case object EeaNonMachineReadable extends PaxType

  case object Transit extends PaxType

  case object VisaNational extends PaxType

  case object EeaMachineReadable extends PaxType

  case object EeaBelowEGateAge extends PaxType

  case object NonVisaNational extends PaxType

  case object B5JPlusNational extends PaxType

  case object B5JPlusNationalBelowEGateAge extends PaxType

  case object UndefinedPaxType extends PaxType

  def displayName(pt: PaxType): String = pt match {
    case EeaMachineReadable => "EEA Machine Readable"
    case EeaNonMachineReadable => "EEA Non-Machine Readable"
    case EeaBelowEGateAge => "EEA Child"
    case VisaNational => "Visa National"
    case NonVisaNational => "Non-Visa National"
    case B5JPlusNational => "B5J+ National"
    case B5JPlusNationalBelowEGateAge => "B5J+ Child"
    case Transit => "Transit"
    case other => other.name
  }
}

case class PaxTypeAndQueue(passengerType: PaxType, queueType: String)

object PaxTypeAndQueue {
  def apply(split: ApiPaxTypeAndQueueCount): PaxTypeAndQueue = PaxTypeAndQueue(split.passengerType, split.queueType)

  implicit val rw: RW[PaxTypeAndQueue] = macroRW
}

object ProcessingTimes {
  val nationalityProcessingTimes: Map[QueueType, Double] = Map(
    "AUT" -> 22.7, "BEL" -> 22.7, "BGR" -> 22.7, "HRV" -> 22.7, "CYP" -> 22.7, "CZE" -> 22.7, "DNK" -> 22.7,
    "EST" -> 22.7, "FIN" -> 22.7, "FRA" -> 22.7, "DEU" -> 22.7, "HUN" -> 22.7, "IRL" -> 22.7, "LVA" -> 22.7,
    "LTU" -> 22.7, "LUX" -> 22.7, "MLT" -> 22.7, "NLD" -> 22.7, "POL" -> 22.7, "PRT" -> 22.7, "ROU" -> 22.7,
    "SVK" -> 22.7, "SVN" -> 22.7, "ESP" -> 22.7, "SWE" -> 22.7, "GBR" -> 22.7, "GRC" -> 64.0, "ITA" -> 50.5,
    "USA" -> 69.6, "CHN" -> 75.7, "IND" -> 79.0, "AUS" -> 69.5, "CAN" -> 66.6, "SAU" -> 76.3, "JPN" -> 69.5,
    "NGA" -> 79.2, "KOR" -> 70.1, "NZL" -> 69.5, "RUS" -> 79.5, "BRA" -> 86.0, "PAK" -> 82.4, "KWT" -> 80.8,
    "TUR" -> 77.5, "ISR" -> 66.3, "ZAF" -> 78.3, "MYS" -> 69.8, "MEX" -> 82.9, "PHL" -> 86.2, "QAT" -> 79.0,
    "UKR" -> 82.2, "ARG" -> 80.7, "ARE" -> 81.0, "THA" -> 77.8, "TWN" -> 75.2, "SGP" -> 72.0, "EGY" -> 79.8,
    "LKA" -> 72.2, "GHA" -> 87.8, "IRN" -> 77.0, "BGD" -> 80.0, "IDN" -> 82.1, "COL" -> 81.8, "CHL" -> 84.2,
    "KEN" -> 87.5, "BHR" -> 79.9, "XXB" -> 71.9, "LBN" -> 66.2, "MUS" -> 78.3, "OMN" -> 82.9, "DZA" -> 83.7,
    "JAM" -> 84.0, "NPL" -> 77.8, "MAR" -> 83.2, "ALB" -> 69.7, "JOR" -> 77.3, "TTO" -> 84.7, "VNM" -> 87.7,
    "ZWE" -> 75.5, "IRQ" -> 81.3, "SRB" -> 77.2, "BLR" -> 78.3, "KAZ" -> 80.9, "SYR" -> 85.4, "ZIM" -> 77.2,
    "AFG" -> 82.1, "GBN" -> 75.2, "VEN" -> 75.7, "PER" -> 83.2, "UGA" -> 88.8, "TUN" -> 85.3, "SDN" -> 85.1,
    "AZE" -> 80.3, "BRB" -> 85.8, "TZA" -> 82.9, "SLE" -> 93.1, "HKG" -> 72.3, "ERI" -> 92.8, "CMR" -> 85.2,
    "ECU" -> 78.6, "LBY" -> 82.2, "URY" -> 94.5, "CRI" -> 89.1, "ZMB" -> 85.4, "BIH" -> 72.3, "COD" -> 90.2,
    "ISL" -> 28.3, "None" -> 30.0, "MKD" -> 72.6, "GEO" -> 83.4, "AGO" -> 94.8, "GMB" -> 81.3, "UZB" -> 72.6,
    "KNA" -> 83.8, "SOM" -> 90.6, "LCA" -> 89.3, "GRD" -> 105.9
  )
}


case class AirportConfig(portCode: String = "n/a",
                         queues: Map[TerminalName, Seq[QueueName]],
                         divertedQueues: Map[QueueName, QueueName] = Map(),
                         slaByQueue: Map[String, Int],
                         terminalNames: Seq[TerminalName],
                         timeToChoxMillis: Long = 300000L,
                         firstPaxOffMillis: Long = 180000L,
                         defaultWalkTimeMillis: Map[TerminalName, Long],
                         terminalPaxSplits: Map[TerminalName, SplitRatios],
                         terminalProcessingTimes: Map[TerminalName, Map[PaxTypeAndQueue, Double]],
                         minMaxDesksByTerminalQueue: Map[TerminalName, Map[QueueName, (List[Int], List[Int])]],
                         shiftExamples: Seq[String] = Seq(),
                         fixedPointExamples: Seq[String] = Seq(),
                         hasActualDeskStats: Boolean = false,
                         portStateSnapshotInterval: Int = 1000,
                         eGateBankSize: Int = 10,
                         crunchOffsetMinutes: Int = 0,
                         hasEstChox: Boolean = false,
                         useStaffingInput: Boolean = false,
                         exportQueueOrder: List[String] = Queues.exportQueueOrderSansFastTrack,
                         contactEmail: Option[String] = None,
                         outOfHoursContactPhone: Option[String] = None,
                         dayLengthHours: Int = 36,
                         nationalityBasedProcTimes: Map[String, Double] = ProcessingTimes.nationalityProcessingTimes,
                         role: Role,
                         cloneOfPortCode: Option[String] = None,
                         terminalPaxTypeQueueAllocation: Map[TerminalName, Map[PaxType, Seq[(QueueType, Double)]]]
                        ) {
  val terminalSplitQueueTypes: Map[TerminalName, Set[QueueType]] = terminalPaxSplits.map {
    case (terminal, splitRatios) =>
      (terminal, splitRatios.splits.map(_.paxType.queueType).toSet)
  }

  def queueTypeSplitOrder(terminalName: TerminalName): List[QueueName] = Queues.queueOrder.filter { q =>
    terminalSplitQueueTypes.getOrElse(terminalName, Set()).contains(q)
  }

  def paxTypeAndQueueOrder(terminalName: TerminalName): List[PaxTypeAndQueue] = PaxTypesAndQueues.inOrder.filter { q =>
    queues.getOrElse(terminalName, List()).contains(q.queueType)
  }

  def feedPortCode: String = cloneOfPortCode.getOrElse(portCode)

  def nonTransferQueues(terminalName: TerminalName): Seq[QueueName] = queues(terminalName).collect {
    case queueName: String if queueName != Queues.Transfer => queueName
  }
}

object AirportConfig {
  implicit val rw: RW[AirportConfig] = macroRW
}

case class ContactDetails(supportEmail: Option[String], oohPhone: Option[String])

object ContactDetails {
  implicit val rw: RW[ContactDetails] = macroRW
}

case class OutOfHoursStatus(localTime: String, isOoh: Boolean)

object OutOfHoursStatus {
  implicit val rw: RW[OutOfHoursStatus] = macroRW
}

object ArrivalHelper {
  val defaultPax = 0

  def bestPax(flight: Arrival): Int = {
    (flight.ApiPax, flight.ActPax.getOrElse(0), flight.TranPax.getOrElse(0), flight.MaxPax.getOrElse(0)) match {
      case (Some(apiPax), _, _, _)  => apiPax
      case (_, actPaxIsLtE0, _, maxPaxValid) if actPaxIsLtE0 <= 0 && maxPaxValid > 0 => maxPaxValid
      case (_, actPaxIsLt0, _, _) if actPaxIsLt0 <= 0 => defaultPax
      case (_, actPax, tranPax, _) => actPax - tranPax
      case _ => defaultPax
    }
  }

  def bestPaxIncludingTransit(flight: Arrival): Int = {
    (flight.ActPax.getOrElse(0), flight.MaxPax.getOrElse(0)) match {
      case (actPaxIsLtE0, maxPaxValid) if actPaxIsLtE0 <= 0 && maxPaxValid > 0 => maxPaxValid
      case (actPaxIsLt0, _) if actPaxIsLt0 <= 0 => defaultPax
      case (actPax, _) => actPax
      case _ => defaultPax
    }
  }

  def padTo4Digits(voyageNumber: String): String = {
    val prefix = voyageNumber.length match {
      case 4 => ""
      case 3 => "0"
      case 2 => "00"
      case 1 => "000"
      case _ => ""
    }
    prefix + voyageNumber
  }
}

trait HasAirportConfig {
  val airportConfig: AirportConfig
}

object PaxTypesAndQueues {
  val eeaMachineReadableToDesk = PaxTypeAndQueue(PaxTypes.EeaMachineReadable, Queues.EeaDesk)
  val eeaChildToDesk = PaxTypeAndQueue(PaxTypes.EeaBelowEGateAge, Queues.EeaDesk)
  val eeaMachineReadableToEGate = PaxTypeAndQueue(PaxTypes.EeaMachineReadable, Queues.EGate)
  val eeaNonMachineReadableToDesk = PaxTypeAndQueue(PaxTypes.EeaNonMachineReadable, Queues.EeaDesk)
  val b5jsskToDesk = PaxTypeAndQueue(PaxTypes.B5JPlusNational, Queues.EeaDesk)
  val b5jsskChildToDesk = PaxTypeAndQueue(PaxTypes.B5JPlusNationalBelowEGateAge, Queues.EeaDesk)
  val b5jsskToEGate = PaxTypeAndQueue(PaxTypes.B5JPlusNational, Queues.EGate)
  val visaNationalToDesk = PaxTypeAndQueue(PaxTypes.VisaNational, Queues.NonEeaDesk)
  val nonVisaNationalToDesk = PaxTypeAndQueue(PaxTypes.NonVisaNational, Queues.NonEeaDesk)
  val visaNationalToFastTrack = PaxTypeAndQueue(PaxTypes.VisaNational, Queues.FastTrack)
  val transitToTransfer = PaxTypeAndQueue(PaxTypes.Transit, Queues.Transfer)
  val nonVisaNationalToFastTrack = PaxTypeAndQueue(PaxTypes.NonVisaNational, Queues.FastTrack)

  def displayName: Map[PaxTypeAndQueue, QueueType] = Map(
    eeaMachineReadableToEGate -> "eGates",
    eeaMachineReadableToDesk -> "EEA (Machine Readable)",
    eeaChildToDesk -> "EEA child to Desk",
    eeaNonMachineReadableToDesk -> "EEA (Non Machine Readable)",
    b5jsskToDesk -> "B5JSSK to Desk",
    b5jsskChildToDesk -> "B5JSSK child to Desk",
    b5jsskToEGate -> "B5JSSK to eGates",
    visaNationalToDesk -> "Non EEA (Visa)",
    nonVisaNationalToDesk -> "Non EEA (Non Visa)",
    visaNationalToFastTrack -> "Fast Track (Visa)",
    nonVisaNationalToFastTrack -> "Fast Track (Non Visa)",
    transitToTransfer -> "Transfer"
  )

  val inOrder = List(
    eeaMachineReadableToEGate, eeaMachineReadableToDesk, eeaNonMachineReadableToDesk, visaNationalToDesk, nonVisaNationalToDesk, visaNationalToFastTrack, nonVisaNationalToFastTrack)
}

object DqEventCodes {
  val DepartureConfirmed = "DC"
  val CheckIn = "CI"
}

object AirportConfigs {

  import drt.shared.airportconfig._

  val allPorts: List[AirportConfigLike] = List(Ncl, Bfs, Lpl, Lcy, Gla, Ema, Edi, Stn, Man, Ltn, Lhr, Lgw, Bhx, Brs, Test, Test2)
  val testPorts: List[AirportConfigLike] = List(Test, Test2)

  val allPortConfigs: List[AirportConfig] = allPorts.map(_.config)
  val testPortConfigs: List[AirportConfig] = testPorts.map(_.config)

  def portGroups: List[String] = allPortConfigs.filterNot(testPorts.contains).map(_.portCode.toUpperCase).sorted

  val confByPort: Map[String, AirportConfig] = allPortConfigs.map(c => (c.portCode, c)).toMap
}

trait AirportConfigLike {
  val config: AirportConfig
}

object AirportConfigDefaults {
  val defaultSlas: Map[String, Int] = Map(
    EeaDesk -> 20,
    EGate -> 25,
    NonEeaDesk -> 45
  )

  import PaxTypesAndQueues._

  val defaultPaxSplits = SplitRatios(
    SplitSources.TerminalAverage,
    SplitRatio(eeaMachineReadableToDesk, 0.1625),
    SplitRatio(eeaMachineReadableToEGate, 0.4875),
    SplitRatio(eeaNonMachineReadableToDesk, 0.1625),
    SplitRatio(visaNationalToDesk, 0.05),
    SplitRatio(nonVisaNationalToDesk, 0.05)
  )

  val defaultQueueRatios: Map[PaxType, Seq[(QueueType, Double)]] = Map(
    EeaMachineReadable -> List(Queues.EGate -> 0.8, Queues.EeaDesk -> 0.2),
    EeaBelowEGateAge -> List(Queues.EeaDesk -> 1.0),
    EeaNonMachineReadable -> List(Queues.EeaDesk -> 1.0),
    NonVisaNational -> List(Queues.NonEeaDesk -> 1.0),
    VisaNational -> List(Queues.NonEeaDesk -> 1.0),
    B5JPlusNational -> List(Queues.EGate -> 0.6, Queues.EeaDesk -> 0.4),
    B5JPlusNationalBelowEGateAge -> List(Queues.EeaDesk -> 1)
  )

  val defaultProcessingTimes: Map[PaxTypeAndQueue, Double] = Map(
    eeaMachineReadableToDesk -> 20d / 60,
    eeaMachineReadableToEGate -> 35d / 60,
    eeaNonMachineReadableToDesk -> 50d / 60,
    visaNationalToDesk -> 90d / 60,
    nonVisaNationalToDesk -> 78d / 60
  )
}
