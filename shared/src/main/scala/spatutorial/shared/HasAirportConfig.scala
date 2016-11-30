package spatutorial.shared

import spatutorial.shared.FlightsApi.{QueueName, TerminalName}

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
case class SplitRatio(paxType: PaxTypeAndQueue, ratio: Double)

case class AirportConfig(
                          portCode: String = "n/a",
                          queues: Seq[QueueName],
                          slaByQueue: Map[String, Int],
                          terminalNames: Seq[TerminalName],
                          defaultPaxSplits: List[SplitRatio]
                        ) extends AirportConfigLike

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
    SplitRatio(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eeaDesk), 0.4875),
    SplitRatio(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eGate), 0.1625),
    SplitRatio(PaxTypeAndQueue(PaxTypes.eeaNonMachineReadable, Queues.eeaDesk), 0.1625),
    SplitRatio(PaxTypeAndQueue(PaxTypes.visaNational, Queues.nonEeaDesk), 0.05),
    SplitRatio(PaxTypeAndQueue(PaxTypes.nonVisaNational, Queues.nonEeaDesk), 0.05)
  )

  val edi = AirportConfig(portCode = "EDI", queues = Seq("eeaDesk", "eGate", "nonEeaDesk"), slaByQueue = defaultSlas, terminalNames = Seq("A1", "A2"), defaultPaxSplits = Nil)
  val stn = AirportConfig(portCode = "STN", queues = Seq("eeaDesk", "eGate", "nonEeaDesk"), slaByQueue = Map(
    "eeaDesk" -> 25,
    "eGate" -> 5,
    "nonEeaDesk" -> 45
  ), terminalNames = Seq("T1"), defaultPaxSplits = Nil)
  val man = AirportConfig(portCode = "MAN", queues = Seq("eeaDesk", "eGate", "nonEeaDesk"), slaByQueue = Map(
    "eeaDesk" -> 25,
    "eGate" -> 10,
    "nonEeaDesk" -> 45
  ), terminalNames = Seq("T1", "T2", "T3"), defaultPaxSplits = Nil)
  val boh = AirportConfig(portCode = "BOH", queues = Seq("eeaDesk", "eGate", "nonEeaDesk"), slaByQueue = defaultSlas, terminalNames = Seq("T1"), defaultPaxSplits = Nil)
  val ltn = AirportConfig(portCode = "LTN", queues = Seq("eeaDesk", "eGate", "nonEeaDesk"), slaByQueue = defaultSlas, terminalNames = Seq("T1"), defaultPaxSplits = Nil)

  val allPorts = edi :: stn :: man :: boh :: ltn :: Nil
  val confByPort = allPorts.map(c => (c.portCode, c)).toMap
}