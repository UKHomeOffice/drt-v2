package spatutorial.shared

import spatutorial.shared.FlightsApi.{QueueName, TerminalName}
import scala.collection.immutable.Seq

case class AirportConfigHolder(portCode: String = "n/a", queues: Seq[QueueName], slaByQueue: Map[String, Int], terminalNames: Seq[TerminalName])

trait AirportConfig {
  val airportConfigHolder: AirportConfigHolder
}

trait EdiAirportConfig extends AirportConfig {
  val airportConfigHolder = AirportConfigHolder(
    portCode = "EDI",
    queues = Seq("eeaDesk", "eGate", "nonEeaDesk"),
    slaByQueue = Map(
      "eeaDesk" -> 20,
      "eGate" -> 25,
      "nonEeaDesk" -> 45
    ), terminalNames = Seq("A1", "A2"))
}

trait StnAirportConfig extends AirportConfig {
  val airportConfigHolder = AirportConfigHolder(
    portCode = "STN",
    queues = Seq("eeaDesk", "eGate", "nonEeaDesk"),
    slaByQueue = Map(
      "eeaDesk" -> 20,
      "eGate" -> 25,
      "nonEeaDesk" -> 45
    ), terminalNames = Seq("T1"))
}

trait ManAirportConfig extends AirportConfig {
  val airportConfigHolder = AirportConfigHolder(
    portCode = "MAN",
    queues = Seq("eeaDesk", "eGate", "nonEeaDesk"),
    slaByQueue = Map(
      "eeaDesk" -> 20,
      "eGate" -> 25,
      "nonEeaDesk" -> 45
    ), terminalNames = Seq("T1", "T2", "T3"))
}

trait BohAirportConfig extends AirportConfig {
  val airportConfigHolder = AirportConfigHolder(
    portCode = "BOH",
    queues = Seq("eeaDesk", "eGate", "nonEeaDesk"),
    slaByQueue = Map(
      "eeaDesk" -> 20,
      "eGate" -> 25,
      "nonEeaDesk" -> 45
    ), terminalNames = Seq("T1"))
}

trait LtnAirportConfig extends AirportConfig {
  val airportConfigHolder = AirportConfigHolder(
    portCode = "LTN",
    queues = Seq("eeaDesk", "eGate", "nonEeaDesk"),
    slaByQueue = Map(
      "eeaDesk" -> 20,
      "eGate" -> 25,
      "nonEeaDesk" -> 45
    ), terminalNames = Seq("T1"))
}