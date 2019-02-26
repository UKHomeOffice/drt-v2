package drt.shared

import drt.shared.FlightsApi.{QueueName, TerminalName}
import drt.shared.PaxTypes._
import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios, SplitSources}

//import scala.collection.immutable.Seq


object Queues {
  val EeaDesk = "eeaDesk"
  val EGate = "eGate"
  val NonEeaDesk = "nonEeaDesk"
  val FastTrack = "fastTrack"
  val Transfer = "transfer"
  val QueueDesk = "queueDesk"

  val queueOrder = List(QueueDesk, EeaDesk, EGate, NonEeaDesk, FastTrack)

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
    case _ => UndefinedPaxType
  }
}

object PaxTypes {

  case object EeaNonMachineReadable extends PaxType

  case object Transit extends PaxType

  case object VisaNational extends PaxType

  case object EeaMachineReadable extends PaxType

  case object NonVisaNational extends PaxType

  case object B5JPlusNational extends PaxType

  case object UndefinedPaxType extends PaxType

}

case class PaxTypeAndQueue(passengerType: PaxType, queueType: String)

object PaxTypeAndQueue {
  def apply(split: ApiPaxTypeAndQueueCount): PaxTypeAndQueue = PaxTypeAndQueue(split.passengerType, split.queueType)
}

