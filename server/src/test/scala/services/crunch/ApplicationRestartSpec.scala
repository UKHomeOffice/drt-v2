package services.crunch


import controllers.ArrivalGenerator
import drt.shared.CrunchApi.{CrunchMinute, PortState}
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

    crunch.liveTestProbe.expectNoMsg(250 milliseconds)

    true
  }

  "Given an initial PortState to restore from with a flight in day 1 and day 2 " +
    "When I start the crunch graph and send an updated flight for day 1 " +
    "Then I should not see any new crunch data in the forecast" >> {
    val scheduledDay1 = "2018-01-01T00:00"
    val scheduledDay2 = "2018-01-02T00:00"
    val arrivalDay1 = ArrivalGenerator.apiFlight(actPax = 1, iata = "BA1010", schDt = scheduledDay1)
    val arrivalDay2 = ArrivalGenerator.apiFlight(actPax = 1, iata = "BA1010", schDt = scheduledDay2)
    val splits = Set(
      ApiSplits(
        Set(ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 1, None)),
        SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages,
        Option(DqEventCodes.DepartureConfirmed),
        PaxNumbers))


    val firstMinuteDay1 = SDate(scheduledDay1)
    val terminalName = "T1"
    val existingMinutesDay1 = (firstMinuteDay1.millisSinceEpoch to firstMinuteDay1.addMinutes(59).millisSinceEpoch by oneMinuteMillis)
      .map(m => {
        val cm = if (m == firstMinuteDay1.millisSinceEpoch) CrunchMinute(terminalName, Queues.EeaDesk, m, 1.0, 0.4166666666666667, 1, 25, None, None, None, None, None)
        else CrunchMinute(terminalName, Queues.EeaDesk, m, 0.0, 0.0, 1, 25, None, None, None, None, None)
        (cm.key, cm)
      })
    val firstMinuteDay2 = SDate(scheduledDay2)
    val existingMinutesDay2 = (firstMinuteDay2.millisSinceEpoch to firstMinuteDay2.addMinutes(59).millisSinceEpoch by oneMinuteMillis)
      .map(m => {
        val cm = if (m == firstMinuteDay2.millisSinceEpoch) CrunchMinute(terminalName, Queues.EeaDesk, m, 1.0, 0.4166666666666667, 1, 25, None, None, None, None, None)
        else CrunchMinute(terminalName, Queues.EeaDesk, m, 0.0, 0.0, 1, 25, None, None, None, None, None)
        (cm.key, cm)
      })

    val portState = PortState(
      flights = Seq(
        ApiFlightWithSplits(arrivalDay1, splits),
        ApiFlightWithSplits(arrivalDay2, splits)
      ).map(f => (f.apiFlight.uniqueId, f)).toMap,
      existingMinutesDay1.toMap ++ existingMinutesDay2.toMap,
      Map()
    )

    val initialLiveArrivals = Set(arrivalDay1, arrivalDay2)

    val crunch = runCrunchGraph(
      now = () => SDate(scheduledDay1),
      initialPortState = Option(portState),
      initialLiveArrivals = initialLiveArrivals
    )

    offerAndWait(crunch.liveArrivalsInput, Flights(Seq(arrivalDay1.copy(Estimated = SDate("2018-01-01T00:05").millisSinceEpoch))))

    crunch.forecastTestProbe.expectNoMsg(250 milliseconds)

    true
  }
}