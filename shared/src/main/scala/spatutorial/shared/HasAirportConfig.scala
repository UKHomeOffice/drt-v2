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
                          defaultPaxSplits: List[SplitRatio],
                          defaultProcessingTimes: Map[TerminalName, Map[PaxTypeAndQueue, Double]],
                          shiftExamples: Seq[String] = Seq()
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
    SplitRatio(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eeaDesk), 0.4875),
    SplitRatio(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eGate), 0.1625),
    SplitRatio(PaxTypeAndQueue(PaxTypes.eeaNonMachineReadable, Queues.eeaDesk), 0.1625),
    SplitRatio(PaxTypeAndQueue(PaxTypes.visaNational, Queues.nonEeaDesk), 0.05),
    SplitRatio(PaxTypeAndQueue(PaxTypes.nonVisaNational, Queues.nonEeaDesk), 0.05)
  )
  val defaultProcessingTimes = Map(
    PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eeaDesk) -> 20d / 60,
    PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eGate) -> 35d / 60,
    PaxTypeAndQueue(PaxTypes.eeaNonMachineReadable, Queues.eeaDesk) -> 50d / 60,
    PaxTypeAndQueue(PaxTypes.visaNational, Queues.nonEeaDesk) -> 90d / 60,
    PaxTypeAndQueue(PaxTypes.nonVisaNational, Queues.nonEeaDesk) -> 78d / 60
  )

  val edi = AirportConfig(
    portCode = "EDI",
    queues = Seq("eeaDesk", "eGate", "nonEeaDesk"),
    slaByQueue = defaultSlas,
    terminalNames = Seq("A1", "A2"),
    defaultPaxSplits = defaultPaxSplits,
    defaultProcessingTimes = Map(
      "A1" -> Map(
        PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eeaDesk) -> 16d / 60,
        PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eGate) -> 25d / 60,
        PaxTypeAndQueue(PaxTypes.eeaNonMachineReadable, Queues.eeaDesk) -> 50d / 60,
        PaxTypeAndQueue(PaxTypes.visaNational, Queues.nonEeaDesk) -> 75d / 60,
        PaxTypeAndQueue(PaxTypes.nonVisaNational, Queues.nonEeaDesk) -> 64d / 60
      ),
      "A2" -> Map(
        PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eeaDesk) -> 30d / 60,
        PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eGate) -> 25d / 60,
        PaxTypeAndQueue(PaxTypes.eeaNonMachineReadable, Queues.eeaDesk) -> 50d / 60,
        PaxTypeAndQueue(PaxTypes.visaNational, Queues.nonEeaDesk) -> 120d / 60,
        PaxTypeAndQueue(PaxTypes.nonVisaNational, Queues.nonEeaDesk) -> 120d / 60
      )),
    shiftExamples = Seq(
      "Midnight shift,{date},00:00,00:59,10",
      "Night shift,{date},01:00,06:59,4",
      "Morning shift,{date},07:00,13:59,15",
      "Afternoon shift,{date},14:00,16:59,10",
      "Evening shift,{date},17:00,23:59,17"
    )
  )
  val stn = AirportConfig(
    portCode = "STN",
    queues = Seq("eeaDesk", "eGate", "nonEeaDesk"),
    slaByQueue = Map("eeaDesk" -> 25, "eGate" -> 5, "nonEeaDesk" -> 45),
    terminalNames = Seq("T1"),
    defaultPaxSplits = List(
      SplitRatio(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eeaDesk), 0.4875),
      SplitRatio(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eGate), 0.1625),
      SplitRatio(PaxTypeAndQueue(PaxTypes.eeaNonMachineReadable, Queues.eeaDesk), 0.1625),
      SplitRatio(PaxTypeAndQueue(PaxTypes.visaNational, Queues.nonEeaDesk), 0.05),
      SplitRatio(PaxTypeAndQueue(PaxTypes.nonVisaNational, Queues.nonEeaDesk), 0.05)
    ),
    defaultProcessingTimes = Map("T1" -> Map(
      PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eeaDesk) -> 20d / 60,
      PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eGate) -> 35d / 60,
      PaxTypeAndQueue(PaxTypes.eeaNonMachineReadable, Queues.eeaDesk) -> 50d / 60,
      PaxTypeAndQueue(PaxTypes.visaNational, Queues.nonEeaDesk) -> 90d / 60,
      PaxTypeAndQueue(PaxTypes.nonVisaNational, Queues.nonEeaDesk) -> 78d / 60
    )),
    shiftExamples = Seq(
      "Midnight shift,{date},00:00,00:59,14",
      "Night shift,{date},01:00,06:59,6",
      "Morning shift,{date},07:00,13:59,25",
      "Afternoon shift,{date},14:00,16:59,13",
      "Evening shift,{date},17:00,23:59,20"
    )
  )
  val man = AirportConfig(
    portCode = "MAN",
    queues = Seq("eeaDesk", "eGate", "nonEeaDesk"),
    slaByQueue = Map("eeaDesk" -> 25, "eGate" -> 10, "nonEeaDesk" -> 45),
    terminalNames = Seq("T1", "T2", "T3"),
    defaultPaxSplits = defaultPaxSplits,
    defaultProcessingTimes = Map("T1" -> defaultProcessingTimes, "T2" -> defaultProcessingTimes, "T3" -> defaultProcessingTimes),
    shiftExamples = Seq(
      "Midnight shift,{date},00:00,00:59,25",
      "Night shift,{date},01:00,06:59,10",
      "Morning shift,{date},07:00,13:59,30",
      "Afternoon shift,{date},14:00,16:59,18",
      "Evening shift,{date},17:00,23:59,22"
    )
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
