package drt.shared.airportconfig

import drt.shared.PaxTypes.{B5JPlusNational, EeaMachineReadable}
import drt.shared.PaxTypesAndQueues._
import drt.shared.Queues.{EGate, EeaDesk, NonEeaDesk}
import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios, SplitSources}
import drt.shared.{AirportConfig, AirportConfigDefaults, AirportConfigLike, BHXAccess, Queues}

object Bhx extends AirportConfigLike {

  import AirportConfigDefaults._

  val config = AirportConfig(
    portCode = "BHX",
    queues = Map(
      "T1" -> Seq(EeaDesk, EGate, NonEeaDesk),
      "T2" -> Seq(EeaDesk, NonEeaDesk)
    ),
    slaByQueue = defaultSlas,
    terminalNames = Seq("T1", "T2"),
    defaultWalkTimeMillis = Map("T1" -> 240000L, "T2" -> 240000L),
    terminalPaxSplits = Map(
      "T1" -> SplitRatios(
        SplitSources.TerminalAverage,
        SplitRatio(eeaMachineReadableToDesk, 0.92 * 0.2446),
        SplitRatio(eeaMachineReadableToEGate, 0.92 * 0.7554),
        SplitRatio(eeaNonMachineReadableToDesk, 0),
        SplitRatio(visaNationalToDesk, 0.04),
        SplitRatio(nonVisaNationalToDesk, 0.04)
      ),
      "T2" -> SplitRatios(
        SplitSources.TerminalAverage,
        SplitRatio(eeaMachineReadableToDesk, 0.92),
        SplitRatio(eeaNonMachineReadableToDesk, 0),
        SplitRatio(visaNationalToDesk, 0.04),
        SplitRatio(nonVisaNationalToDesk, 0.04)
      )),
    terminalProcessingTimes = Map("T1" -> Map(
      eeaMachineReadableToDesk -> 16d / 60,
      eeaMachineReadableToEGate -> 20d / 60,
      eeaNonMachineReadableToDesk -> 50d / 60,
      visaNationalToDesk -> 93d / 60,
      nonVisaNationalToDesk -> 83d / 60
    ), "T2" -> Map(
      eeaMachineReadableToDesk -> 16d / 60,
      eeaNonMachineReadableToDesk -> 50d / 60,
      visaNationalToDesk -> 93d / 60,
      nonVisaNationalToDesk -> 83d / 60
    )),
    minMaxDesksByTerminalQueue = Map(
      "T1" -> Map(
        Queues.EGate -> (List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1),
          List(2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2)),
        Queues.EeaDesk -> (List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1),
          List(6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6)),
        Queues.NonEeaDesk -> (List(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
          List(8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8))
      ),
      "T2" -> Map(
        Queues.EeaDesk -> (List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1),
          List(4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4)),
        Queues.NonEeaDesk -> (List(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
          List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1))
      )
    ),
    role = BHXAccess,
    terminalPaxTypeQueueAllocation = Map(
      "T1" -> (defaultQueueRatios + (EeaMachineReadable -> List(
        EGate -> 0.7968,
        EeaDesk -> (1.0 - 0.7968)
      ))),
      "T2" -> (defaultQueueRatios ++ List(
        (EeaMachineReadable -> List(EeaDesk -> 1.0)),
        (B5JPlusNational -> List(EeaDesk -> 1.0))
      ))
    )
  )
}
