package services.crunch


import controllers.ArrivalGenerator
import drt.shared.CrunchApi.{CrunchMinute, PortState, StaffMinute}
import drt.shared.FlightsApi.{Flights, TerminalName}
import drt.shared.SplitRatiosNs.SplitSources
import drt.shared._
import drt.server.feeds.ArrivalsFeedSuccess
import services.SDate
import services.graphstages.Crunch

import scala.collection.immutable.{Seq, SortedMap}
import scala.concurrent.duration._


class ApplicationRestartSpec extends CrunchTestLike {

  def emptyStaffMinutes(now: () => SDateLike, numDays: Int, terminals: List[TerminalName]): Map[TM, StaffMinute] = {
    val startMillis = Crunch.getLocalLastMidnight(now()).millisSinceEpoch
    val minutesInADay = 1440
    val millisInAMinute = 60 * 1000
    val millisInADay = minutesInADay * millisInAMinute
    val tmStaffMins = for {
      day <- 0 to numDays
      minuteOfDay <- 0 until minutesInADay
      terminal <- terminals
    } yield {
      val minute = startMillis + (day * millisInADay) + (minuteOfDay * millisInAMinute)
      (TM(terminal, minute), StaffMinute(terminal, minute, 0, 0, 0))
    }
    tmStaffMins.toMap
  }

  "Given an initial PortState to restore from " +
    "When I start the crunch graph " +
    "Then I should not see any new crunch data" >> {
    val scheduledLive = "2018-01-01T00:00"
    val scheduledBase = "2018-01-01T00:05"
    val now: () => SDateLike = () => SDate(scheduledLive)

    val arrivalLive = ArrivalGenerator.arrival(actPax = Option(1), iata = "BA1010", schDt = scheduledLive)
    val arrivalBase = ArrivalGenerator.arrival(actPax = Option(1), iata = "BA2010", schDt = scheduledBase)

    val splits = Set(
      Splits(
        Set(ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 1, None)),
        SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages,
        Option(DqEventCodes.DepartureConfirmed),
        PaxNumbers))

    val daysToCrunch = 3

    val portState = PortState(
      flights = Seq(
        ApiFlightWithSplits(arrivalLive, splits),
        ApiFlightWithSplits(arrivalBase, splits)
      ).map(f => (f.apiFlight.uniqueId, f)).toMap,
      crunchMinutes = SortedMap[TQM, CrunchMinute](),
      staffMinutes = SortedMap[TM, StaffMinute]() ++ emptyStaffMinutes(now, daysToCrunch, airportConfig.terminalNames.toList)
    )
    val initialLiveArrivals = Set(arrivalLive)

    val initialBaseArrivals = Set(arrivalBase)
    val crunch = runCrunchGraph(
      now = now,
      initialPortState = Option(portState),
      initialLiveArrivals = initialLiveArrivals,
      initialBaseArrivals = initialBaseArrivals,
      maxDaysToCrunch = daysToCrunch
    )

    offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Flights(Seq(arrivalLive)), SDate.now()))

    crunch.liveTestProbe.expectNoMessage(2 second)

    crunch.liveArrivalsInput.complete()

    success
  }

  "Given an initial PortState to restore from with a flight in day 1 and day 2 " +
    "When I start the crunch graph and send an updated flight for day 1 " +
    "Then I should not see any new crunch data in the forecast" >> {
    val scheduledDay1 = "2018-01-01T00:00"
    val scheduledDay2 = "2018-01-02T00:00"
    val arrivalDay1 = ArrivalGenerator.arrival(actPax = Option(1), iata = "BA1010", schDt = scheduledDay1, feedSources = Set(LiveFeedSource))
    val arrivalDay2 = ArrivalGenerator.arrival(actPax = Option(1), iata = "BA1010", schDt = scheduledDay2, feedSources = Set(LiveFeedSource))
    val splits = Set(
      Splits(
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

    val daysToCrunch = 3

    val now: () => SDateLike = () => SDate(scheduledDay1)

    val portState = PortState(
      flights = Seq(
        ApiFlightWithSplits(arrivalDay1, splits),
        ApiFlightWithSplits(arrivalDay2, splits)
      ).map(f => (f.apiFlight.uniqueId, f)).toMap,
      crunchMinutes = SortedMap[TQM, CrunchMinute]() ++ existingMinutesDay1.toMap ++ existingMinutesDay2.toMap,
      staffMinutes = SortedMap[TM, StaffMinute]() ++ emptyStaffMinutes(now, daysToCrunch, airportConfig.terminalNames.toList)
    )

    val initialLiveArrivals = Set(arrivalDay1, arrivalDay2)
    val crunch = runCrunchGraph(
      now = now,
      initialPortState = Option(portState),
      initialLiveArrivals = initialLiveArrivals,
      maxDaysToCrunch = daysToCrunch
    )

    offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Flights(Seq(arrivalDay1.copy(Estimated = Some(SDate("2018-01-01T00:05").millisSinceEpoch)))), SDate.now()))

    crunch.forecastTestProbe.expectNoMessage(2 second)

    crunch.liveArrivalsInput.complete()

    success
  }
}
