package drt.shared.airportconfig

import drt.shared.PaxTypes.EeaMachineReadable
import drt.shared.PaxTypesAndQueues._
import drt.shared.Queues.{EGate, EeaDesk, NonEeaDesk}
import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios, SplitSources}
import drt.shared.Terminals.{N, S}
import drt.shared._

object Lgw extends AirportConfigLike {
  import AirportConfigDefaults._

  val config = AirportConfig(
    portCode = PortCode("LGW"),
    queues = Map(
      N -> Seq(EeaDesk, EGate, NonEeaDesk),
      S -> Seq(EeaDesk, EGate, NonEeaDesk)
    ),
    slaByQueue = Map(
      EeaDesk -> 25,
      EGate -> 10,
      NonEeaDesk -> 45
    ),
    hasEstChox = true,
    terminals = Seq(N, S),
    defaultWalkTimeMillis = Map(N -> 180000L, S -> 180000L),
    terminalPaxSplits = List(N, S).map(t => (t, SplitRatios(
      SplitSources.TerminalAverage,
      SplitRatio(eeaMachineReadableToDesk, 0.85 * 0.17),
      SplitRatio(eeaMachineReadableToEGate, 0.85 * 0.83),
      SplitRatio(eeaNonMachineReadableToDesk, 0d),
      SplitRatio(visaNationalToDesk, 0.06),
      SplitRatio(nonVisaNationalToDesk, 0.09)
    ))).toMap,
    terminalProcessingTimes = Map(
      N -> Map(
        eeaMachineReadableToDesk -> 23d / 60,
        eeaMachineReadableToEGate -> 30d / 60,
        eeaNonMachineReadableToDesk -> 55d / 60,
        visaNationalToDesk -> 92d / 60,
        nonVisaNationalToDesk -> 77d / 60
      ),
      S -> Map(
        eeaMachineReadableToDesk -> 23d / 60,
        eeaMachineReadableToEGate -> 30d / 60,
        eeaNonMachineReadableToDesk -> 42d / 60,
        visaNationalToDesk -> 99d / 60,
        nonVisaNationalToDesk -> 81d / 60
      )),
    minMaxDesksByTerminalQueue = Map(
      N -> Map(
        Queues.EGate -> (List(0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3)),
        Queues.EeaDesk -> (List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13)),
        Queues.NonEeaDesk -> (List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15))
      ),
      S -> Map(
        Queues.EGate -> (List(0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3)),
        Queues.EeaDesk -> (List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(15, 15, 15, 15, 15, 15, 13, 10, 10, 10, 10, 10, 10, 10, 10, 13, 13, 13, 13, 13, 13, 13, 13, 13)),
        Queues.NonEeaDesk -> (List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(10, 10, 10, 10, 10, 10, 12, 15, 15, 15, 15, 15, 15, 15, 15, 13, 13, 13, 13, 13, 13, 13, 13, 13))
      )
    ),
    shiftExamples = Seq(
      "Midnight shift, N, {date}, 00:00, 00:59, 10",
      "Night shift, N, {date}, 01:00, 06:59, 4",
      "Morning shift, N, {date}, 07:00, 13:59, 15",
      "Afternoon shift, N, {date}, 14:00, 16:59, 10",
      "Evening shift, N, {date}, 17:00, 23:59, 17"
    ),
    role = LGWAccess,
    terminalPaxTypeQueueAllocation = Map(
      N -> (defaultQueueRatios + (EeaMachineReadable -> List(
        EGate -> 0.8244,
        EeaDesk -> (1.0 - 0.8244)
      ))),
      S -> (defaultQueueRatios + (EeaMachineReadable -> List(
        EGate -> 0.8375,
        EeaDesk -> (1.0 - 0.8375)
      )))),
    feedSources = Seq(LiveBaseFeedSource, LiveFeedSource, ForecastFeedSource, AclFeedSource, ApiFeedSource)
  )
}
