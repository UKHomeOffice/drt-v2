package spatutorial.shared

import spatutorial.shared.FlightsApi.{QueueName, TerminalName}
import scala.collection.immutable.Seq

case class AirportConfigHolder(terminalNames: Seq[TerminalName], queues: Seq[QueueName], slaByQueue: Map[String, Int])

trait AirportConfig {
  val airportConfigHolder: AirportConfigHolder
}

trait EdiAirportConfig extends AirportConfig {
  val airportConfigHolder = AirportConfigHolder(
    Seq("A1", "A2"),
    Seq("eeaDesk", "eGate", "nonEeaDesk"),
    Map(
      "eeaDesk" -> 20,
      "eGate" -> 25,
      "nonEeaDesk" -> 45
    )
  )
}

trait StnAirportConfig extends AirportConfig {
  val airportConfigHolder = AirportConfigHolder(
    Seq("T1"),
    Seq("eeaDesk", "eGate", "nonEeaDesk"),
    Map(
      "eeaDesk" -> 20,
      "eGate" -> 25,
      "nonEeaDesk" -> 45
    )
  )
}

trait ManAirportConfig extends AirportConfig {
  val airportConfigHolder = AirportConfigHolder(
    Seq("T1", "T2", "T3"),
    Seq("eeaDesk", "eGate", "nonEeaDesk"),
    Map(
      "eeaDesk" -> 20,
      "eGate" -> 25,
      "nonEeaDesk" -> 45
    )
  )
}

trait BohAirportConfig extends AirportConfig {
  val airportConfigHolder = AirportConfigHolder(
    Seq("T1"),
    Seq("eeaDesk", "eGate", "nonEeaDesk"),
    Map(
      "eeaDesk" -> 20,
      "eGate" -> 25,
      "nonEeaDesk" -> 45
    )
  )
}

trait LtnAirportConfig extends AirportConfig {
  val airportConfigHolder = AirportConfigHolder(
    Seq("T1"),
    Seq("eeaDesk", "eGate", "nonEeaDesk"),
    Map(
      "eeaDesk" -> 20,
      "eGate" -> 25,
      "nonEeaDesk" -> 45
    )
  )
}