package spatutorial.shared

import spatutorial.shared.FlightsApi.{QueueName, TerminalName}
import scala.collection.immutable.Seq

case class AirportConfig(portCode: String = "n/a", queues: Seq[QueueName], slaByQueue: Map[String, Int], terminalNames: Seq[TerminalName]) extends AirportConfigLike

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
  val manSlas: Map[String, Int] = Map(
    "eeaDesk" -> 25,
    "eGate" -> 10,
    "nonEeaDesk" -> 45
  )

  val edi = AirportConfig(portCode = "EDI", queues = Seq("eeaDesk", "eGate", "nonEeaDesk"), slaByQueue = defaultSlas, terminalNames = Seq("A1", "A2"))
  val stn = AirportConfig(portCode = "STN", queues = Seq("eeaDesk", "eGate", "nonEeaDesk"), slaByQueue = defaultSlas, terminalNames = Seq("T1"))
  val man = AirportConfig(portCode = "MAN", queues = Seq("eeaDesk", "eGate", "nonEeaDesk"), slaByQueue = manSlas, terminalNames = Seq("T1", "T2", "T3"))
  val boh = AirportConfig(portCode = "BOH", queues = Seq("eeaDesk", "eGate", "nonEeaDesk"), slaByQueue = defaultSlas, terminalNames = Seq("T1"))
  val ltn = AirportConfig(portCode = "LTN", queues = Seq("eeaDesk", "eGate", "nonEeaDesk"), slaByQueue = defaultSlas, terminalNames = Seq("T1"))

  val allPorts = edi :: stn :: man :: boh :: ltn :: Nil
  val confByPort = allPorts.map(c => (c.portCode, c)).toMap
}