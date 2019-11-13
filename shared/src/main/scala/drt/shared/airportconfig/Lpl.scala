package drt.shared.airportconfig

import drt.shared.AirportConfigs.defaultQueueRatios
import drt.shared.PaxTypes.{B5JPlusNational, EeaMachineReadable}
import drt.shared.PaxTypesAndQueues._
import drt.shared.Queues.EeaDesk
import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios, SplitSources}
import drt.shared.{AirportConfig, AirportConfigLike, LPLAccess, Queues}

object Lpl extends AirportConfigLike {
  val config = AirportConfig(
    portCode = "LPL",
    queues = Map(
      "T1" -> Seq(Queues.NonEeaDesk, Queues.EeaDesk)
    ),
    slaByQueue = Map(
      Queues.EeaDesk -> 25,
      Queues.NonEeaDesk -> 45
    ),
    terminalNames = Seq("T1"),
    defaultWalkTimeMillis = Map("T1" -> 600000L),
    terminalPaxSplits = Map("T1" -> SplitRatios(
      SplitSources.TerminalAverage,
      SplitRatio(eeaMachineReadableToDesk, 0.98),
      SplitRatio(eeaNonMachineReadableToDesk, 0),
      SplitRatio(visaNationalToDesk, 0.0),
      SplitRatio(nonVisaNationalToDesk, 0.01)
    )),
    terminalProcessingTimes = Map("T1" -> Map(
      eeaMachineReadableToDesk -> 20d / 60,
      eeaMachineReadableToEGate -> 30d / 60,
      eeaNonMachineReadableToDesk -> 50d / 60,
      visaNationalToDesk -> 100d / 60,
      nonVisaNationalToDesk -> 80d / 60
    )),
    minMaxDesksByTerminalQueue = Map(
      "T1" -> Map(
        Queues.EeaDesk -> (List(2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2), List(6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6)),
        Queues.NonEeaDesk -> (List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2))
      )
    ),
    role = LPLAccess,
    terminalPaxTypeQueueAllocation = Map(
      "T1" -> (defaultQueueRatios + (
        EeaMachineReadable -> List(EeaDesk -> 1.0),
        B5JPlusNational -> List(Queues.EeaDesk -> 1.0)
      ))
    ))
}
