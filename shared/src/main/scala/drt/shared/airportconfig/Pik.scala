package drt.shared.airportconfig

import drt.shared.PaxTypes.{B5JPlusNational, EeaMachineReadable}
import drt.shared.PaxTypesAndQueues._
import drt.shared.Queues.EeaDesk
import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios, SplitSources}
import drt.shared.Terminals.T1
import drt.shared._
import uk.gov.homeoffice.drt.auth.Roles.PIK

import scala.collection.immutable.SortedMap

object Pik extends AirportConfigLike {

  import AirportConfigDefaults._

  val config: AirportConfig = AirportConfig(
    portCode = PortCode("PIK"),
    queuesByTerminal = SortedMap(
      T1 -> Seq(Queues.NonEeaDesk, Queues.EeaDesk)
    ),
    divertedQueues = Map(
      Queues.NonEeaDesk -> Queues.QueueDesk,
      Queues.EeaDesk -> Queues.QueueDesk
    ),
    slaByQueue = Map(
      Queues.EeaDesk -> 25,
      Queues.NonEeaDesk -> 45
    ),
    defaultWalkTimeMillis = Map(T1 -> 780000L),
    terminalPaxSplits = Map(T1 -> SplitRatios(
      SplitSources.TerminalAverage,
      SplitRatio(eeaMachineReadableToDesk, 0.99 * 0.2),
      SplitRatio(eeaNonMachineReadableToDesk, 0),
      SplitRatio(visaNationalToDesk, 0.0),
      SplitRatio(nonVisaNationalToDesk, 0.01)
    )),
    terminalProcessingTimes = Map(T1 -> Map(
      eeaMachineReadableToDesk -> 20d / 60,
      eeaNonMachineReadableToDesk -> 50d / 60,
      visaNationalToDesk -> 100d / 60,
      nonVisaNationalToDesk -> 80d / 60
    )),
    minMaxDesksByTerminalQueue24Hrs = Map(
      T1 -> Map(
        Queues.EeaDesk -> (List(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List(4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4)),
        Queues.NonEeaDesk -> (List(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), List(4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4))
      )
    ),
    eGateBankSizes = Map(T1 -> Iterable()),
    role = PIK,
    terminalPaxTypeQueueAllocation = Map(
      T1 -> (defaultQueueRatios + (
        EeaMachineReadable -> List(EeaDesk -> 1.0),
        B5JPlusNational -> List(Queues.EeaDesk -> 1.0)
      ))),
    flexedQueues = Set(),
    desksByTerminal = Map(T1 -> 5),
    feedSources = Seq(ApiFeedSource, LiveBaseFeedSource, LiveFeedSource, AclFeedSource)
  )
}
