package drt.shared

import drt.shared.FlightsApi.{QueueName, TerminalName}
import drt.shared.PassengerSplits.{SplitsPaxTypeAndQueueCount, VoyagePaxSplits}
import drt.shared.PaxTypes._
import drt.shared.PassengerSplits.{SplitsPaxTypeAndQueueCount, VoyagePaxSplits}
import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios, SplitSources}

import scala.collection.immutable.Seq


object Queues {
  val EeaDesk = "eeaDesk"
  val EGate = "eGate"
  val NonEeaDesk = "nonEeaDesk"
  val FastTrack = "fastTrack"
  val Transfer = "transfer"
}

sealed trait PaxType {
  def name = getClass.getSimpleName
}

object PaxType {
  def apply(paxTypeString: String) = paxTypeString match {
    case "EeaNonMachineReadable$" => EeaNonMachineReadable
    case "Transit$" => Transit
    case "VisaNational$" => VisaNational
    case "EeaMachineReadable$" => EeaMachineReadable
    case "NonVisaNational$" => NonVisaNational
    case _ => UndefinedPaxType
  }
}

object PaxTypes {

  case object EeaNonMachineReadable extends PaxType

  case object Transit extends PaxType

  case object VisaNational extends PaxType

  case object EeaMachineReadable extends PaxType

  case object NonVisaNational extends PaxType

  case object UndefinedPaxType extends PaxType

}

case class PaxTypeAndQueue(passengerType: PaxType, queueType: String)

object PaxTypeAndQueue {
  def apply(split: SplitsPaxTypeAndQueueCount): PaxTypeAndQueue = PaxTypeAndQueue(split.passengerType, split.queueType)
}


case class AirportConfig(
                          portCode: String = "n/a",
                          queues: Map[TerminalName, Seq[QueueName]],
                          slaByQueue: Map[String, Int],
                          terminalNames: Seq[TerminalName],
                          timeToChoxMillis: Long = 120000L,
                          firstPaxOffMillis: Long = 120000L,
                          defaultWalkTimeMillis: Long = 300000L,
                          defaultPaxSplits: SplitRatios,
                          defaultProcessingTimes: Map[TerminalName, Map[PaxTypeAndQueue, Double]],
                          minMaxDesksByTerminalQueue: Map[TerminalName, Map[QueueName, (List[Int], List[Int])]],
                          shiftExamples: Seq[String] = Seq(),
                          queueOrder: List[PaxTypeAndQueue] = PaxTypesAndQueues.inOrderSansFastTrack,
                          fixedPointExamples: Seq[String] = Seq(),
                          hasActualDeskStats: Boolean = false
                        ) extends AirportConfigLike {

}

object BestPax {

  def apply(portCode: String) = portCode.toUpperCase match {
    case "LHR" => lhrBestPax
    case _ => bestPax
  }

  def bestPax = (flight: Arrival) => {
    flight match {
      case f if f.ActPax > 0 =>
        f.ActPax
      case f if f.LastKnownPax.isDefined && f.LastKnownPax.get > 0 =>
        f.LastKnownPax.get
      case f =>
        f.MaxPax
    }
  }

  def lhrBestPax = (flight: Arrival) => {
    val DefaultPax = 200
    (flight.ActPax, flight.TranPax, flight.LastKnownPax, flight.MaxPax) match {
      case (actPaxIsLtE0, _, None, maxPaxValid) if actPaxIsLtE0 <= 0 && maxPaxValid > 0 => maxPaxValid
      case (DefaultPax, _, None, _) => DefaultPax
      case (DefaultPax, _, Some(lastPax), _) => lastPax
      case (actPaxIsLt0, _, Some(lastPax), _) if actPaxIsLt0 <= 0 => lastPax
      case (actPaxIsLt0, _, None, _) if actPaxIsLt0 <= 0 => DefaultPax
      case (actPax, tranPax, _, _) => actPax - tranPax
      case _ => DefaultPax
    }
  }

