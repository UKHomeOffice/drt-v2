package drt.shared

import controllers.ArrivalGenerator
import drt.shared.PaxTypes._
import drt.shared.SplitRatiosNs.SplitSources
import drt.shared.SplitRatiosNs.SplitSources.{ApiSplitsWithHistoricalEGateAndFTPercentages, PredictedSplitsWithHistoricalEGateAndFTPercentages}
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


  "flight Arrival" should {
    val apiFlightWithSplits = new ApiFlightWithSplits(flight, Set(splitsWithinFivePercentageThreshold))

    "has valid Api when api splits sum pax count is within 5% Threshold of LiveSourceFeed pax count" in {
      val apiSplits = apiFlightWithSplits.splits.find(s => s.source == SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages).get
      apiFlightWithSplits.isWithinThreshold(apiSplits) mustEqual true
      apiFlightWithSplits.hasValidApi mustEqual true
    }

    "has not valid Api when api splits sum pax count is not within 5% Threshold of LiveSourceFeed pax count" in {
      val flightWithSplits = new ApiFlightWithSplits(flight, Set(splitsNotWithinFivePercentageThreshold))
      val apiSplits = flightWithSplits.splits.find(s => s.source == SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages).get
      flightWithSplits.isWithinThreshold(apiSplits) mustEqual false
      flightWithSplits.hasValidApi mustEqual false
    }

    "has not valid Api splits when source is not ApiSplitsWithHistoricalEGateAndFTPercentages" in {
      val flightWithSplits = new ApiFlightWithSplits(flight, Set(splitsNotWithinFivePercentageThreshold.copy(source = PredictedSplitsWithHistoricalEGateAndFTPercentages)))
      flightWithSplits.hasValidApi mustEqual false
    }

    "has valid Api splits when source is not LiveFeedSource" in {
      val flightWithSplits = new ApiFlightWithSplits(flight.copy(FeedSources=Set(ForecastFeedSource)), Set(splitsNotWithinFivePercentageThreshold))
      flightWithSplits.hasValidApi mustEqual true
    }

  }

}
