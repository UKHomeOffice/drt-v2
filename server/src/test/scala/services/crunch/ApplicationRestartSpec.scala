package services.crunch


import controllers.ArrivalGenerator
import drt.shared.CrunchApi.{CrunchMinute, StaffMinute}
import drt.shared.FlightsApi.Flights
import drt.shared.SplitRatiosNs.SplitSources
import drt.shared.Terminals.Terminal
import drt.shared._
import server.feeds.ArrivalsFeedSuccess
import services.SDate
import services.graphstages.Crunch

import scala.collection.immutable.{Seq, SortedMap}
import scala.collection.mutable
import scala.concurrent.duration._


class ApplicationRestartSpec extends CrunchTestLike {

  def emptyStaffMinutes(now: () => SDateLike, numDays: Int, terminals: List[Terminal]): Map[TM, StaffMinute] = {
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

    val arrivalLive = ArrivalGenerator.arrival(iata = "BA1010", schDt = scheduledLive, actPax = Option(1))
    val arrivalBase = ArrivalGenerator.arrival(iata = "BA2010", schDt = scheduledBase, actPax = Option(1))

    val splits = Set(
      Splits(
        Set(ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 1, None)),
        SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages,
        Option(EventTypes.DC),
        PaxNumbers))

    val daysToCrunch = 3

    val portState = PortState(
      flights = SortedMap[UniqueArrival, ApiFlightWithSplits]() ++ Seq(
        ApiFlightWithSplits(arrivalLive, splits),
        ApiFlightWithSplits(arrivalBase, splits)
      ).map(f => (f.apiFlight.unique, f)),
      crunchMinutes = SortedMap[TQM, CrunchMinute](),
      staffMinutes = SortedMap[TM, StaffMinute]() ++ emptyStaffMinutes(now, daysToCrunch, airportConfig.terminals.toList)
    )
    val initialLiveArrivals = mutable.SortedMap[UniqueArrival, Arrival](arrivalLive.unique -> arrivalLive)

    val initialBaseArrivals = mutable.SortedMap[UniqueArrival, Arrival](arrivalBase.unique -> arrivalBase)
    val crunch = runCrunchGraph(
      now = now,
      initialPortState = Option(portState),
      initialLiveArrivals = initialLiveArrivals,
      initialForecastBaseArrivals = initialBaseArrivals,
      maxDaysToCrunch = daysToCrunch
    )

    offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Flights(Seq(arrivalLive)), SDate.now()))

    crunch.portStateTestProbe.expectNoMessage(2 second)

    crunch.shutdown

    success
  }
}
