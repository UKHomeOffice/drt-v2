package drt.shared.airportconfig

import drt.shared.AirportConfigs.defaultQueueRatios
import drt.shared.PaxTypes.EeaMachineReadable
import drt.shared.PaxTypesAndQueues._
import drt.shared.Queues.{EGate, EeaDesk, NonEeaDesk}
import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios, SplitSources}
import drt.shared.{AirportConfig, AirportConfigLike, Queues, STNAccess}


object Stn extends AirportConfigLike {
  val config = AirportConfig(
    portCode = "STN",
    queues = Map(
      "T1" -> Seq(EeaDesk, EGate, NonEeaDesk)
    ),
    slaByQueue = Map(EeaDesk -> 25, EGate -> 5, NonEeaDesk -> 45),
    terminalNames = Seq("T1"),
    crunchOffsetMinutes = 240,
    dayLengthHours = 36,
    defaultWalkTimeMillis = Map("T1" -> 600000L),
    terminalPaxSplits = Map("T1" -> SplitRatios(
      SplitSources.TerminalAverage,
      SplitRatio(eeaMachineReadableToDesk, 0.1916),
      SplitRatio(eeaMachineReadableToEGate, 0.8084),
      SplitRatio(eeaNonMachineReadableToDesk, 0.0),
      SplitRatio(visaNationalToDesk, 0.0),
      SplitRatio(nonVisaNationalToDesk, 0.01)
    )),
    terminalProcessingTimes = Map("T1" -> Map(
      eeaMachineReadableToDesk -> 20d / 60,
      eeaMachineReadableToEGate -> 35d / 60,
      eeaNonMachineReadableToDesk -> 50d / 60,
      visaNationalToDesk -> 90d / 60,
      nonVisaNationalToDesk -> 78d / 60
    )),
    minMaxDesksByTerminalQueue = Map(
      "T1" -> Map(
        Queues.EGate -> (List(1, 1, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(3, 3, 1, 1, 1, 1, 1, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3)),
        Queues.EeaDesk -> (List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13)),
        Queues.NonEeaDesk -> (List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8))
      )
    ),
    shiftExamples = Seq(
      "Alpha, T1, {date}, 07:00, 15:48, 0",
      "Bravo, T1, {date}, 07:45, 16:33, 0",
      "Charlie, T1, {date}, 15:00, 23:48, 0",
      "Delta, T1, {date}, 16:00, 00:48, 0",
      "Night, T1, {date}, 22:36, 07:24, 0"
    ),
    fixedPointExamples = Seq("Roving Officer, 00:00, 23:59, 1",
      "Referral Officer, 00:00, 23:59, 1",
      "Forgery Officer, 00:00, 23:59, 1"),
    role = STNAccess,
    terminalPaxTypeQueueAllocation = Map(
      "T1" -> (defaultQueueRatios + (EeaMachineReadable -> List(
        EGate -> 0.8084,
        EeaDesk -> (1.0 - 0.8084)
      )))
    )
  )
}
