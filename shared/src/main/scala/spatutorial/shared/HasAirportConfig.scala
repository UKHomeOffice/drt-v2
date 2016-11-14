package spatutorial.shared

import spatutorial.shared.FlightsApi.{QueueName, TerminalName}
import scala.collection.immutable.Seq

case class AirportConfig(portCode: String = "n/a", queues: Seq[QueueName], slaByQueue: Map[String, Int], terminalNames: Seq[TerminalName])

trait HasAirportConfig {
  val airportConfig: AirportConfig
}

trait EdiAirportConfig extends HasAirportConfig {
  val airportConfig = AirportConfig(
    portCode = "EDI",
    queues = Seq("eeaDesk", "eGate", "nonEeaDesk"),
    slaByQueue = Map(
      "eeaDesk" -> 20,
      "eGate" -> 25,
      "nonEeaDesk" -> 45
    ), terminalNames = Seq("A1", "A2"))
}

trait StnAirportConfig extends HasAirportConfig {
  val airportConfig = AirportConfig(
    portCode = "STN",
    queues = Seq("eeaDesk", "eGate", "nonEeaDesk"),
    slaByQueue = Map(
      "eeaDesk" -> 20,
      "eGate" -> 25,
      "nonEeaDesk" -> 45
    ), terminalNames = Seq("T1"))
}

trait ManAirportConfig extends HasAirportConfig {
  val airportConfig = AirportConfig(
    portCode = "MAN",
    queues = Seq("eeaDesk", "eGate", "nonEeaDesk"),
    slaByQueue = Map(
      "eeaDesk" -> 20,
      "eGate" -> 25,
      "nonEeaDesk" -> 45
    ), terminalNames = Seq("T1", "T2", "T3"))
}

trait BohAirportConfig extends HasAirportConfig {
  val airportConfig = AirportConfig(
    portCode = "BOH",
    queues = Seq("eeaDesk", "eGate", "nonEeaDesk"),
    slaByQueue = Map(
      "eeaDesk" -> 20,
      "eGate" -> 25,
      "nonEeaDesk" -> 45
    ), terminalNames = Seq("T1"))
}

trait LtnAirportConfig extends HasAirportConfig {
  val airportConfig = AirportConfig(
    portCode = "LTN",
    queues = Seq("eeaDesk", "eGate", "nonEeaDesk"),
    slaByQueue = Map(
      "eeaDesk" -> 20,
      "eGate" -> 25,
      "nonEeaDesk" -> 45
    ), terminalNames = Seq("T1"))
}