  def lhrBestPaxIncludingTransfer = (flight: Arrival) => {
    val DefaultPax = 200
    (flight.ActPax, flight.TranPax, flight.LastKnownPax, flight.MaxPax) match {
      case (actPaxIsLtE0, _, None, maxPaxValid) if actPaxIsLtE0 <= 0 && maxPaxValid > 0 => maxPaxValid
      case (DefaultPax, _, None, _) => DefaultPax
      case (DefaultPax, _, Some(lastPax), _) => lastPax
      case (actPaxIsLt0, _, Some(lastPax), _) if actPaxIsLt0 <= 0 => lastPax
      case (actPaxIsLt0, _, None, _) if actPaxIsLt0 <= 0 => DefaultPax
      case (actPax, _, _, _) => actPax
      case _ => DefaultPax
    }
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

  /*todo - we should move the usages of this to airportConfig */
  val inOrderSansFastTrack = List(
    eeaMachineReadableToEGate, eeaMachineReadableToDesk, eeaNonMachineReadableToDesk, visaNationalToDesk, nonVisaNationalToDesk)

  val inOrderWithFastTrack = List(
    eeaMachineReadableToEGate, eeaMachineReadableToDesk, eeaNonMachineReadableToDesk, visaNationalToDesk, nonVisaNationalToDesk, visaNationalToFastTrack, nonVisaNationalToFastTrack)
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
        "eGate" -> (List(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)),
        "eeaDesk" -> (List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9)),
        "nonEeaDesk" -> (List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(2, 2, 2, 2, 2, 2, 6, 6, 3, 3, 3, 3, 4, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3))
      ),
      "A2" -> Map(
        "eGate" -> (List(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)),
        "eeaDesk" -> (List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6)),
        "nonEeaDesk" -> (List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3))
      )
    ),
    shiftExamples = Seq(
      "Midnight shift, A1, {date}, 00:00, 00:59, 10",
      "Night shift, A1, {date}, 01:00, 06:59, 4",
      "Morning shift, A1, {date}, 07:00, 13:59, 15",
      "Afternoon shift, A1, {date}, 14:00, 16:59, 10",
      "Evening shift, A1, {date}, 17:00, 23:59,17"
    )
  )
  val stn = AirportConfig(
    portCode = "STN",
    queues = Map(
      "T1" -> Seq(EeaDesk, EGate, NonEeaDesk)
    ),
    slaByQueue = Map(EeaDesk -> 25, EGate -> 5, NonEeaDesk -> 45),
    terminalNames = Seq("T1"),
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
        "eGate" -> (List(2, 2, 0, 0, 0, 0, 0, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2), List(6, 6, 1, 1, 1, 1, 1, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6)),
        "eeaDesk" -> (List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13)),
        "nonEeaDesk" -> (List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8))
      )
    ),
    shiftExamples = Seq(
      "Alpha, T1, {date}, 07:00, 15:48, 0",
      "Bravo, T1, {date}, 07:45, 16:33, 0",
      "Charlie, T1, {date}, 15:00, 23:48, 0",
      "Delta, T1, {date}, 16:00, 00:48, 0",
      "Night, T1, {date}, 22:36, 07:24, 0"
    ),
    fixedPointExamples = Seq("Roving Officer,00:00,23:59,1",
      "Referral Officer,00:00,23:59,1",
      "Forgery Officer,00:00,23:59,1")
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
    defaultPaxSplits = defaultPaxSplits,
    defaultProcessingTimes = Map("T1" -> defaultProcessingTimes, "T2" -> defaultProcessingTimes, "T3" -> defaultProcessingTimes),
    minMaxDesksByTerminalQueue = Map(
      "T1" -> Map(
        "eGate" -> (List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2)),
        "eeaDesk" -> (List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6)),
        "nonEeaDesk" -> (List(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), List(5, 5, 5, 5, 5, 5, 7, 7, 7, 7, 5, 6, 6, 6, 6, 6, 5, 5, 5, 6, 5, 5, 5, 5))
      ),
      "T2" -> Map(
        "eGate" -> (List(1, 1, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)),
        "eeaDesk" -> (List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(8, 8, 8, 8, 8, 5, 5, 5, 5, 5, 5, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8)),
        "nonEeaDesk" -> (List(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), List(3, 3, 3, 3, 3, 8, 8, 8, 8, 8, 8, 3, 3, 3, 3, 3, 6, 6, 6, 6, 3, 3, 3, 3))
      ),
      "T3" -> Map(
        "eGate" -> (List(1, 1, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)),
        "eeaDesk" -> (List(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), List(6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6)),
        "nonEeaDesk" -> (List(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), List(3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3))
      )
    ),
    shiftExamples = Seq(
      "Midnight shift, T1, {date}, 00:00, 00:59, 25",
      "Night shift, T1, {date}, 01:00, 06:59, 10",
      "Morning shift, T1, {date}, 07:00, 13:59, 30",
      "Afternoon shift, T1, {date}, 14:00, 16:59, 18",
      "Evening shift, T1, {date}, 17:00, 23:59, 22"
    )
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
    terminalNames = Seq("T2", "T3", "T4", "T5"),
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
        "eGate" -> (List(0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(1, 1, 1, 1, 1, 2, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3)),
        "eeaDesk" -> (List(0, 0, 0, 0, 0, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2), List(9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9)),
        "fastTrack" -> (List(0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0), List(6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 0)),
        "nonEeaDesk" -> (List(0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20))
      ),
      "T3" -> Map(
        "eGate" -> (List(0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(1, 1, 1, 1, 1, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3)),
        "eeaDesk" -> (List(0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16)),
        "fastTrack" -> (List(0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7)),
        "nonEeaDesk" -> (List(0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23))
      ),
      "T4" -> Map(
        "eGate" -> (List(0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2)),
        "eeaDesk" -> (List(0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8)),
        "fastTrack" -> (List(0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4)),
        "nonEeaDesk" -> (List(0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27))
      ),
      "T5" -> Map(
        "eGate" -> (List(0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5)),
        "eeaDesk" -> (List(0, 0, 0, 0, 0, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2), List(6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6)),
        "fastTrack" -> (List(0, 0, 0, 0, 0, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 0), List(0, 0, 0, 0, 0, 2, 4, 4, 2, 2, 2, 2, 2, 2, 2, 3, 2, 2, 2, 2, 2, 2, 2, 0)),
        "nonEeaDesk" -> (List(0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20))
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
    hasActualDeskStats = true
  )
  val ltn = AirportConfig(
    portCode = "LTN",
    queues = Map(
      "T1" -> Seq(EeaDesk, EGate, NonEeaDesk)
    ),
    slaByQueue = defaultSlas,
    terminalNames = Seq("T1"),
    defaultPaxSplits = defaultPaxSplits,
    defaultProcessingTimes = Map("T1" -> defaultProcessingTimes),
    minMaxDesksByTerminalQueue = Map(
      "T1" -> Map(
        "eGate" -> (List(2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2), List(3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3)),
        "eeaDesk" -> (List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(6, 9, 9, 9, 9, 9, 9, 8, 6, 6, 6, 6, 6, 6, 7, 7, 7, 8, 6, 6, 7, 8, 6, 6)),
        "nonEeaDesk" -> (List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(4, 1, 1, 1, 1, 1, 1, 2, 4, 4, 4, 4, 4, 4, 3, 3, 3, 2, 4, 4, 3, 2, 4, 4))
      )
    )
  )
  val ema = AirportConfig(
    portCode = "EMA",
    queues = Map(
      "T1" -> Seq(EeaDesk, EGate, NonEeaDesk)
    ),
    slaByQueue = defaultSlas,
    terminalNames = Seq("T1"),
    defaultPaxSplits = defaultPaxSplits,
    defaultProcessingTimes = Map("T1" -> defaultProcessingTimes),
    minMaxDesksByTerminalQueue = Map(
      "T1" -> Map(
        "eGate" -> (List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)),
        "eeaDesk" -> (List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5)),
        "nonEeaDesk" -> (List(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1))
      )
    )
  )

  val allPorts = ema :: edi :: stn :: man :: ltn :: lhr :: Nil
  val confByPort = allPorts.map(c => (c.portCode, c)).toMap
}

/**
  * LGW shift examples
  */
/*
"A (P/T), {date}, 00:01, 07:25, 0",
"A, {date}, 00:01, 08:25, 0",
"S (P/T), {date}, 06:00, 13:24, 0",
"S, {date}, 06:00, 14:24, 0",
"E non team (P/T), {date}, 07:00, 14:24, 0",
"E non team, {date}, 07:00, 15:24, 0",
"E team, {date}, 07:00, 17:30, 0",
"L non team, {date}, 11:36, 20:00, 0",
"L non team (P/T), {date}, 12:36, 20:00, 0",
"L team, {date}, 13:00, 23:30, 0",
"LN non team, {date}, 15:36, 00:00, 0",
"LN Non team (P/T), {date}, 16:36, 00:00, 0",
"Night team, {date}, 21:00, 07:30, 0",
"Night non team (P/T), {date}, 23:00, 06:24, 0",
"Night non team, {date}, 23:00, 07:24, 0"
*/
