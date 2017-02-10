package spatutorial.shared

import spatutorial.shared.FlightsApi.{QueueName, TerminalName}
import spatutorial.shared.PaxTypes.{eeaMachineReadable, eeaNonMachineReadable, nonVisaNational, visaNational}
import spatutorial.shared.SplitRatios.SplitRatio

import scala.collection.immutable.Seq


object Queues {
  val eeaDesk = "eeaDesk"
  val eGate = "eGate"
  val nonEeaDesk = "nonEeaDesk"
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

object SplitRatios {
  type SplitRatios = List[SplitRatio]
  case class SplitRatio(paxType: PaxTypeAndQueue, ratio: Double)
}


case class AirportConfig(
                          portCode: String = "n/a",
                          queues: Seq[QueueName],
                          slaByQueue: Map[String, Int],
                          terminalNames: Seq[TerminalName],
                          defaultPaxSplits: List[SplitRatio],
                          defaultProcessingTimes: Map[TerminalName, Map[PaxTypeAndQueue, Double]]
                        ) extends AirportConfigLike {

}

trait HasAirportConfig {
  val airportConfig: AirportConfig
}

trait AirportConfigLike {
  def portCode: String

  def queues: Seq[QueueName]

  def slaByQueue: Map[String, Int]

  def terminalNames: Seq[TerminalName]
}

object AirportConfigs {
  val defaultSlas: Map[String, Int] = Map(
    "eeaDesk" -> 20,
    "eGate" -> 25,
    "nonEeaDesk" -> 45
  )
  val defaultPaxSplits = List(
    SplitRatio(PaxTypeAndQueue(eeaMachineReadable, Queues.eeaDesk), 0.4875),
    SplitRatio(PaxTypeAndQueue(eeaMachineReadable, Queues.eGate), 0.1625),
    SplitRatio(PaxTypeAndQueue(eeaNonMachineReadable, Queues.eeaDesk), 0.1625),
    SplitRatio(PaxTypeAndQueue(visaNational, Queues.nonEeaDesk), 0.05),
    SplitRatio(PaxTypeAndQueue(nonVisaNational, Queues.nonEeaDesk), 0.05)
  )
  val defaultProcessingTimes = Map(
    PaxTypeAndQueue(eeaMachineReadable, Queues.eeaDesk) -> 20d / 60,
    PaxTypeAndQueue(eeaMachineReadable, Queues.eGate) -> 35d / 60,
    PaxTypeAndQueue(eeaNonMachineReadable, Queues.eeaDesk) -> 50d / 60,
    PaxTypeAndQueue(visaNational, Queues.nonEeaDesk) -> 90d / 60,
    PaxTypeAndQueue(nonVisaNational, Queues.nonEeaDesk) -> 78d / 60
  )

  val edi = AirportConfig(
    portCode = "EDI",
    queues = Seq("eeaDesk", "eGate", "nonEeaDesk"),
    slaByQueue = defaultSlas,
    terminalNames = Seq("A1", "A2"),
    defaultPaxSplits = defaultPaxSplits,
    defaultProcessingTimes = Map(
      "A1" -> Map(
        PaxTypeAndQueue(eeaMachineReadable, Queues.eeaDesk) -> 16d / 60,
        PaxTypeAndQueue(eeaMachineReadable, Queues.eGate) -> 25d / 60,
        PaxTypeAndQueue(eeaNonMachineReadable, Queues.eeaDesk) -> 50d / 60,
        PaxTypeAndQueue(visaNational, Queues.nonEeaDesk) -> 75d / 60,
        PaxTypeAndQueue(nonVisaNational, Queues.nonEeaDesk) -> 64d / 60
      ),
      "A2" -> Map(
        PaxTypeAndQueue(eeaMachineReadable, Queues.eeaDesk) -> 30d / 60,
        PaxTypeAndQueue(eeaMachineReadable, Queues.eGate) -> 25d / 60,
        PaxTypeAndQueue(eeaNonMachineReadable, Queues.eeaDesk) -> 50d / 60,
        PaxTypeAndQueue(visaNational, Queues.nonEeaDesk) -> 120d / 60,
        PaxTypeAndQueue(nonVisaNational, Queues.nonEeaDesk) -> 120d / 60
      ))
  )
  val stn = AirportConfig(
    portCode = "STN",
    queues = Seq("eeaDesk", "eGate", "nonEeaDesk"),
    slaByQueue = Map("eeaDesk" -> 25, "eGate" -> 5, "nonEeaDesk" -> 45),
    terminalNames = Seq("T1"),
    defaultPaxSplits = List(
      SplitRatio(PaxTypeAndQueue(eeaMachineReadable, Queues.eeaDesk), 0.4875),
      SplitRatio(PaxTypeAndQueue(eeaMachineReadable, Queues.eGate), 0.1625),
      SplitRatio(PaxTypeAndQueue(eeaNonMachineReadable, Queues.eeaDesk), 0.1625),
      SplitRatio(PaxTypeAndQueue(visaNational, Queues.nonEeaDesk), 0.05),
      SplitRatio(PaxTypeAndQueue(nonVisaNational, Queues.nonEeaDesk), 0.05)
    ),
    defaultProcessingTimes = Map("T1" -> Map(
      PaxTypeAndQueue(eeaMachineReadable, Queues.eeaDesk) -> 20d / 60,
      PaxTypeAndQueue(eeaMachineReadable, Queues.eGate) -> 35d / 60,
      PaxTypeAndQueue(eeaNonMachineReadable, Queues.eeaDesk) -> 50d / 60,
      PaxTypeAndQueue(visaNational, Queues.nonEeaDesk) -> 90d / 60,
      PaxTypeAndQueue(nonVisaNational, Queues.nonEeaDesk) -> 78d / 60
    ))
  )
  val man = AirportConfig(
    portCode = "MAN",
    queues = Seq("eeaDesk", "eGate", "nonEeaDesk"),
    slaByQueue = Map("eeaDesk" -> 25, "eGate" -> 10, "nonEeaDesk" -> 45),
    terminalNames = Seq("T1", "T2", "T3"),
    defaultPaxSplits = defaultPaxSplits,
    defaultProcessingTimes = Map("T1" -> defaultProcessingTimes, "T2" -> defaultProcessingTimes, "T3" -> defaultProcessingTimes)
  )
  val ltn = AirportConfig(
    portCode = "LTN",
    queues = Seq("eeaDesk", "eGate", "nonEeaDesk"),
    slaByQueue = defaultSlas,
    terminalNames = Seq("T1"),
    defaultPaxSplits = defaultPaxSplits,
    defaultProcessingTimes = Map("T1" -> defaultProcessingTimes)
  )

  val allPorts = edi :: stn :: man :: ltn :: Nil
  val confByPort = allPorts.map(c => (c.portCode, c)).toMap
}
