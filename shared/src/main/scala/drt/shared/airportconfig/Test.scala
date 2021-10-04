package drt.shared.airportconfig

import uk.gov.homeoffice.drt.auth.Roles.TEST
import uk.gov.homeoffice.drt.ports.PaxTypes.EeaMachineReadable
import uk.gov.homeoffice.drt.ports.PaxTypesAndQueues._
import uk.gov.homeoffice.drt.ports.Queues.{EGate, EeaDesk, NonEeaDesk}
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.{SplitRatio, SplitRatios, SplitSources}
import uk.gov.homeoffice.drt.ports.Terminals.{T1, Terminal}
import uk.gov.homeoffice.drt.ports.config.AirportConfigDefaults.defaultQueueRatios
import uk.gov.homeoffice.drt.ports._

import scala.collection.immutable.SortedMap

object Test extends AirportConfigLike {

  val config: AirportConfig = AirportConfig(
    portCode = PortCode("TEST"),
    queuesByTerminal = SortedMap(
      T1 -> Seq(EeaDesk, EGate, NonEeaDesk)
    ),
    slaByQueue = Map(EeaDesk -> 25, EGate -> 5, NonEeaDesk -> 45),
    crunchOffsetMinutes = 240,
    defaultWalkTimeMillis = Map(T1 -> 600000L),
    terminalPaxSplits = Map[Terminal, SplitRatios](T1 -> SplitRatios(
      SplitSources.TerminalAverage,
      SplitRatio(eeaMachineReadableToDesk, 0.7425),
      SplitRatio(eeaMachineReadableToEGate, 0.2475),
      SplitRatio(eeaNonMachineReadableToDesk, 0.0),
      SplitRatio(visaNationalToDesk, 0.0),
      SplitRatio(nonVisaNationalToDesk, 0.01)
    )),
    terminalProcessingTimes = Map(T1 -> Map(
      eeaMachineReadableToDesk -> 20d / 60,
      eeaMachineReadableToEGate -> 35d / 60,
      eeaNonMachineReadableToDesk -> 50d / 60,
      visaNationalToDesk -> 90d / 60,
      nonVisaNationalToDesk -> 78d / 60
    )),
    minMaxDesksByTerminalQueue24Hrs = Map(
      T1 -> Map(
        Queues.EGate -> (List(0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(3, 3, 1, 1, 1, 1, 1, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3)),
        Queues.EeaDesk -> (List(0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13)),
        Queues.NonEeaDesk -> (List(0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8))
      )
    ),
    eGateBankSizes = Map(T1 -> Iterable(10, 10, 10)),
    role = TEST,
    terminalPaxTypeQueueAllocation = Map(
      T1 -> (defaultQueueRatios + (EeaMachineReadable -> List(
        EGate -> 0.7968,
        EeaDesk -> (1.0 - 0.7968)
      )))
    ),
    hasTransfer = true,
    desksByTerminal = Map(T1 -> 22),
    feedSources = Seq(ApiFeedSource, LiveBaseFeedSource, LiveFeedSource, AclFeedSource)
  )
}
