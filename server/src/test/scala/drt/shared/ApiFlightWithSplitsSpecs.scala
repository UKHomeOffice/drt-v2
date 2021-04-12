package drt.shared

import controllers.ArrivalGenerator
import drt.shared.PaxTypes._
import drt.shared.SplitRatiosNs.SplitSources
import drt.shared.SplitRatiosNs.SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages
import drt.shared.Terminals.T1
import drt.shared.api.Arrival
import org.specs2.mutable.Specification

class ApiFlightWithSplitsSpecs extends Specification {

  val flight: Arrival = ArrivalGenerator.arrival(
    iata = "TST100",
    actPax = Option(40),
    tranPax = Option(0),
    schDt = "2020-06-17T05:30:00Z",
    terminal = T1,
    airportId = PortCode("ID"),
    status = ArrivalStatus("Scheduled"),
    feedSources = Set(LiveFeedSource),
    pcpDt = "2020-06-17T06:30:00Z"
  )
  val splitsWithinFivePercentageThreshold = Splits(Set(
    ApiPaxTypeAndQueueCount(B5JPlusNational, Queues.EeaDesk, 2.0, None, None),
    ApiPaxTypeAndQueueCount(B5JPlusNational, Queues.EGate, 4.0, None, None),
    ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 3.0, None, None),
    ApiPaxTypeAndQueueCount(EeaNonMachineReadable, Queues.EeaDesk, 4.0, None, None),
    ApiPaxTypeAndQueueCount(NonVisaNational, Queues.NonEeaDesk, 5.0, None, None),
    ApiPaxTypeAndQueueCount(VisaNational, Queues.NonEeaDesk, 6.0, None, None),
    ApiPaxTypeAndQueueCount(Transit, Queues.Transfer, 7.0, None, None),
    ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EGate, 8.0, None, None)
  ), ApiSplitsWithHistoricalEGateAndFTPercentages, Option(EventTypes.DC))

  val splitsNotWithinFivePercentageThreshold = Splits(Set(
    ApiPaxTypeAndQueueCount(B5JPlusNational, Queues.EeaDesk, 1.0, None, None),
    ApiPaxTypeAndQueueCount(B5JPlusNational, Queues.EGate, 2.0, None, None),
    ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 3.0, None, None),
    ApiPaxTypeAndQueueCount(EeaNonMachineReadable, Queues.EeaDesk, 4.0, None, None),
    ApiPaxTypeAndQueueCount(NonVisaNational, Queues.NonEeaDesk, 5.0, None, None),
    ApiPaxTypeAndQueueCount(VisaNational, Queues.NonEeaDesk, 6.0, None, None),
    ApiPaxTypeAndQueueCount(Transit, Queues.Transfer, 7.0, None, None),
    ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EGate, 8.0, None, None)
  ), ApiSplitsWithHistoricalEGateAndFTPercentages, Option(EventTypes.DC))
}
