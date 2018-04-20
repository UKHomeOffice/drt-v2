package services.crunch


import controllers.ArrivalGenerator
import drt.shared.CrunchApi.PortState
import drt.shared.FlightsApi.Flights
import drt.shared.SplitRatiosNs.SplitSources
import drt.shared._
import services.SDate

import scala.collection.immutable.Seq
import scala.concurrent.duration._


class ApplicationRestartSpec extends CrunchTestLike {
  "Given an initial PortState to restore from " +
    "When I start the crunch graph " +
    "Then I should not see any new crunch data" >> {
    val scheduledLive = "2018-01-01T00:00"
    val scheduledBase = "2018-01-01T00:05"
    val scheduledFcst = "2018-01-01T00:10"
    val arrivalLive = ArrivalGenerator.apiFlight(actPax = 1, iata = "BA1010", schDt = scheduledLive)
    val arrivalBase = ArrivalGenerator.apiFlight(actPax = 1, iata = "BA2010", schDt = scheduledBase)
    val arrivalFcst = ArrivalGenerator.apiFlight(actPax = 1, iata = "BA3010", schDt = scheduledFcst)
    val splits = Set(
      ApiSplits(
        Set(ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 1, None)),
        SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages,
        Option(DqEventCodes.DepartureConfirmed),
        PaxNumbers))
    val portState = PortState(
      flights = Seq(
        ApiFlightWithSplits(arrivalLive, splits),
        ApiFlightWithSplits(arrivalFcst, splits),
        ApiFlightWithSplits(arrivalBase, splits)
      ).map(f => (f.apiFlight.uniqueId, f)).toMap,
      Map(),
      Map()
    )

    val initialLiveArrivals = Set(arrivalLive)
    val initialFcstArrivals = Set(arrivalFcst)
    val initialBaseArrivals = Set(arrivalBase)

    val crunch = runCrunchGraph(
      now = () => SDate(scheduledLive),
      initialPortState = Option(portState),
      initialLiveArrivals = initialLiveArrivals,
      initialForecastArrivals = initialFcstArrivals,
      initialBaseArrivals = initialBaseArrivals
    )

    offerAndWait(crunch.liveArrivalsInput, Flights(Seq(arrivalLive)))

    crunch.liveTestProbe.expectNoMsg(5 seconds)

    true
  }
}