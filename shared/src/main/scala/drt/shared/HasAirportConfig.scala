package drt.shared

import drt.shared.PaxTypes._
import drt.shared.Queues.{EGate, EeaDesk, FastTrack, NonEeaDesk, Queue, QueueDesk}
import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios, SplitSources}
import drt.shared.Terminals.Terminal
import ujson.Js.Value
import upickle.Js
import upickle.default.{ReadWriter, macroRW, readwriter}

import scala.collection.immutable.SortedMap

trait ClassNameForToString {
  override val toString: String = getClass.toString.split("\\$").last
}

object Terminals {

  sealed trait Terminal extends ClassNameForToString with Ordered[Terminal] {
    override def compare(that: Terminal): Int = toString.compare(that.toString)
  }

  object Terminal {
    implicit val rw: ReadWriter[Terminal] = macroRW

    def apply(terminalName: String): Terminal = terminalName.toLowerCase match {
      case "t1" => T1
      case "t2" => T2
      case "t3" => T3
      case "t4" => T4
      case "t5" => T5
      case "a1" => A1
      case "a2" => A2
      case "1i" => T1
      case "2i" => T2
      case "3i" => T3
      case "4i" => T4
      case "5i" => T5
      case "ter" => T1
      case "n" => N
      case "s" => S
      case _ => InvalidTerminal
    }
  }

  case object InvalidTerminal extends Terminal {
    override val toString: String = ""
  }

  case object T1 extends Terminal

  case object T2 extends Terminal

  case object T3 extends Terminal

  case object T4 extends Terminal

  case object T5 extends Terminal

  case object A1 extends Terminal

  case object A2 extends Terminal

  case object ACL1I extends Terminal

  case object ACL2I extends Terminal

  case object ACL1D extends Terminal

  case object ACLTER extends Terminal

  case object N extends Terminal

  case object S extends Terminal

}

object Queues {

  sealed trait Queue extends ClassNameForToString with Ordered[Queue] {
    override def compare(that: Queue): Int = toString.compareTo(that.toString)
  }

  object Queue {
    implicit val rw: ReadWriter[Queue] = macroRW

    def apply(queueName: String): Queue = queueName.toLowerCase match {
      case "eeadesk" => EeaDesk
      case "egate" => EGate
      case "noneeadesk" => NonEeaDesk
      case "fasttrack" => FastTrack
      case "transfer" => Transfer
      case "queuedesk" => QueueDesk
      case _ => InvalidQueue
    }
  }

  case object InvalidQueue extends Queue {
    override val toString: String = ""
  }

  case object EeaDesk extends Queue

  case object EGate extends Queue

  case object NonEeaDesk extends Queue

  case object FastTrack extends Queue

  case object Transfer extends Queue

  case object QueueDesk extends Queue

  val queueOrder = List(QueueDesk, EGate, EeaDesk, NonEeaDesk, FastTrack)

  def inOrder(queuesToSort: Seq[Queue]): Seq[Queue] = queueOrder.filter(q => queuesToSort.contains(q))

  val queueDisplayNames: Map[Queue, String] = Map(
    EeaDesk -> "EEA",
    NonEeaDesk -> "Non-EEA",
    EGate -> "e-Gates",
    FastTrack -> "Fast Track",
    Transfer -> "Tx",
    QueueDesk -> "Desk"
  )