object ProcessingTimes {
  val nationalityProcessingTimes = Map(
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


case class AirportConfig(
                          portCode: String = "n/a",
                          queues: Map[TerminalName, Seq[QueueName]],
                          divertedQueues: Map[QueueName, QueueName] = Map(),
                          slaByQueue: Map[String, Int],
                          terminalNames: Seq[TerminalName],
                          timeToChoxMillis: Long = 300000L,
                          firstPaxOffMillis: Long = 180000L,
                          defaultWalkTimeMillis: Map[TerminalName, Long],
                          defaultPaxSplits: SplitRatios,
                          defaultProcessingTimes: Map[TerminalName, Map[PaxTypeAndQueue, Double]],
                          minMaxDesksByTerminalQueue: Map[TerminalName, Map[QueueName, (List[Int], List[Int])]],
                          shiftExamples: Seq[String] = Seq(),
                          queueOrder: List[PaxTypeAndQueue] = PaxTypesAndQueues.inOrderSansFastTrack,
                          fixedPointExamples: Seq[String] = Seq(),
                          hasActualDeskStats: Boolean = false,
                          portStateSnapshotInterval: Int = 1000,
                          eGateBankSize: Int = 10,
                          crunchOffsetMinutes: Int = 0,
                          hasEstChox: Boolean = false,
                          useStaffingInput: Boolean = false,
                          exportQueueOrder: List[String] = Queues.exportQueueOrderSansFastTrack,
                          contactEmail: Option[String] = None,
                          dayLengthHours: Int = 36,
                          nationalityBasedProcTimes: Map[String, Double] = ProcessingTimes.nationalityProcessingTimes,
                          role: Role,
                          cloneOfPortCode: Option[String] = None
                        ) extends AirportConfigLike {

  def feedPortCode: String = cloneOfPortCode.getOrElse(portCode)

  def nonTransferQueues(terminalName: TerminalName): Seq[QueueName] = queues(terminalName).collect {
    case queueName: String if queueName != Queues.Transfer => queueName
  }
}

object ArrivalHelper {
  val defaultPax = 0

  def bestPax(flight: Arrival): Int = {
    (flight.ActPax.getOrElse(0), flight.TranPax.getOrElse(0), flight.LastKnownPax, flight.MaxPax.getOrElse(0)) match {
      case (actPaxIsLtE0, _, None, maxPaxValid) if actPaxIsLtE0 <= 0 && maxPaxValid > 0 => maxPaxValid
      case (actPaxIsLt0, _, Some(lastPax), _) if actPaxIsLt0 <= 0 => lastPax
      case (actPaxIsLt0, _, None, _) if actPaxIsLt0 <= 0 => defaultPax
      case (actPax, tranPax, _, _) => actPax - tranPax
      case _ => defaultPax
    }
  }

  def bestPaxIncludingTransit(flight: Arrival): Int = {
    (flight.ActPax.getOrElse(0), flight.LastKnownPax, flight.MaxPax.getOrElse(0)) match {
      case (actPaxIsLtE0, None, maxPaxValid) if actPaxIsLtE0 <= 0 && maxPaxValid > 0 => maxPaxValid
      case (actPaxIsLt0, Some(lastPax), _) if actPaxIsLt0 <= 0 => lastPax
      case (actPaxIsLt0, None, _) if actPaxIsLt0 <= 0 => defaultPax
      case (actPax, _, _) => actPax
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

trait AirportConfigLike {
  def portCode: String

  def queues: Map[TerminalName, Seq[QueueName]]

  def slaByQueue: Map[String, Int]

  def terminalNames: Seq[TerminalName]
}

object PaxTypesAndQueues {
  val eeaMachineReadableToDesk = PaxTypeAndQueue(PaxTypes.EeaMachineReadable, Queues.EeaDesk)
  val eeaMachineReadableToEGate = PaxTypeAndQueue(PaxTypes.EeaMachineReadable, Queues.EGate)
  val eeaNonMachineReadableToDesk = PaxTypeAndQueue(PaxTypes.EeaNonMachineReadable, Queues.EeaDesk)
  val visaNationalToDesk = PaxTypeAndQueue(PaxTypes.VisaNational, Queues.NonEeaDesk)
  val nonVisaNationalToDesk = PaxTypeAndQueue(PaxTypes.NonVisaNational, Queues.NonEeaDesk)
  val visaNationalToFastTrack = PaxTypeAndQueue(PaxTypes.VisaNational, Queues.FastTrack)
  val transitToTransfer = PaxTypeAndQueue(PaxTypes.Transit, Queues.Transfer)
  val nonVisaNationalToFastTrack = PaxTypeAndQueue(PaxTypes.NonVisaNational, Queues.FastTrack)

  def displayName = Map(
    eeaMachineReadableToEGate -> "eGates",
    eeaMachineReadableToDesk -> "EEA (Machine Readable)",
    eeaNonMachineReadableToDesk -> "EEA (Non Machine Readable)",
    visaNationalToDesk -> "Non EEA (Visa)",
    nonVisaNationalToDesk -> "Non EEA (Non Visa)",
    visaNationalToFastTrack -> "Fast Track (Visa)",
    nonVisaNationalToFastTrack -> "Fast Track (Non Visa)",
    transitToTransfer -> "Transfer"
  )

  /*todo - we should move the usages of this to airportConfig */
  val inOrderSansFastTrack = List(
    eeaMachineReadableToEGate, eeaMachineReadableToDesk, eeaNonMachineReadableToDesk, visaNationalToDesk, nonVisaNationalToDesk)

  val inOrderWithFastTrack = List(
    eeaMachineReadableToEGate, eeaMachineReadableToDesk, eeaNonMachineReadableToDesk, visaNationalToDesk, nonVisaNationalToDesk, visaNationalToFastTrack, nonVisaNationalToFastTrack)
}

object DqEventCodes {
  val DepartureConfirmed = "DC"
  val CheckIn = "CI"
}

object AirportConfigs {

  import Queues._

  val defaultSlas: Map[String, Int] = Map(
    EeaDesk -> 20,
    EGate -> 25,
    NonEeaDesk -> 45
  )

  import PaxTypesAndQueues._

  val defaultPaxSplits = SplitRatios(
    SplitSources.TerminalAverage,
    SplitRatio(eeaMachineReadableToDesk, 0.4875),
    SplitRatio(eeaMachineReadableToEGate, 0.1625),
    SplitRatio(eeaNonMachineReadableToDesk, 0.1625),
    SplitRatio(visaNationalToDesk, 0.05),
    SplitRatio(nonVisaNationalToDesk, 0.05)
  )

  val defaultProcessingTimes = Map(
    eeaMachineReadableToDesk -> 20d / 60,
    eeaMachineReadableToEGate -> 35d / 60,
    eeaNonMachineReadableToDesk -> 50d / 60,
    visaNationalToDesk -> 90d / 60,
    nonVisaNationalToDesk -> 78d / 60
  )

  val edi = AirportConfig(
    portCode = "EDI",
    queues = Map(
      "A1" -> Seq(EeaDesk, EGate, NonEeaDesk),
      "A2" -> Seq(EeaDesk, EGate, NonEeaDesk)
    ),
    slaByQueue = defaultSlas,
    terminalNames = Seq("A1", "A2"),
    defaultWalkTimeMillis = Map("A1" -> 180000L, "A2" -> 120000L),
    defaultPaxSplits = defaultPaxSplits,
    defaultProcessingTimes = Map(
      "A1" -> Map(
        eeaMachineReadableToDesk -> 16d / 60,
        eeaMachineReadableToEGate -> 25d / 60,
        eeaNonMachineReadableToDesk -> 50d / 60,
        visaNationalToDesk -> 75d / 60,
        nonVisaNationalToDesk -> 64d / 60
      ),
      "A2" -> Map(
        eeaMachineReadableToDesk -> 16d / 60,
        eeaMachineReadableToEGate -> 25d / 60,
        eeaNonMachineReadableToDesk -> 50d / 60,
        visaNationalToDesk -> 75d / 60,
        nonVisaNationalToDesk -> 64d / 60
      )),
    minMaxDesksByTerminalQueue = Map(
      "A1" -> Map(
        Queues.EGate -> (List(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)),
        Queues.EeaDesk -> (List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9)),
        Queues.NonEeaDesk -> (List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(2, 2, 2, 2, 2, 2, 6, 6, 3, 3, 3, 3, 4, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3))
      ),
      "A2" -> Map(
        Queues.EGate -> (List(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)),
        Queues.EeaDesk -> (List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6)),
        Queues.NonEeaDesk -> (List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3))
      )
    ),
    shiftExamples = Seq(
      "Midnight shift, A1, {date}, 00:00, 00:59, 10",
      "Night shift, A1, {date}, 01:00, 06:59, 4",
      "Morning shift, A1, {date}, 07:00, 13:59, 15",
      "Afternoon shift, A1, {date}, 14:00, 16:59, 10",
      "Evening shift, A1, {date}, 17:00, 23:59, 17"
    ),
    role = EDIAccess
  )
  val lgw = AirportConfig(
    portCode = "LGW",
    queues = Map(
      "N" -> Seq(EeaDesk, EGate, NonEeaDesk),
      "S" -> Seq(EeaDesk, EGate, NonEeaDesk)
    ),
    slaByQueue = Map(
      EeaDesk -> 25,
      EGate -> 10,
      NonEeaDesk -> 45
    ),
    hasEstChox = true,
    terminalNames = Seq("N", "S"),
    defaultWalkTimeMillis = Map("N" -> 180000L, "S" -> 180000L),
    defaultPaxSplits = SplitRatios(
      SplitSources.TerminalAverage,
      SplitRatio(eeaMachineReadableToDesk, 0.85 * 0.55),
      SplitRatio(eeaMachineReadableToEGate, 0.85 * 0.45),
      SplitRatio(eeaNonMachineReadableToDesk, 0d),
      SplitRatio(visaNationalToDesk, 0.06),
      SplitRatio(nonVisaNationalToDesk, 0.09)
    ),
    defaultProcessingTimes = Map(
      "N" -> Map(
        eeaMachineReadableToDesk -> 23d / 60,
        eeaMachineReadableToEGate -> 30d / 60,
        eeaNonMachineReadableToDesk -> 55d / 60,
        visaNationalToDesk -> 92d / 60,
        nonVisaNationalToDesk -> 77d / 60
      ),
      "S" -> Map(
        eeaMachineReadableToDesk -> 23d / 60,
        eeaMachineReadableToEGate -> 30d / 60,
        eeaNonMachineReadableToDesk -> 42d / 60,
        visaNationalToDesk -> 99d / 60,
        nonVisaNationalToDesk -> 81d / 60
      )),
    minMaxDesksByTerminalQueue = Map(
      "N" -> Map(
        Queues.EGate -> (List(0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3)),
        Queues.EeaDesk -> (List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13)),
        Queues.NonEeaDesk -> (List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15))
      ),
      "S" -> Map(
        Queues.EGate -> (List(0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3)),
        Queues.EeaDesk -> (List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(15, 15, 15, 15, 15, 15, 13, 10, 10, 10, 10, 10, 10, 10, 10, 13, 13, 13, 13, 13, 13, 13, 13, 13)),
        Queues.NonEeaDesk -> (List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(10, 10, 10, 10, 10, 10, 12, 15, 15, 15, 15, 15, 15, 15, 15, 13, 13, 13, 13, 13, 13, 13, 13, 13))
      )
    ),
    shiftExamples = Seq(
      "Midnight shift, N, {date}, 00:00, 00:59, 10",
      "Night shift, N, {date}, 01:00, 06:59, 4",
      "Morning shift, N, {date}, 07:00, 13:59, 15",
      "Afternoon shift, N, {date}, 14:00, 16:59, 10",
      "Evening shift, N, {date}, 17:00, 23:59, 17"
    ),
    role = LGWAccess
  )
  val stn = AirportConfig(
    portCode = "STN",
    queues = Map(
      "T1" -> Seq(EeaDesk, EGate, NonEeaDesk)
    ),
    slaByQueue = Map(EeaDesk -> 25, EGate -> 5, NonEeaDesk -> 45),
    terminalNames = Seq("T1"),
    crunchOffsetMinutes = 240,
    dayLengthHours = 36,
    defaultWalkTimeMillis = Map("T1" -> 600000L),
    defaultPaxSplits = SplitRatios(
      SplitSources.TerminalAverage,
      SplitRatio(eeaMachineReadableToDesk, 0.7425),
      SplitRatio(eeaMachineReadableToEGate, 0.2475),
      SplitRatio(eeaNonMachineReadableToDesk, 0.0),
      SplitRatio(visaNationalToDesk, 0.0),
      SplitRatio(nonVisaNationalToDesk, 0.01)
    ),
    defaultProcessingTimes = Map("T1" -> Map(
      eeaMachineReadableToDesk -> 20d / 60,
      eeaMachineReadableToEGate -> 35d / 60,
      eeaNonMachineReadableToDesk -> 50d / 60,
      visaNationalToDesk -> 90d / 60,
      nonVisaNationalToDesk -> 78d / 60
    )),
    minMaxDesksByTerminalQueue = Map(
      "T1" -> Map(
        Queues.EGate -> (List(1, 1, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(3, 3, 1, 1, 1, 1, 1, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3)),
        Queues.EeaDesk -> (List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13)),
        Queues.NonEeaDesk -> (List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8))
      )
    ),
    shiftExamples = Seq(
      "Alpha, T1, {date}, 07:00, 15:48, 0",
      "Bravo, T1, {date}, 07:45, 16:33, 0",
      "Charlie, T1, {date}, 15:00, 23:48, 0",
      "Delta, T1, {date}, 16:00, 00:48, 0",
      "Night, T1, {date}, 22:36, 07:24, 0"
    ),
    fixedPointExamples = Seq("Roving Officer, 00:00, 23:59, 1",
      "Referral Officer, 00:00, 23:59, 1",
      "Forgery Officer, 00:00, 23:59, 1"),
    role = STNAccess
  )
  val man = AirportConfig(
    portCode = "MAN",
    queues = Map(
      "T1" -> Seq(EeaDesk, EGate, NonEeaDesk),
      "T2" -> Seq(EeaDesk, EGate, NonEeaDesk),
      "T3" -> Seq(EeaDesk, EGate, NonEeaDesk)
    ),
    slaByQueue = Map(EeaDesk -> 25, EGate -> 10, NonEeaDesk -> 45),
    terminalNames = Seq("T1", "T2", "T3"),
    defaultWalkTimeMillis = Map("T1" -> 180000L, "T2" -> 180000L, "T3" -> 60000L),
    defaultPaxSplits = defaultPaxSplits,
    defaultProcessingTimes = Map("T1" -> defaultProcessingTimes, "T2" -> defaultProcessingTimes, "T3" -> defaultProcessingTimes),
    minMaxDesksByTerminalQueue = Map(
      "T1" -> Map(
        Queues.EGate -> (List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2)),
        Queues.EeaDesk -> (List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6)),
        Queues.NonEeaDesk -> (List(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), List(5, 5, 5, 5, 5, 5, 7, 7, 7, 7, 5, 6, 6, 6, 6, 6, 5, 5, 5, 6, 5, 5, 5, 5))
      ),
      "T2" -> Map(
        Queues.EGate -> (List(1, 1, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)),
        Queues.EeaDesk -> (List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(8, 8, 8, 8, 8, 5, 5, 5, 5, 5, 5, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8)),
        Queues.NonEeaDesk -> (List(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), List(3, 3, 3, 3, 3, 8, 8, 8, 8, 8, 8, 3, 3, 3, 3, 3, 6, 6, 6, 6, 3, 3, 3, 3))
      ),
      "T3" -> Map(
        Queues.EGate -> (List(1, 1, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)),
        Queues.EeaDesk -> (List(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), List(6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6)),
        Queues.NonEeaDesk -> (List(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), List(3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3))
      )
    ),
    shiftExamples = Seq(
      "Midnight shift, T1, {date}, 00:00, 00:59, 25",
      "Night shift, T1, {date}, 01:00, 06:59, 10",
      "Morning shift, T1, {date}, 07:00, 13:59, 30",
      "Afternoon shift, T1, {date}, 14:00, 16:59, 18",
      "Evening shift, T1, {date}, 17:00, 23:59, 22"
    ),
    role = MANAccess
  )
  private val lhrDefaultTerminalProcessingTimes = Map(
    eeaMachineReadableToDesk -> 25d / 60,
    eeaMachineReadableToEGate -> 25d / 60,
    eeaNonMachineReadableToDesk -> 55d / 60,
    visaNationalToDesk -> 96d / 60,
    nonVisaNationalToDesk -> 78d / 60,
    nonVisaNationalToFastTrack -> 78d / 60,
    visaNationalToFastTrack -> 78d / 60,
    transitToTransfer -> 0d
  )
  val lhr = AirportConfig(
    portCode = "LHR",
    queues = Map(
      "T2" -> Seq(EeaDesk, EGate, NonEeaDesk, FastTrack, Transfer),
      "T3" -> Seq(EeaDesk, EGate, NonEeaDesk, FastTrack, Transfer),
      "T4" -> Seq(EeaDesk, EGate, NonEeaDesk, FastTrack, Transfer),
      "T5" -> Seq(EeaDesk, EGate, NonEeaDesk, FastTrack, Transfer)
    ),
    slaByQueue = Map(EeaDesk -> 25, EGate -> 15, NonEeaDesk -> 45, FastTrack -> 15),
    crunchOffsetMinutes = 120,
    dayLengthHours = 36,
    terminalNames = Seq("T2", "T3", "T4", "T5"),
    defaultWalkTimeMillis = Map("T2" -> 900000L, "T3" -> 660000L, "T4" -> 900000L, "T5" -> 660000L),
    defaultPaxSplits = SplitRatios(
      SplitSources.TerminalAverage,
      SplitRatio(eeaMachineReadableToDesk, 0.64 * 0.57),
      SplitRatio(eeaMachineReadableToEGate, 0.64 * 0.43),
      SplitRatio(eeaNonMachineReadableToDesk, 0),
      SplitRatio(visaNationalToDesk, 0.08 * 0.95),
      SplitRatio(visaNationalToFastTrack, 0.08 * 0.05),
      SplitRatio(nonVisaNationalToDesk, 0.28 * 0.95),
      SplitRatio(nonVisaNationalToFastTrack, 0.28 * 0.05)
    ),
    defaultProcessingTimes = Map(
      "T2" -> lhrDefaultTerminalProcessingTimes,
      "T3" -> lhrDefaultTerminalProcessingTimes,
      "T4" -> lhrDefaultTerminalProcessingTimes,
      "T5" -> lhrDefaultTerminalProcessingTimes
    ),
    minMaxDesksByTerminalQueue = Map(
      "T2" -> Map(
        Queues.EGate -> (List(0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(1, 1, 1, 1, 1, 2, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3)),
        Queues.EeaDesk -> (List(0, 0, 0, 0, 0, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2), List(9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9)),
        Queues.FastTrack -> (List(0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0), List(6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6)),
        Queues.NonEeaDesk -> (List(0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20))
      ),
      "T3" -> Map(
        Queues.EGate -> (List(0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(1, 1, 1, 1, 1, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3)),
        Queues.EeaDesk -> (List(0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16)),
        Queues.FastTrack -> (List(0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7)),
        Queues.NonEeaDesk -> (List(0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23))
      ),
      "T4" -> Map(
        Queues.EGate -> (List(0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2)),
        Queues.EeaDesk -> (List(0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8)),
        Queues.FastTrack -> (List(0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4)),
        Queues.NonEeaDesk -> (List(0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27))
      ),
      "T5" -> Map(
        Queues.EGate -> (List(0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5)),
        Queues.EeaDesk -> (List(0, 0, 0, 0, 0, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2), List(6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6)),
        Queues.FastTrack -> (List(0, 0, 0, 0, 0, 2, 4, 4, 2, 2, 2, 2, 2, 2, 2, 3, 2, 2, 2, 2, 2, 1, 1, 0), List(0, 0, 0, 0, 0, 2, 4, 4, 2, 2, 2, 2, 2, 2, 2, 3, 2, 2, 2, 2, 2, 1, 1, 0)),
        Queues.NonEeaDesk -> (List(0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20))
      )
    ),
    shiftExamples = Seq(
      "Midnight shift, T2, {date}, 00:00, 00:59, 25",
      "Night shift, T2, {date}, 01:00, 06:59, 10",
      "Morning shift, T2, {date}, 07:00, 13:59, 30",
      "Afternoon shift, T2, {date}, 14:00, 16:59, 18",
      "Evening shift, T2, {date}, 17:00, 23:59, 22"
    ),
    queueOrder = PaxTypesAndQueues.inOrderWithFastTrack,
    hasActualDeskStats = true,
    portStateSnapshotInterval = 250,
    hasEstChox = true,
    exportQueueOrder = Queues.exportQueueOrderWithFastTrack,
    role = LHRAccess
  )
  val ltn = AirportConfig(
    portCode = "LTN",
    queues = Map(
      "T1" -> Seq(EeaDesk, EGate, NonEeaDesk)
    ),
    slaByQueue = defaultSlas,
    terminalNames = Seq("T1"),
    defaultWalkTimeMillis = Map("T1" -> 300000L),
    defaultPaxSplits = defaultPaxSplits,
    defaultProcessingTimes = Map("T1" -> defaultProcessingTimes),
    minMaxDesksByTerminalQueue = Map(
      "T1" -> Map(
        Queues.EGate -> (List.fill(24)(1), List.fill(24)(2)),
        Queues.EeaDesk -> (List.fill(24)(1), List(9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9)),
        Queues.NonEeaDesk -> (List.fill(24)(1), List(5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5))
      )
    ),
    role = LTNAccess
  )
  val ema = AirportConfig(
    portCode = "EMA",
    queues = Map(
      "T1" -> Seq(Queues.QueueDesk, Queues.EGate)
    ),
    divertedQueues = Map(
      Queues.NonEeaDesk -> Queues.QueueDesk,
      Queues.EeaDesk -> Queues.QueueDesk
    ),
    slaByQueue = Map(
      Queues.QueueDesk -> 20,
      Queues.EGate -> 25
    ),
    terminalNames = Seq("T1"),
    defaultWalkTimeMillis = Map("T1" -> 780000L),
    defaultPaxSplits = SplitRatios(
      SplitSources.TerminalAverage,
      SplitRatio(eeaMachineReadableToDesk, 0.99 * 0.8),
      SplitRatio(eeaMachineReadableToEGate, 0.99 * 0.2),
      SplitRatio(eeaNonMachineReadableToDesk, 0),
      SplitRatio(visaNationalToDesk, 0.0),
      SplitRatio(nonVisaNationalToDesk, 0.01)
    ),
    defaultProcessingTimes = Map("T1" -> Map(
      eeaMachineReadableToDesk -> 20d / 60,
      eeaMachineReadableToEGate -> 30d / 60,
      eeaNonMachineReadableToDesk -> 50d / 60,
      visaNationalToDesk -> 100d / 60,
      nonVisaNationalToDesk -> 80d / 60
    )),
    minMaxDesksByTerminalQueue = Map(
      "T1" -> Map(
        Queues.EGate -> (List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)),
        Queues.QueueDesk -> (List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5))
      )
    ),
    role = EMAAccess,
    // This is deliberately high (10000) to cope with restoring previous snapshot bug where the time between snapshots
    // was going beyond the message threshold.
    // A neater fix would be to produce the missing snapshots retrospectively, but that would be quite a big job for a
    // minor gain
    portStateSnapshotInterval = 10000
  )

  val brs = AirportConfig(
    portCode = "BRS",
    queues = Map(
      "T1" -> Seq(Queues.QueueDesk, Queues.EGate)
    ),
    divertedQueues = Map(
      Queues.NonEeaDesk -> Queues.QueueDesk,
      Queues.EeaDesk -> Queues.QueueDesk
    ),
    slaByQueue = Map(
      Queues.QueueDesk -> 20,
      Queues.EGate -> 25
    ),
    terminalNames = Seq("T1"),
    defaultWalkTimeMillis = Map("T1" -> 780000L),
    defaultPaxSplits = SplitRatios(
      SplitSources.TerminalAverage,
      SplitRatio(eeaMachineReadableToDesk, 0.99 * 0.8),
      SplitRatio(eeaMachineReadableToEGate, 0.99 * 0.2),
      SplitRatio(eeaNonMachineReadableToDesk, 0),
      SplitRatio(visaNationalToDesk, 0.0),
      SplitRatio(nonVisaNationalToDesk, 0.01)
    ),
    defaultProcessingTimes = Map("T1" -> Map(
      eeaMachineReadableToDesk -> 20d / 60,
      eeaMachineReadableToEGate -> 30d / 60,
      eeaNonMachineReadableToDesk -> 50d / 60,
      visaNationalToDesk -> 100d / 60,
      nonVisaNationalToDesk -> 80d / 60
    )),
    minMaxDesksByTerminalQueue = Map(
      "T1" -> Map(
        Queues.EGate -> (List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)),
        Queues.QueueDesk -> (List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5))
      )
    ),
    role = BRSAccess
  )

  val bhx = AirportConfig(
    portCode = "BHX",
    queues = Map(
      "T1" -> Seq(EeaDesk, EGate, NonEeaDesk),
      "T2" -> Seq(EeaDesk, NonEeaDesk)
    ),
    slaByQueue = defaultSlas,
    terminalNames = Seq("T1", "T2"),
    defaultWalkTimeMillis = Map("T1" -> 240000L, "T2" -> 60000L),
    defaultPaxSplits = SplitRatios(
      SplitSources.TerminalAverage,
      SplitRatio(eeaMachineReadableToDesk, 0.92 * 0.7),
      SplitRatio(eeaMachineReadableToEGate, 0.92 * 0.3),
      SplitRatio(eeaNonMachineReadableToDesk, 0),
      SplitRatio(visaNationalToDesk, 0.04),
      SplitRatio(nonVisaNationalToDesk, 0.04)
    ),
    defaultProcessingTimes = Map("T1" -> Map(
      eeaMachineReadableToDesk -> 16d / 60,
      eeaMachineReadableToEGate -> 20d / 60,
      eeaNonMachineReadableToDesk -> 50d / 60,
      visaNationalToDesk -> 93d / 60,
      nonVisaNationalToDesk -> 83d / 60
    ), "T2" -> Map(
      eeaMachineReadableToDesk -> 16d / 60,
      //eeaMachineReadableToEGate -> 30d / 60,
      eeaNonMachineReadableToDesk -> 50d / 60,
      visaNationalToDesk -> 93d / 60,
      nonVisaNationalToDesk -> 83d / 60
    )),
    minMaxDesksByTerminalQueue = Map(
      "T1" -> Map(
        Queues.EGate -> (List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1),
          List(2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2)),
        Queues.EeaDesk -> (List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1),
          List(6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6)),
        Queues.NonEeaDesk -> (List(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
          List(8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8))
      ),
      "T2" -> Map(
        Queues.EeaDesk -> (List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1),
          List(4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4)),
        Queues.NonEeaDesk -> (List(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
          List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1))
      )
    ),
    role = BHXAccess
  )

  val test = AirportConfig(
    portCode = "TEST",
    queues = Map(
      "T1" -> Seq(EeaDesk, EGate, NonEeaDesk)
    ),
    slaByQueue = Map(EeaDesk -> 25, EGate -> 5, NonEeaDesk -> 45),
    terminalNames = Seq("T1"),
    crunchOffsetMinutes = 240,
    dayLengthHours = 36,
    defaultWalkTimeMillis = Map("T1" -> 600000L),
    defaultPaxSplits = SplitRatios(
      SplitSources.TerminalAverage,
      SplitRatio(eeaMachineReadableToDesk, 0.7425),
      SplitRatio(eeaMachineReadableToEGate, 0.2475),
      SplitRatio(eeaNonMachineReadableToDesk, 0.0),
      SplitRatio(visaNationalToDesk, 0.0),
      SplitRatio(nonVisaNationalToDesk, 0.01)
    ),
    defaultProcessingTimes = Map("T1" -> Map(
      eeaMachineReadableToDesk -> 20d / 60,
      eeaMachineReadableToEGate -> 35d / 60,
      eeaNonMachineReadableToDesk -> 50d / 60,
      visaNationalToDesk -> 90d / 60,
      nonVisaNationalToDesk -> 78d / 60
    )),
    minMaxDesksByTerminalQueue = Map(
      "T1" -> Map(
        Queues.EGate -> (List(1, 1, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(3, 3, 1, 1, 1, 1, 1, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3)),
        Queues.EeaDesk -> (List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13)),
        Queues.NonEeaDesk -> (List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8))
      )
    ),
    role = TestAccess
  )

  val test2 = AirportConfig(
    portCode = "TEST2",
    queues = Map(
      "T1" -> Seq(EeaDesk, EGate, NonEeaDesk),
      "T2" -> Seq(EeaDesk, EGate, NonEeaDesk, FastTrack)
    ),
    slaByQueue = Map(EeaDesk -> 25, EGate -> 5, NonEeaDesk -> 45),
    terminalNames = Seq("T1", "T2"),
    crunchOffsetMinutes = 240,
    dayLengthHours = 36,
    defaultWalkTimeMillis = Map(
      "T1" -> 600000L,
      "T2" -> 600000L
    ),
    defaultPaxSplits = SplitRatios(
      SplitSources.TerminalAverage,
      SplitRatio(eeaMachineReadableToDesk, 0.7425),
      SplitRatio(eeaMachineReadableToEGate, 0.2475),
      SplitRatio(eeaNonMachineReadableToDesk, 0.0),
      SplitRatio(visaNationalToDesk, 0.0),
      SplitRatio(nonVisaNationalToDesk, 0.01)
    ),
    defaultProcessingTimes = Map("T1" -> Map(
      eeaMachineReadableToDesk -> 20d / 60,
      eeaMachineReadableToEGate -> 35d / 60,
      eeaNonMachineReadableToDesk -> 50d / 60,
      visaNationalToDesk -> 90d / 60,
      nonVisaNationalToDesk -> 78d / 60
    )),
    minMaxDesksByTerminalQueue = Map(
      "T1" -> Map(
        Queues.EGate -> (List(1, 1, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(3, 3, 1, 1, 1, 1, 1, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3)),
        Queues.EeaDesk -> (List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13)),
        Queues.NonEeaDesk -> (List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8))
      ),
      "T2" -> Map(
        Queues.EGate -> (List(1, 1, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(3, 3, 1, 1, 1, 1, 1, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3)),
        Queues.EeaDesk -> (List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13)),
        Queues.NonEeaDesk -> (List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8))
      )
    ),
    role = Test2Access
  )

  val nationalityProcessingTimesHalved: Map[String, Double] = ProcessingTimes.nationalityProcessingTimes.mapValues(_ / 2)
  val halvedLHRProcessingTimes: Map[TerminalName, Map[PaxTypeAndQueue, Double]] = lhr.defaultProcessingTimes.mapValues(_.mapValues(_ / 2))

  //copies of LHR for AB testing nationality based processing times and halved processing times
  val lhr_ppt_halved: AirportConfig = lhr.copy(portCode = "LHR_PPT_HALVED", defaultProcessingTimes = halvedLHRProcessingTimes, cloneOfPortCode = Option("LHR"), role = TestAccess)
  //Nationality based proc times turned on using feature flag
  val lhr_nbp: AirportConfig = lhr.copy(portCode = "LHR_NBP", cloneOfPortCode = Option("LHR"), role = TestAccess)
  val lhr_nbp_halved: AirportConfig = lhr_ppt_halved.copy(portCode = "LHR_NBP_HALVED", nationalityBasedProcTimes = nationalityProcessingTimesHalved, cloneOfPortCode = Option("LHR"), role = TestAccess) //use halved default times and halved nationality based times

  val allPorts: List[AirportConfig] = ema :: edi :: stn :: man :: ltn :: lhr :: lhr_nbp :: lhr_nbp_halved :: lhr_ppt_halved :: lgw :: bhx :: brs :: test :: test2 :: Nil
  val testPorts: List[AirportConfig] = test :: test2 :: lhr_nbp :: lhr_nbp_halved :: lhr_ppt_halved :: Nil

  def portGroups: List[String] = allPorts.filterNot(testPorts.contains).map(_.portCode.toUpperCase).sorted

  val confByPort: Map[String, AirportConfig] = allPorts.map(c => (c.portCode, c)).toMap
}
