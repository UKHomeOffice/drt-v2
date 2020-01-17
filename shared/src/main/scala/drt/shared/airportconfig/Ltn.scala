package drt.shared.airportconfig

import drt.shared.PaxTypes.EeaMachineReadable
import drt.shared.PaxTypesAndQueues._
import drt.shared.Queues.{EGate, EeaDesk, NonEeaDesk}
import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios, SplitSources}
import drt.shared.Terminals.T1
import drt.shared._

import scala.collection.immutable.SortedMap

object Ltn extends AirportConfigLike {
  import AirportConfigDefaults._

  val config = AirportConfig(
    portCode = PortCode("LTN"),
    queuesByTerminal = SortedMap(
      T1 -> Seq(EeaDesk, EGate, NonEeaDesk)
    ),
    slaByQueue = defaultSlas,
    defaultWalkTimeMillis = Map(T1 -> 300000L),
    terminalPaxSplits = Map(T1 -> SplitRatios(
      SplitSources.TerminalAverage,
      SplitRatio(eeaMachineReadableToDesk, 0.02078),
      SplitRatio(eeaMachineReadableToEGate, 0.07922),
      SplitRatio(eeaNonMachineReadableToDesk, 0.1625),
      SplitRatio(visaNationalToDesk, 0.05),
      SplitRatio(nonVisaNationalToDesk, 0.05)
    )),
    terminalProcessingTimes = Map(T1 -> defaultProcessingTimes),
    minMaxDesksByTerminalQueue = Map(
      T1 -> Map(
        Queues.EGate -> (List.fill(24)(1), List.fill(24)(2)),
        Queues.EeaDesk -> (List.fill(24)(1), List(9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9)),
        Queues.NonEeaDesk -> (List.fill(24)(1), List(5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5))
      )
    ),
    role = LTNAccess,
    terminalPaxTypeQueueAllocation = Map(
      T1 -> (defaultQueueRatios + (EeaMachineReadable -> List(
        EGate -> 0.7922,
        EeaDesk -> (1.0 - 0.7922)
      )))
    )
  )
}
