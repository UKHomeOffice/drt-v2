package drt.shared.airportconfig

import drt.shared.PaxTypes.EeaMachineReadable
import drt.shared.PaxTypesAndQueues._
import drt.shared.Queues.{EGate, EeaDesk}
import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios, SplitSources}
import drt.shared.Terminals.T1
import drt.shared._

import scala.collection.immutable.SortedMap

object Ema extends AirportConfigLike {
  import AirportConfigDefaults._

  val config = AirportConfig(
    portCode = PortCode("EMA"),
    queuesByTerminal = SortedMap(
      T1 -> Seq(Queues.QueueDesk, Queues.EGate)
    ),
    divertedQueues = Map(
      Queues.NonEeaDesk -> Queues.QueueDesk,
      Queues.EeaDesk -> Queues.QueueDesk
    ),
    slaByQueue = Map(
      Queues.QueueDesk -> 20,
      Queues.EGate -> 25
    ),
    defaultWalkTimeMillis = Map(T1 -> 780000L),
    terminalPaxSplits = Map(T1 -> SplitRatios(
      SplitSources.TerminalAverage,
      SplitRatio(eeaMachineReadableToDesk, 0.99 * 0.2),
      SplitRatio(eeaMachineReadableToEGate, 0.99 * 0.8),
      SplitRatio(eeaNonMachineReadableToDesk, 0),
      SplitRatio(visaNationalToDesk, 0.0),
      SplitRatio(nonVisaNationalToDesk, 0.01)
    )),
    terminalProcessingTimes = Map(T1 -> Map(
      eeaMachineReadableToDesk -> 20d / 60,
      eeaMachineReadableToEGate -> 30d / 60,
      eeaNonMachineReadableToDesk -> 50d / 60,
      visaNationalToDesk -> 100d / 60,
      nonVisaNationalToDesk -> 80d / 60
    )),
    minMaxDesksByTerminalQueue = Map(
      T1 -> Map(
        Queues.EGate -> (List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)),
        Queues.QueueDesk -> (List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5))
      )
    ),
    role = EMAAccess,
    // This is deliberately high (10000) to cope with restoring previous snapshot bug where the time between snapshots
    // was going beyond the message threshold.
    // A neater fix would be to produce the missing snapshots retrospectively, but that would be quite a big job for a
    // minor gain
    portStateSnapshotInterval = 10000,
    terminalPaxTypeQueueAllocation = Map(
      T1 -> (defaultQueueRatios + (EeaMachineReadable -> List(
        EGate -> 0.6993,
        EeaDesk -> (1.0 - 0.6993)
      )))
    ),
    hasEstChox = true,
    desksByTerminal = Map(T1 -> 5),
    doesDeskFlexing = false
  )
}
