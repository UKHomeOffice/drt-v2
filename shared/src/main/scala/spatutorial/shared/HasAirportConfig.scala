package drt.shared

import drt.shared.FlightsApi.{QueueName, TerminalName}
import drt.shared.PaxTypes.{eeaMachineReadable, eeaNonMachineReadable, nonVisaNational, visaNational}
import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios}

import scala.collection.immutable.Seq


object Queues {
  val eeaDesk = "eeaDesk"
  val eGate = "eGate"
  val nonEeaDesk = "nonEeaDesk"
  val fastTrack = "fastTrack"
}

sealed trait PaxType {
  def name = getClass.getName
}

object PaxTypes {

  case object eeaNonMachineReadable extends PaxType

  case object visaNational extends PaxType

  case object eeaMachineReadable extends PaxType

  case object nonVisaNational extends PaxType

}

case class PaxTypeAndQueue(passengerType: PaxType, queueType: String)


case class AirportConfig(
                          portCode: String = "n/a",
                          queues: Map[TerminalName, Seq[QueueName]],
                          slaByQueue: Map[String, Int],
                          terminalNames: Seq[TerminalName],
                          defaultPaxSplits: SplitRatios,
                          defaultProcessingTimes: Map[TerminalName, Map[PaxTypeAndQueue, Double]],
                          shiftExamples: Seq[String] = Seq()
                        ) extends AirportConfigLike {

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
  val eeaMachineReadableToDesk = PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eeaDesk)
  val eeaMachineReadableToEGate = PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eGate)
  val eeaNonMachineReadableToDesk = PaxTypeAndQueue(PaxTypes.eeaNonMachineReadable, Queues.eeaDesk)
  val visaNationalToDesk = PaxTypeAndQueue(PaxTypes.visaNational, Queues.nonEeaDesk)
  val nonVisaNationalToDesk = PaxTypeAndQueue(PaxTypes.nonVisaNational, Queues.nonEeaDesk)
  val visaNationalToFastTrack = PaxTypeAndQueue(PaxTypes.nonVisaNational, Queues.fastTrack)
  val nonVisaNationalToFastTrack = PaxTypeAndQueue(PaxTypes.nonVisaNational, Queues.fastTrack)
}

object AirportConfigs {
  val defaultSlas: Map[String, Int] = Map(
    "eeaDesk" -> 20,
    "eGate" -> 25,
    "nonEeaDesk" -> 45
  )

  import PaxTypesAndQueues._

  val defaultPaxSplits = SplitRatios(
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
      "A1" -> Seq("eeaDesk", "eGate", "nonEeaDesk"),
      "A2" -> Seq("eeaDesk", "eGate", "nonEeaDesk")
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
        eeaMachineReadableToDesk -> 30d / 60,
        eeaMachineReadableToEGate -> 25d / 60,
        eeaNonMachineReadableToDesk -> 50d / 60,
        visaNationalToDesk -> 120d / 60,
        nonVisaNationalToDesk -> 120d / 60
      )),
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
      "T1" -> Seq("eeaDesk", "eGate", "nonEeaDesk")
    ),
    slaByQueue = Map("eeaDesk" -> 25, "eGate" -> 5, "nonEeaDesk" -> 45),
    terminalNames = Seq("T1"),
    defaultPaxSplits = SplitRatios(
      SplitRatio(eeaMachineReadableToDesk, 0.4875),
      SplitRatio(eeaMachineReadableToEGate, 0.1625),
      SplitRatio(eeaNonMachineReadableToDesk, 0.1625),
      SplitRatio(visaNationalToDesk, 0.05),
      SplitRatio(nonVisaNationalToDesk, 0.05)
    ),
    defaultProcessingTimes = Map("T1" -> Map(
      eeaMachineReadableToDesk -> 20d / 60,
      eeaMachineReadableToEGate -> 35d / 60,
      eeaNonMachineReadableToDesk -> 50d / 60,
      visaNationalToDesk -> 90d / 60,
      nonVisaNationalToDesk -> 78d / 60
    )),
    shiftExamples = Seq(
      "Alpha, T1, {date}, 07:00, 15:48, 0",
      "Bravo, T1, {date}, 07:45, 16:33, 0",
      "Charlie, T1, {date}, 15:00, 23:48, 0",
      "Delta, T1, {date}, 16:00, 00:48, 0",
      "Night, T1, {date}, 22:36, 07:24, 0"
    )
  )
  val man = AirportConfig(
    portCode = "MAN",
    queues = Map(
      "T1" -> Seq("eeaDesk", "eGate", "nonEeaDesk"),
      "T2" -> Seq("eeaDesk", "eGate", "nonEeaDesk"),
      "T3" -> Seq("eeaDesk", "eGate", "nonEeaDesk")
    ),
    slaByQueue = Map("eeaDesk" -> 25, "eGate" -> 10, "nonEeaDesk" -> 45),
    terminalNames = Seq("T1", "T2", "T3"),
    defaultPaxSplits = defaultPaxSplits,
    defaultProcessingTimes = Map("T1" -> defaultProcessingTimes, "T2" -> defaultProcessingTimes, "T3" -> defaultProcessingTimes),
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
    visaNationalToFastTrack -> 78d / 60
  )
  val lhr = AirportConfig(
    portCode = "LHR",
    queues = Map(
      "T2" -> Seq("eeaDesk", "eGate", "nonEeaDesk", "fastTrack"),
      "T3" -> Seq("eeaDesk", "eGate", "nonEeaDesk", "fastTrack"),
      "T4" -> Seq("eeaDesk", "eGate", "nonEeaDesk", "fastTrack"),
      "T5" -> Seq("eeaDesk", "eGate", "nonEeaDesk", "fastTrack")
    ),
    slaByQueue = Map("eeaDesk" -> 25, "eGate" -> 15, "nonEeaDesk" -> 45, "fastTrack" -> 15),
    terminalNames = Seq("T2", "T3", "T4", "T5"),
    defaultPaxSplits = SplitRatios(
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
    shiftExamples = Seq(
      "Midnight shift, T2, {date}, 00:00, 00:59, 25",
      "Night shift, T2, {date}, 01:00, 06:59, 10",
      "Morning shift, T2, {date}, 07:00, 13:59, 30",
      "Afternoon shift, T2, {date}, 14:00, 16:59, 18",
      "Evening shift, T2, {date}, 17:00, 23:59, 22"
    )
  )
  val ltn = AirportConfig(
    portCode = "LTN",
    queues = Map(
      "T1" -> Seq("eeaDesk", "eGate", "nonEeaDesk")
    ),
    slaByQueue = defaultSlas,
    terminalNames = Seq("T1"),
    defaultPaxSplits = defaultPaxSplits,
    defaultProcessingTimes = Map("T1" -> defaultProcessingTimes)
  )
  val ema = AirportConfig(
    portCode = "EMA",
    queues = Map(
      "T1" -> Seq("eeaDesk", "eGate", "nonEeaDesk")
    ),
    slaByQueue = defaultSlas,
    terminalNames = Seq("T1"),
    defaultPaxSplits = defaultPaxSplits,
    defaultProcessingTimes = Map("T1" -> defaultProcessingTimes)
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
