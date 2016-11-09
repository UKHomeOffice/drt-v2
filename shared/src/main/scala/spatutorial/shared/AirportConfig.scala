package spatutorial.shared

import spatutorial.shared.FlightsApi.{QueueName, TerminalName}
import scala.collection.immutable.Seq

trait AirportConfig {
  val terminalNames: Seq[TerminalName]
  val queues: Seq[QueueName]
  def slaFromTerminalAndQueue(terminal: String, queue: String): Int
}

trait EdiAirportConfig extends AirportConfig {
  val terminalNames: Seq[TerminalName] = Seq("A1", "A2")
  val airportShortCode: String = "edi"
  val eeadesk = "eeaDesk"
  val egate = "eGate"
  val nonEeaDesk = "nonEeaDesk"
  val queues: Seq[QueueName] = Seq(eeadesk, egate, nonEeaDesk)

  def slaFromTerminalAndQueue(terminal: String, queue: String) = (terminal, queue) match {
    case ("A1", "eeaDesk") => 20
    case ("A1", "eGate") => 25
    case ("A1", "nonEeaDesk") => 45
    case ("A2", "eeaDesk") => 20
    case ("A2", "eGate") => 25
    case ("A2", "nonEeaDesk") => 45
  }
}

trait StnAirportConfig extends AirportConfig {
  val terminalNames: Seq[TerminalName] = Seq("T1")
  val airportShortCode: String = "stn"
  val eeadesk = "eeaDesk"
  val egate = "eGate"
  val nonEeaDesk = "nonEeaDesk"
  val queues: Seq[QueueName] = Seq(eeadesk, egate, nonEeaDesk)

  def slaFromTerminalAndQueue(terminal: String, queue: String) = (terminal, queue) match {
    case ("T1", "eeaDesk") => 20
    case ("T1", "eGate") => 25
    case ("T1", "nonEeaDesk") => 45
  }
}