  val exportQueueOrderSansFastTrack = List(EeaDesk, NonEeaDesk, EGate)
  val exportQueueOrderWithFastTrack = List(EeaDesk, NonEeaDesk, EGate, FastTrack)
  val exportQueueDisplayNames: Map[Queue, String] = Map(
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

case class PaxTypeAndQueue(passengerType: PaxType, queueType: Queue)

object PaxTypeAndQueue {
  def apply(split: ApiPaxTypeAndQueueCount): PaxTypeAndQueue = PaxTypeAndQueue(split.passengerType, split.queueType)

  implicit val rw: ReadWriter[PaxTypeAndQueue] = macroRW
}

object ProcessingTimes {
  val nationalityProcessingTimes: Map[String, Double] = Map(
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

case class AirportConfig(portCode: PortCode,
                         queuesByTerminal: SortedMap[Terminal, Seq[Queue]],
                         divertedQueues: Map[Queue, Queue] = Map(),
                         slaByQueue: Map[Queue, Int],
                         timeToChoxMillis: Long = 300000L,
                         firstPaxOffMillis: Long = 180000L,
                         defaultWalkTimeMillis: Map[Terminal, Long],
                         terminalPaxSplits: Map[Terminal, SplitRatios],
                         terminalProcessingTimes: Map[Terminal, Map[PaxTypeAndQueue, Double]],
                         minMaxDesksByTerminalQueue: Map[Terminal, Map[Queue, (List[Int], List[Int])]],
                         shiftExamples: Seq[String] = Seq(),
                         fixedPointExamples: Seq[String] = Seq(),
                         hasActualDeskStats: Boolean = false,
                         portStateSnapshotInterval: Int = 1000,
                         eGateBankSize: Int = 10,
                         crunchOffsetMinutes: Int = 0,
                         hasEstChox: Boolean = false,
                         useStaffingInput: Boolean = false,
                         exportQueueOrder: List[Queue] = Queues.exportQueueOrderSansFastTrack,
                         contactEmail: Option[String] = None,
                         outOfHoursContactPhone: Option[String] = None,
                         dayLengthHours: Int = 36,
                         nationalityBasedProcTimes: Map[String, Double] = ProcessingTimes.nationalityProcessingTimes,
                         role: Role,
                         cloneOfPortCode: Option[PortCode] = None,
                         terminalPaxTypeQueueAllocation: Map[Terminal, Map[PaxType, Seq[(Queue, Double)]]],
                         hasTransfer: Boolean = false,
                         maybeCiriumEstThresholdHours: Option[Int] = None,
                         maybeCiriumTaxiThresholdMinutes: Option[Int] = Option(20),
                         feedSources: Seq[FeedSource] = Seq(LiveBaseFeedSource, LiveFeedSource, AclFeedSource, ApiFeedSource),
                         desksByTerminal: Terminal => List[Int],
                         doesDeskFlexing: Boolean = false
                        ) {
  def assertValid(): Unit = {
    queuesByTerminal.values.flatten.toSet.foreach { queue: Queue =>
      assert(slaByQueue.contains(queue), s"Missing sla for $queue")
    }
    queuesByTerminal.foreach { case (terminal, tQueues) =>
      assert(minMaxDesksByTerminalQueue.contains(terminal), s"Missing min/max desks for terminal $terminal")
      tQueues.foreach { tQueue =>
        assert(minMaxDesksByTerminalQueue(terminal).contains(tQueue), s"Missing min/max desks for $tQueue for terminal $terminal")
      }
    }
  }

  val terminals: Iterable[Terminal] = queuesByTerminal.keys

  val terminalSplitQueueTypes: Map[Terminal, Set[Queue]] = terminalPaxSplits.map {
    case (terminal, splitRatios) =>
      (terminal, splitRatios.splits.map(_.paxType.queueType).toSet)
  }

  def queueTypeSplitOrder(terminal: Terminal): List[Queue] = Queues.queueOrder.filter { q =>
    terminalSplitQueueTypes.getOrElse(terminal, Set()).contains(q)
  }

  def feedPortCode: PortCode = cloneOfPortCode.getOrElse(portCode)

  def nonTransferQueues(terminalName: Terminal): Seq[Queue] = queuesByTerminal(terminalName).collect {
    case queue if queue != Queues.Transfer => queue
  }
}

object AirportConfig {
  implicit val rwQueues: ReadWriter[SortedMap[Terminal, Seq[Queue]]] = readwriter[Map[Terminal, Seq[Queue]]].bimap[SortedMap[Terminal, Seq[Queue]]](
    sm => Map[Terminal, Seq[Queue]]() ++ sm,
    m => SortedMap[Terminal, Seq[Queue]]() ++ m
  )

  implicit val rw: ReadWriter[AirportConfig] = macroRW

  def desksByTerminalDefault(minMaxDesksByTerminalQueue: Map[Terminal, Map[Queue, (List[Int], List[Int])]])(terminal: Terminal): List[Int] = minMaxDesksByTerminalQueue.getOrElse(terminal, Map())
    .filterKeys(_ != EGate)
    .map { case (_, (_, max)) => max }
    .reduce[List[Int]] {
      case (max1, max2) => max1.zip(max2).map { case (m1, m2) => m1 + m2 }
    }
}

case class ContactDetails(supportEmail: Option[String], oohPhone: Option[String])

object ContactDetails {
  implicit val rw: ReadWriter[ContactDetails] = macroRW
}

case class OutOfHoursStatus(localTime: String, isOoh: Boolean)

object OutOfHoursStatus {
  implicit val rw: ReadWriter[OutOfHoursStatus] = macroRW
}

object ArrivalHelper {
  val defaultPax = 0

  def bestPax(flight: Arrival): Int = {
    (flight.ApiPax, flight.ActPax.getOrElse(0), flight.TranPax.getOrElse(0), flight.MaxPax.getOrElse(0)) match {
      case (Some(apiPax), _, _, _) => apiPax
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

  def displayName: Map[PaxTypeAndQueue, String] = Map(
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

case class PortCode(iata: String) extends Ordered[PortCode] {
  override def toString: String = iata

  override def compare(that: PortCode): Int = iata.compareTo(that.iata)

  def nonEmpty: Boolean = iata.nonEmpty
}

object PortCode {
  implicit val rw: ReadWriter[PortCode] = macroRW
}

case class CarrierCode(code: String) {
  override def toString: String = code
}

object CarrierCode {
  implicit val rw: ReadWriter[CarrierCode] = macroRW
}

object AirportConfigs {

  import drt.shared.airportconfig._

  val allPorts: List[AirportConfigLike] = List(Ncl, Bfs, Lpl, Lcy, Gla, Ema, Edi, Stn, Man, Ltn, Lhr, Lgw, Bhx, Brs, Test, Test2)
  val testPorts: List[AirportConfigLike] = List(Test, Test2)

  val allPortConfigs: List[AirportConfig] = allPorts.map(_.config)
  val testPortConfigs: List[AirportConfig] = testPorts.map(_.config)

  def portGroups: List[String] = allPortConfigs.filterNot(testPorts.contains).map(_.portCode.toString.toUpperCase).sorted

  val confByPort: Map[PortCode, AirportConfig] = allPortConfigs.map(c => (c.portCode, c)).toMap
}

trait AirportConfigLike {
  val config: AirportConfig
}

object AirportConfigDefaults {
  val defaultSlas: Map[Queue, Int] = Map(
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

  val defaultQueueRatios: Map[PaxType, Seq[(Queue, Double)]] = Map(
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
