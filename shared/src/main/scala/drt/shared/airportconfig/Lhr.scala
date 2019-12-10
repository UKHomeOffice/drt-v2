package drt.shared.airportconfig

import drt.shared.PaxTypes._
import drt.shared.PaxTypesAndQueues._
import drt.shared.Queues._
import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios, SplitSources}
import drt.shared.Terminals._
import drt.shared._

object Lhr extends AirportConfigLike {
  val lhrDefaultTerminalProcessingTimes: Map[PaxTypeAndQueue, Double] = Map(
    eeaMachineReadableToDesk -> 25d / 60,
    eeaMachineReadableToEGate -> 25d / 60,
    eeaNonMachineReadableToDesk -> 55d / 60,
    visaNationalToDesk -> 96d / 60,
    nonVisaNationalToDesk -> 78d / 60,
    nonVisaNationalToFastTrack -> 78d / 60,
    visaNationalToFastTrack -> 78d / 60,
    transitToTransfer -> 0d
  )

  val lhrDefaultQueueRatios: Map[PaxType, Seq[(Queue, Double)]] = Map(
    EeaMachineReadable -> List(Queues.EGate -> 0.8, Queues.EeaDesk -> 0.2),
    EeaBelowEGateAge -> List(Queues.EeaDesk -> 1.0),
    EeaNonMachineReadable -> List(Queues.EeaDesk -> 1.0),
    Transit -> List(Queues.Transfer -> 1.0),
    NonVisaNational -> List(Queues.NonEeaDesk -> 1.0),
    VisaNational -> List(Queues.NonEeaDesk -> 1.0),
    B5JPlusNational -> List(Queues.EGate -> 0.6, Queues.EeaDesk -> 0.4),
    B5JPlusNationalBelowEGateAge -> List(Queues.EeaDesk -> 1)
  )

  val config = AirportConfig(
    portCode = PortCode("LHR"),
    queues = Map(
      T2 -> Seq(EeaDesk, EGate, NonEeaDesk, FastTrack, Transfer),
      T3 -> Seq(EeaDesk, EGate, NonEeaDesk, FastTrack, Transfer),
      T4 -> Seq(EeaDesk, EGate, NonEeaDesk, FastTrack, Transfer),
      T5 -> Seq(EeaDesk, EGate, NonEeaDesk, FastTrack, Transfer)
    ),
    slaByQueue = Map(EeaDesk -> 25, EGate -> 15, NonEeaDesk -> 45, FastTrack -> 15),
    crunchOffsetMinutes = 120,
    dayLengthHours = 36,
    terminals = Seq(T2, T3, T4, T5),
    defaultWalkTimeMillis = Map(T2 -> 900000L, T3 -> 660000L, T4 -> 900000L, T5 -> 660000L),
    terminalPaxSplits = List(T2, T3, T4, T5).map(t => (t, SplitRatios(
      SplitSources.TerminalAverage,
      SplitRatio(eeaMachineReadableToDesk, 0.64 * 0.2),
      SplitRatio(eeaMachineReadableToEGate, 0.64 * 0.8),
      SplitRatio(eeaNonMachineReadableToDesk, 0),
      SplitRatio(visaNationalToDesk, 0.08 * 0.95),
      SplitRatio(visaNationalToFastTrack, 0.08 * 0.05),
      SplitRatio(nonVisaNationalToDesk, 0.28 * 0.95),
      SplitRatio(nonVisaNationalToFastTrack, 0.28 * 0.05)
    ))).toMap,
    terminalProcessingTimes = Map(
      T2 -> lhrDefaultTerminalProcessingTimes,
      T3 -> lhrDefaultTerminalProcessingTimes,
      T4 -> lhrDefaultTerminalProcessingTimes,
      T5 -> lhrDefaultTerminalProcessingTimes
    ),
    minMaxDesksByTerminalQueue = Map(
      T2 -> Map(
        Queues.EGate -> (List(0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2)),
        Queues.EeaDesk -> (List(0, 0, 0, 0, 0, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2), List(9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9)),
        Queues.FastTrack -> (List(0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0), List(6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6)),
        Queues.NonEeaDesk -> (List(0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20))
      ),
      T3 -> Map(
        Queues.EGate -> (List(0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2)),
        Queues.EeaDesk -> (List(0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16)),
        Queues.FastTrack -> (List(0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7)),
        Queues.NonEeaDesk -> (List(0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23))
      ),
      T4 -> Map(
        Queues.EGate -> (List(0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)),
        Queues.EeaDesk -> (List(0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8)),
        Queues.FastTrack -> (List(0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4)),
        Queues.NonEeaDesk -> (List(0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27))
      ),
      T5 -> Map(
        Queues.EGate -> (List(0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3)),
        Queues.EeaDesk -> (List(0, 0, 0, 0, 0, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2), List(6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6)),
        Queues.FastTrack -> (List(0, 0, 0, 0, 0, 2, 2, 2, 1, 1, 1, 1, 2, 2, 2, 2, 2, 1, 1, 1, 1, 1, 1, 0), List(0, 0, 0, 0, 0, 2, 2, 2, 1, 1, 1, 1, 2, 2, 2, 2, 2, 1, 1, 1, 1, 1, 1, 0)),
        Queues.NonEeaDesk -> (List(0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20))
      )
    ),
    shiftExamples = Seq(
      "Midnight shift, T2, {date}, 00:00, 00:59, 25",
      "Night shift, T2, {date}, 01:00, 06:59, 10",
      "Morning shift, T2, {date}, 07:00, 13:59, 30",
      "Afternoon shift, T2, {date}, 14:00, 16:59, 18",
      "Evening shift, T2, {date}, 17:00, 23:59, 22"
    ),
    hasActualDeskStats = true,
    portStateSnapshotInterval = 250,
    hasEstChox = true,
    exportQueueOrder = Queues.exportQueueOrderWithFastTrack,
    role = LHRAccess,
    terminalPaxTypeQueueAllocation = Map(
      T2 -> (lhrDefaultQueueRatios + (EeaMachineReadable -> List(
        EGate -> 0.8102,
        EeaDesk -> (1.0 - 0.8102)
      ))),
      T3 -> (lhrDefaultQueueRatios + (EeaMachineReadable -> List(
        EGate -> 0.8075,
        EeaDesk -> (1.0 - 0.8075)
      ))),
      T4 -> (lhrDefaultQueueRatios + (EeaMachineReadable -> List(
        EGate -> 0.7687,
        EeaDesk -> (1.0 - 0.7687)
      ))),
      T5 -> (lhrDefaultQueueRatios + (EeaMachineReadable -> List(
        EGate -> 0.8466,
        EeaDesk -> (1.0 - 0.8466)
      )))
    ),
    hasTransfer = true
  )
}
