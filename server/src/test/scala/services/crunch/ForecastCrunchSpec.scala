package services.crunch

import akka.actor.{Actor, Props}
import controllers.ArrivalGenerator
import drt.server.feeds.ArrivalsFeedSuccess
import drt.shared.CrunchApi.{CrunchMinute, MillisSinceEpoch, StaffMinute}
import drt.shared.FlightsApi.Flights
import drt.shared._
import services.OptimiserWithFlexibleProcessors
import services.graphstages.CrunchMocks
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.{T1, Terminal}
import uk.gov.homeoffice.drt.ports.{AclFeedSource, ForecastFeedSource, LiveFeedSource, Queues}
import uk.gov.homeoffice.drt.time.SDate

import scala.collection.immutable.{List, Seq, SortedMap}
import scala.concurrent.Future
import scala.concurrent.duration._


class ForecastCrunchSpec extends CrunchTestLike {
  sequential
  isolated
  stopOnFail


  "Given a live flight and a base flight arriving 3 days later " +
    "When I ask for pax loads " +
    "Then I should see pax arriving in the 1st and 2nd minute for the live flight, and the 1st & 2nd minute 3 days later for the base flight" >> {

    val scheduled = "2017-01-01T00:00Z"
    val base = "2017-01-04T00:00Z"

    val liveArrival = ArrivalGenerator.arrival(schDt = scheduled, iata = "BA0001", terminal = T1, totalPax = Map(LiveFeedSource -> Passengers(Option(21), None)))
    val liveFlights = Flights(List(liveArrival))
    val baseArrival = ArrivalGenerator.arrival(schDt = base, iata = "BA0001", terminal = T1, totalPax = Map(AclFeedSource -> Passengers(Option(21), None)))
    val baseFlights = Flights(List(baseArrival))

    val crunch = runCrunchGraph(TestConfig(now = () => SDate(scheduled), maxDaysToCrunch = 4, cruncher = CrunchMocks.mockCrunchWholePax))

    offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(liveFlights))
    offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(baseFlights))

    val expectedForecast = Map(
      SDate(base).toISOString -> 20,
      SDate(base).addMinutes(1).toISOString -> 1,
      SDate(scheduled).toISOString -> 20,
      SDate(scheduled).addMinutes(1).toISOString -> 1
    )

    crunch.portStateTestProbe.fishForMessage(10.seconds) {
      case PortState(_, cms, _) =>
        val forecastSummary = interestingPaxLoads(cms)
        forecastSummary == expectedForecast
    }

    success
  }

  "Given a flight with pcp times just before midnight " +
    "When I crunch  " +
    "Then I should see a wait time at midnight of one minute higher than the wait time at 23:59, ie the pax before midnight did not get forgotten " >> {
    val scheduled = "2017-01-01T00:00Z"
    val beforeMidnight = "2017-01-03T23:55Z"
    val afterMidnight = "2017-01-04T00:15Z"

    val baseArrivals = List(
      ArrivalGenerator.arrival(schDt = beforeMidnight, iata = "BA0001", terminal = T1, totalPax = Map(AclFeedSource -> Passengers(Option(20), None))),
      ArrivalGenerator.arrival(schDt = afterMidnight, iata = "BA0002", terminal = T1, totalPax = Map(AclFeedSource -> Passengers(Option(20), None)))
    )
    val baseFlights = Flights(baseArrivals)

    val startDate1 = SDate("2017-01-04T00:00").millisSinceEpoch
    val endDate1 = SDate("2017-01-04T00:14").millisSinceEpoch
    val assignment1 = StaffAssignment("shift s", T1, startDate1, endDate1, 1, None)
    val startDate2 = SDate("2017-01-04T00:15").millisSinceEpoch
    val endDate2 = SDate("2017-01-04T00:29").millisSinceEpoch
    val assignment2 = StaffAssignment("shift s", T1, startDate2, endDate2, 2, None)

    val crunch = runCrunchGraph(TestConfig(
      airportConfig = defaultAirportConfig.copy(
        crunchOffsetMinutes = 240,
        queuesByTerminal = SortedMap(T1 -> Seq(Queues.EeaDesk)),
        minutesToCrunch = 1440
      ),
      now = () => SDate(scheduled),
      initialShifts = ShiftAssignments(Seq(assignment1, assignment2)),
      cruncher = OptimiserWithFlexibleProcessors.crunchWholePax,
      maxDaysToCrunch = 4
    ))

    offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(baseFlights))

    crunch.portStateTestProbe.fishForMessage(10.seconds) {
      case ps: PortState =>
        val firstInterestingMilli = SDate("2017-01-03T23:59Z").millisSinceEpoch
        val interestingMinuteMillis = firstInterestingMilli to firstInterestingMilli + oneMinuteMillis
        val waitTimesBeforeAndAtMidnight = ps
          .crunchMinutes
          .values
          .filter(cm => interestingMinuteMillis.contains(cm.minute))
          .toList.sortBy(_.minute)
          .map(_.waitTime)

        val waitTimeOneMinuteBeforeMidnight = waitTimesBeforeAndAtMidnight.headOption.getOrElse(0)
        val waitTimeAtMidnight = waitTimesBeforeAndAtMidnight.drop(1).headOption.getOrElse(0)

        waitTimeOneMinuteBeforeMidnight < waitTimeAtMidnight
    }

    success
  }

  "Given a base flight with 21 pax" +
    "When I ask for pax loads " +
    "Then I should see 20 pax in the 1st minute and 1 in the second" >> {

    val today = "2017-01-01T00:00Z"
    val baseScheduled = "2017-01-02T00:00Z"

    val baseArrival = ArrivalGenerator.arrival(schDt = baseScheduled, iata = "BA0001", terminal = T1, totalPax = Map(AclFeedSource -> Passengers(Option(21), None)))
    val baseFlights = Flights(List(baseArrival))

    val crunch = runCrunchGraph(TestConfig(now = () => SDate(today)))

    offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(baseFlights))

    val expectedForecast = Map(
      SDate(baseScheduled).toISOString -> 20,
      SDate(baseScheduled).addMinutes(1).toISOString -> 1)

    crunch.portStateTestProbe.fishForMessage(10.seconds) {
      case ps: PortState =>
        val forecastSummary = interestingPaxLoads(ps.crunchMinutes)
        forecastSummary == expectedForecast
    }

    success
  }

  "Given a forecast arrival with no matching base arrival " +
    "When I ask for arrivals " +
    "Then I should not see any arrivals" >> {

    val forecastScheduled = "2017-01-01T00:01Z"

    val forecastArrival = ArrivalGenerator.arrival(schDt = forecastScheduled, iata = "BA0001", terminal = T1, totalPax = Map(ForecastFeedSource -> Passengers(Option(21), None)))
    val forecastArrivals = Flights(List(forecastArrival))

    val crunch = runCrunchGraph(TestConfig(now = () => SDate(forecastScheduled).addDays(-1)))

    offerAndWait(crunch.forecastArrivalsInput, ArrivalsFeedSuccess(forecastArrivals))

    val gotAnyFlights = crunch.portStateTestProbe.receiveWhile(2.seconds) {
      case PortState(flights, _, _) => flights.size
    }.exists(_ > 0)

    gotAnyFlights === false
  }

  "Given a base arrival with 21 pax, and a forecast arrival scheduled 1 minute later " +
    "When I ask for arrivals " +
    "Then I should only see the base arrival as forecast arrival should be ignored when there's no matching base arrival" >> {

    val baseScheduled = "2017-01-01T00:00Z"
    val forecastScheduled = "2017-01-01T00:01Z"

    val baseArrival = ArrivalGenerator.arrival(schDt = baseScheduled, iata = "BA0001", terminal = T1, totalPax = Map(AclFeedSource -> Passengers(Option(21), None)))
    val forecastArrival = ArrivalGenerator.arrival(schDt = forecastScheduled, iata = "BA0001", terminal = T1, totalPax = Map(ForecastFeedSource -> Passengers(Option(21), None)))
    val baseArrivals = Flights(List(baseArrival))
    val forecastArrivals = Flights(List(forecastArrival))

    val crunch = runCrunchGraph(TestConfig(now = () => SDate(baseScheduled).addDays(-1)))

    offerAndWait(crunch.forecastArrivalsInput, ArrivalsFeedSuccess(forecastArrivals))
    offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(baseArrivals))

    val expectedForecastArrivals = Set(baseArrival.copy(FeedSources = Set(AclFeedSource),
      PassengerSources = baseArrival.PassengerSources))

    crunch.portStateTestProbe.fishForMessage(10.seconds) {
      case ps: PortState =>
        val crunchForecastArrivals = ps.flights.values.map(_.apiFlight).toSet
        crunchForecastArrivals == expectedForecastArrivals
    }

    success
  }

  "Given a base arrival with 21 pax, and a matching forecast arrival with 50 pax, 25 trans pax and a different format flight code " +
    "When I ask for arrivals " +
    "Then I should see the base arrival with the forecast's pax & transpax nos overlaid, but retaining the base's flight code" >> {

    val baseScheduled = "2017-01-01T00:00Z"
    val forecastScheduled = baseScheduled

    val baseArrival = ArrivalGenerator.arrival(schDt = baseScheduled, iata = "BA0001", terminal = T1, totalPax = Map(AclFeedSource -> Passengers(Option(21), None)))
    val forecastArrival = ArrivalGenerator.arrival(schDt = forecastScheduled, iata = "BAW0001", terminal = T1, totalPax = Map(ForecastFeedSource -> Passengers(Option(50), Option(25))))
    val baseArrivals = Flights(List(baseArrival))
    val forecastArrivals = Flights(List(forecastArrival))

    val crunch = runCrunchGraph(TestConfig(now = () => SDate(baseScheduled).addDays(-1)))

    offerAndWait(crunch.forecastArrivalsInput, ArrivalsFeedSuccess(forecastArrivals))
    offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(baseArrivals))

    val expectedForecastArrivals = Set(baseArrival.copy(
      FeedSources = Set(ForecastFeedSource, AclFeedSource),
      PassengerSources = baseArrival.PassengerSources ++ forecastArrival.PassengerSources))

    crunch.portStateTestProbe.fishForMessage(2.seconds) {
      case ps: PortState =>
        val crunchForecastArrivals = ps.flights.values.map(_.apiFlight).toSet
        crunchForecastArrivals == expectedForecastArrivals
    }

    success
  }

  "Given a base arrival with 21 pax, followed by a matching forecast arrival with 50 pax, and finally a live flight with zero pax " +
    "When I ask for arrivals " +
    "Then I should see the base arrival details with the forecast pax" >> {

    val baseScheduled = "2017-01-01T00:00Z"
    val forecastScheduled = baseScheduled
    val liveScheduled = baseScheduled

    val baseArrival = ArrivalGenerator.arrival(schDt = baseScheduled, iata = "BA0001", terminal = T1, totalPax = Map(AclFeedSource -> Passengers(Option(21), None)))
    val forecastArrival = ArrivalGenerator.arrival(schDt = forecastScheduled, iata = "BAW0001", terminal = T1, totalPax = Map(ForecastFeedSource -> Passengers(Option(50), Option(25))))
    val liveArrival = ArrivalGenerator.arrival(schDt = liveScheduled, iata = "BA0001", terminal = T1, estDt = liveScheduled, totalPax = Map(LiveFeedSource -> Passengers(None, None)))
    val baseArrivals = Flights(List(baseArrival))
    val forecastArrivals = Flights(List(forecastArrival))
    val liveArrivals = Flights(List(liveArrival))

    val crunch = runCrunchGraph(TestConfig(now = () => SDate(baseScheduled).addDays(-1)))

    offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(baseArrivals))
    offerAndWait(crunch.forecastArrivalsInput, ArrivalsFeedSuccess(forecastArrivals))
    offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(liveArrivals))

    val expectedForecastArrivals = Set(baseArrival.copy(Estimated = Some(SDate(liveScheduled).millisSinceEpoch),
      FeedSources = Set(AclFeedSource, ForecastFeedSource, LiveFeedSource),
      PassengerSources = baseArrival.PassengerSources ++ forecastArrival.PassengerSources ++ liveArrival.PassengerSources
    ))


    crunch.portStateTestProbe.fishForMessage(2.seconds) {
      case ps: PortState =>
        val crunchForecastArrivals = ps.flights.values.map(_.apiFlight).toSet
        crunchForecastArrivals == expectedForecastArrivals
    }

    success
  }

  "Given 2 base arrivals followed by 1 matching forecast arrival, and then the other matching forecast arrival " +
    "When I ask for arrivals " +
    "Then I should see the forecast pax & status details for both arrivals" >> {

    val baseScheduled = "2017-01-01T00:00Z"
    val forecastScheduled = baseScheduled

    val baseArrival1 = ArrivalGenerator.arrival(schDt = baseScheduled, iata = "BA0001", terminal = T1, totalPax = Map(AclFeedSource -> Passengers(Option(21), None)), status = ArrivalStatus("ACL Forecast"))
    val baseArrival2 = ArrivalGenerator.arrival(schDt = baseScheduled, iata = "AA1110", terminal = T1, totalPax = Map(AclFeedSource -> Passengers(Option(22), None)), status = ArrivalStatus("ACL Forecast"))
    val forecastArrival1 = ArrivalGenerator.arrival(schDt = forecastScheduled, iata = "BAW0001", terminal = T1, totalPax = Map(ForecastFeedSource -> Passengers(Option(51), None)), status = ArrivalStatus("Port Forecast"))
    val forecastArrival2 = ArrivalGenerator.arrival(schDt = forecastScheduled, iata = "AAW1110", terminal = T1, totalPax = Map(ForecastFeedSource -> Passengers(Option(52), None)), status = ArrivalStatus("Port Forecast"))
    val baseArrivals = Flights(List(baseArrival1, baseArrival2))
    val forecastArrivals1st = Flights(List(forecastArrival1))
    val forecastArrivals2nd = Flights(List(forecastArrival2))

    val crunch = runCrunchGraph(TestConfig(now = () => SDate(baseScheduled).addDays(-1)))

    offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(baseArrivals))
    crunch.portStateTestProbe.receiveOne(2.seconds)
    offerAndWait(crunch.forecastArrivalsInput, ArrivalsFeedSuccess(forecastArrivals1st))
    crunch.portStateTestProbe.receiveOne(2.seconds)
    offerAndWait(crunch.forecastArrivalsInput, ArrivalsFeedSuccess(forecastArrivals2nd))

    val expectedForecastArrivals = Set(
      baseArrival1.copy(Status = ArrivalStatus("Port Forecast"),
        FeedSources = Set(ForecastFeedSource, AclFeedSource),
        PassengerSources = baseArrival1.PassengerSources ++ forecastArrival1.PassengerSources),
      baseArrival2.copy(Status = ArrivalStatus("Port Forecast"),
        FeedSources = Set(ForecastFeedSource, AclFeedSource),
        PassengerSources = baseArrival2.PassengerSources ++ forecastArrival2.PassengerSources
      ))

    crunch.portStateTestProbe.fishForMessage(2.seconds) {
      case ps: PortState =>
        ps.flights.values.map(_.apiFlight).toSet == expectedForecastArrivals
    }

    success
  }

  "Given an initial base arrivals of 1 flight, and and initial merged arrivals of 2 flights (from a port state that hadn't been updated) " +
    "When I send an updated base arrivals " +
    "Then I should only see arrivals that exist in the latest base arrivals" >> {

    val scheduled = "2017-01-01T00:00Z"
    val base = "2017-01-04T00:00Z"

    val initialBaseArrivals = SortedMap[UniqueArrival, Arrival]() ++ List(ArrivalGenerator.arrival(schDt = base, iata = "BA0001", terminal = T1,
      totalPax = Map(AclFeedSource -> Passengers(Option(21), None)))).map(a => (a.unique, a))
    val initialPortStateArrivals = Seq(
      ArrivalGenerator.arrival(schDt = base, iata = "FR0001", terminal = T1, totalPax = Map(ForecastFeedSource -> Passengers(Option(101), None))),
      ArrivalGenerator.arrival(schDt = base, iata = "EZ1100", terminal = T1, totalPax = Map(ForecastFeedSource -> Passengers(Option(250), None)))
    ).map(a => (a.unique, ApiFlightWithSplits(a, Set())))

    val updatedBaseArrivals = SortedMap[UniqueArrival, Arrival]() ++ List(ArrivalGenerator.arrival(schDt = base, iata = "AA0099", terminal = T1,
      totalPax = Map(ForecastFeedSource -> Passengers(Option(55), None)))).map(a => (a.unique, a))

    val crunch = runCrunchGraph(TestConfig(
      now = () => SDate(scheduled),
      initialForecastBaseArrivals = initialBaseArrivals,
      initialPortState = Option(PortState(SortedMap[UniqueArrival, ApiFlightWithSplits]() ++ initialPortStateArrivals, SortedMap[TQM, CrunchMinute](), SortedMap[TM, StaffMinute]())),
      maxDaysToCrunch = 4
    ))

    offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(Flights(updatedBaseArrivals.values.toSeq)))

    val expectedFlightCodes = updatedBaseArrivals.values.map(_.flightCodeString)

    crunch.portStateTestProbe.fishForMessage(2.seconds) {
      case PortState(flightsWithSplits, _, _) =>
        val flightCodes = flightsWithSplits.values.map(_.apiFlight.flightCodeString)
        flightCodes == expectedFlightCodes
    }

    success
  }

  "Given no initial arrivals " +
    "When I send an updated base arrivals with a matching passenger delta of 10" +
    "Then I should see the arrival in the port state with 90 passengers" >> {

    val scheduled = "2017-01-01T00:00Z"

    val aclPax = 100
    val paxDelta = 0.1

    val arrival = ArrivalGenerator.arrival(schDt = scheduled, iata = "BA0001", terminal = T1, totalPax = Map(AclFeedSource -> Passengers(Option(aclPax), None)))
    val baseArrivals = List(arrival)

    val crunch = runCrunchGraph(TestConfig(
      now = () => SDate(scheduled),
      passengerAdjustments = arrivals => Future.successful(arrivals
        .map(a => a.copy(PassengerSources = a.PassengerSources.map(pax => pax._1 -> Passengers(pax._2.actual.map(at => (at * paxDelta).toInt), pax._2.transit.map(t => (t * paxDelta).toInt))))
        ))))

    offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(Flights(baseArrivals)))

    val expectedActPax = Option(aclPax * paxDelta)

    crunch.portStateTestProbe.fishForMessage(2.seconds) {
      case PortState(flightsWithSplits, _, _) =>
        if (flightsWithSplits.nonEmpty) {
          val actPax = flightsWithSplits.values.head.apiFlight.bestPaxEstimate.passengers.actual
          println(s"actPax: $actPax, expectedActPax: $expectedActPax")
          actPax == expectedActPax
        } else false
    }

    success
  }

  def interestingPaxLoads(cms: Map[TQM, CrunchApi.CrunchMinute]): Map[String, Double] = {
    cms.values.filter(cm => cm.paxLoad != 0).map(cm => (SDate(cm.minute).toISOString, cm.paxLoad)).toMap
  }

  def interestingDeployments(cms: Map[TQM, CrunchApi.CrunchMinute]): scala.Seq[(MillisSinceEpoch, Terminal, Queue, Option[Int])] = {
    cms.values
      .filter(cm => cm.deployedDesks.getOrElse(0) != 0)
      .toSeq
      .sortBy(_.minute)
      .map(cm => (cm.minute, cm.terminal, cm.queue, cm.deployedDesks))
  }
}
