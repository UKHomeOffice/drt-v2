package services.crunch

import controllers.ArrivalGenerator
import drt.shared.CrunchApi.PortState
import drt.shared.FlightsApi.Flights
import drt.shared.SplitRatiosNs.SplitSources
import drt.shared._
import server.feeds.ArrivalsFeedSuccess
import services.SDate
import services.graphstages.Crunch.{PortStateDiff, RemoveFlight}

import scala.collection.immutable.{List, Seq}
import scala.concurrent.duration._


class AggregateArrivalsSpec extends CrunchTestLike {
  "Given a live arrival " +
    "When I inspect the message received by the aggregated arrivals actor " +
    "Then I should see no removals and one update " >> {

    val scheduled = "2017-01-01T00:00Z"

    val liveArrival = ArrivalGenerator.apiFlight(schDt = scheduled, iata = "BA0001", terminal = "T1", actPax = Option(21))
    val liveFlights = Flights(List(liveArrival))

    val crunch = runCrunchGraph(now = () => SDate(scheduled))

    offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(liveFlights))

    val psDiff = crunch.aggregatedArrivalsTestProbe.receiveOne(1 seconds) match {
      case psd: PortStateDiff => psd
    }

    psDiff.flightRemovals === Set() && psDiff.flightUpdates.map(_.apiFlight) === Set(liveArrival)
  }

  "Given an existing arrival which is due to expire and a new live arrival " +
    "When I inspect the message received by the aggregated arrivals actor " +
    "Then I should not see a removal as the arrival is in the past " >> {

    val scheduledExpired = "2017-01-05T00:00Z"
    val scheduled = "2017-01-05T00:01Z"

    val expiredArrival = ArrivalGenerator.apiFlight(schDt = scheduledExpired, iata = "BA0022", terminal = "T1", actPax = Option(21))
    val liveArrival = ArrivalGenerator.apiFlight(schDt = scheduled, iata = "BA0001", terminal = "T1", actPax = Option(21))
    val liveFlights = Flights(List(liveArrival))

    val oldSplits = Splits(Set(ApiPaxTypeAndQueueCount(PaxTypes.VisaNational, Queues.NonEeaDesk, 100, None)), SplitSources.Historical, None, Percentage)
    val initialFlightsWithSplits = Seq(ApiFlightWithSplits(expiredArrival, Set(oldSplits), None))
    val initialPortState = PortState(initialFlightsWithSplits.map(f => (f.apiFlight.uniqueId, f)).toMap, Map(), Map())

    val crunch = runCrunchGraph(
      initialBaseArrivals = Set(expiredArrival),
      initialPortState = Option(initialPortState),
      now = () => SDate(scheduled),
      expireAfterMillis = 250
    )

    offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(liveFlights))

    val psDiff = crunch.aggregatedArrivalsTestProbe.receiveOne(1 seconds) match {
      case psd: PortStateDiff => psd
    }

    psDiff.flightRemovals === Set() && psDiff.flightUpdates.map(_.apiFlight) === Set(liveArrival)
  }

  "Given an existing future base arrival followed by an empty list of base arrivals " +
    "When I inspect the messages received by the aggregated arrivals actor " +
    "Then I should see a removal in the second message of the flight no longer scheduled" >> {

    val scheduledDescheduled = "2017-01-10T00:00Z"
    val scheduled = "2017-01-05T00:00Z"

    val descheduledArrival = ArrivalGenerator.apiFlight(schDt = scheduledDescheduled, iata = "BA0022", terminal = "T1", actPax = Option(21))
    val liveArrival = ArrivalGenerator.apiFlight(schDt = scheduled, iata = "BA0001", terminal = "T1", actPax = Option(21))
    val liveFlights = Flights(List(liveArrival))

    val oldSplits = Splits(Set(ApiPaxTypeAndQueueCount(PaxTypes.VisaNational, Queues.NonEeaDesk, 100, None)), SplitSources.Historical, None, Percentage)
    val initialFlightsWithSplits = Seq(ApiFlightWithSplits(descheduledArrival, Set(oldSplits), None))
    val initialPortState = PortState(initialFlightsWithSplits.map(f => (f.apiFlight.uniqueId, f)).toMap, Map(), Map())

    val crunch = runCrunchGraph(
      initialBaseArrivals = Set(descheduledArrival),
      initialPortState = Option(initialPortState),
      now = () => SDate(scheduled),
      expireAfterMillis = 250
    )

    offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(liveFlights))

    val psDiff1 = crunch.aggregatedArrivalsTestProbe.receiveOne(1 seconds) match {
      case psd: PortStateDiff => psd
    }

    offerAndWait(crunch.baseArrivalsInput, ArrivalsFeedSuccess(Flights(List())))

    val psDiff2 = crunch.aggregatedArrivalsTestProbe.receiveOne(1 seconds) match {
      case psd: PortStateDiff => psd
    }

    psDiff1.flightRemovals === Set() && psDiff1.flightUpdates.map(_.apiFlight) === Set(liveArrival) &&
      psDiff2.flightRemovals === Set(RemoveFlight(descheduledArrival.uniqueArrival)) && psDiff2.flightUpdates === Set()
  }

}