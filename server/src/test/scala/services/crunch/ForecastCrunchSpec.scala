package services.crunch

import controllers.ArrivalGenerator
import drt.shared.CrunchApi.{CrunchMinute, MillisSinceEpoch, StaffMinute}
import drt.shared.FlightsApi.Flights
import drt.shared.Queues.Queue
import drt.shared.Terminals.{T1, Terminal}
import drt.shared._
import server.feeds.ArrivalsFeedSuccess
import services.graphstages.CrunchMocks
import services.{SDate, TryRenjin}

import scala.collection.immutable.{List, Seq, SortedMap}
import scala.collection.mutable
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

    val liveArrival = ArrivalGenerator.arrival(schDt = scheduled, iata = "BA0001", terminal = T1, actPax = Option(21))
    val liveFlights = Flights(List(liveArrival))
    val baseArrival = ArrivalGenerator.arrival(schDt = base, iata = "BA0001", terminal = T1, actPax = Option(21))
    val baseFlights = Flights(List(baseArrival))

    val crunch = runCrunchGraph(now = () => SDate(scheduled), maxDaysToCrunch = 4, cruncher = CrunchMocks.mockCrunch)

    offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(liveFlights))
    offerAndWait(crunch.baseArrivalsInput, ArrivalsFeedSuccess(baseFlights))

    val expectedForecast = Map(SDate(base).millisSinceEpoch -> 20, SDate(base).addMinutes(1).millisSinceEpoch -> 1)

    crunch.portStateTestProbe.fishForMessage(10 seconds) {
      case PortState(_, cms, _) =>
        val forecastSummary = interestingPaxLoads(cms)
        forecastSummary == expectedForecast
    }

    crunch.shutdown

    success
  }

  "Given a live flight and a base flight arriving 3 days later, and shifts spanning the pcp time " +
    "When I ask for pax loads " +
    "Then I should see deployed staff matching the staff available for the crunch period" >> {

    val scheduled = "2017-01-01T00:00Z"
    val base = "2017-01-04T00:00Z"

    val liveArrival = ArrivalGenerator.arrival(schDt = scheduled, iata = "EZ0001", terminal = T1, actPax = Option(21))
    val liveFlights = Flights(List(liveArrival))
    val baseArrival = ArrivalGenerator.arrival(schDt = base, iata = "EZ0001", terminal = T1, actPax = Option(21))
    val baseFlights = Flights(List(baseArrival))

    val firstMinute = SDate(base)

    val startDate1 = MilliDate(SDate("2017-01-04T00:00").millisSinceEpoch)
    val endDate1 = MilliDate(SDate("2017-01-04T00:14").millisSinceEpoch)
    val assignment1 = StaffAssignment("shift s", T1, startDate1, endDate1, 1, None)
    val startDate2 = MilliDate(SDate("2017-01-04T00:15").millisSinceEpoch)
    val endDate2 = MilliDate(SDate("2017-01-04T00:29").millisSinceEpoch)
    val assignment2 = StaffAssignment("shift s", T1, startDate2, endDate2, 2, None)

    val crunch = runCrunchGraph(
      airportConfig = airportConfig.copy(
        terminals = Seq(T1),
        queues = Map(T1 -> Seq(Queues.EeaDesk))
      ),
      now = () => SDate(scheduled),
      initialShifts = ShiftAssignments(Seq(assignment1, assignment2)),
      maxDaysToCrunch = 4,
      checkRequiredStaffUpdatesOnStartup = true
    )

    offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(liveFlights))
    offerAndWait(crunch.baseArrivalsInput, ArrivalsFeedSuccess(baseFlights))

    val shift1Millis = (firstMinute.millisSinceEpoch to firstMinute.addMinutes(14).millisSinceEpoch by 60000)
      .map(m => (m, T1, Queues.EeaDesk, Some(1)))
    val shift2Millis = (firstMinute.addMinutes(15).millisSinceEpoch to firstMinute.addMinutes(29).millisSinceEpoch by 60000)
      .map(m => (m, T1, Queues.EeaDesk, Some(2)))

    val expected = shift1Millis ++ shift2Millis

    crunch.portStateTestProbe.fishForMessage(10 seconds) {
      case PortState(_, cms, _) =>
        val deployedStaff = interestingDeployments(cms).take(30)
        deployedStaff == expected
    }

    crunch.shutdown

    success
  }

  "Given a flight with pcp times just before midnight " +
    "When I crunch  " +
    "Then I should see a wait time at midnight of one minute higher than the wait time at 23:59, ie the pax before midnight did not get forgotten " >> {
    val scheduled = "2017-01-01T00:00Z"
    val beforeMidnight = "2017-01-03T23:55Z"
    val afterMidnight = "2017-01-04T00:15Z"

    val baseArrivals = List(
      ArrivalGenerator.arrival(schDt = beforeMidnight, iata = "BA0001", terminal = T1, actPax = Option(20)),
      ArrivalGenerator.arrival(schDt = afterMidnight, iata = "BA0002", terminal = T1, actPax = Option(20))
    )
    val baseFlights = Flights(baseArrivals)

    val startDate1 = MilliDate(SDate("2017-01-04T00:00").millisSinceEpoch)
    val endDate1 = MilliDate(SDate("2017-01-04T00:14").millisSinceEpoch)
    val assignment1 = StaffAssignment("shift s", T1, startDate1, endDate1, 1, None)
    val startDate2 = MilliDate(SDate("2017-01-04T00:15").millisSinceEpoch)
    val endDate2 = MilliDate(SDate("2017-01-04T00:29").millisSinceEpoch)
    val assignment2 = StaffAssignment("shift s", T1, startDate2, endDate2, 2, None)

    val crunch = runCrunchGraph(
      airportConfig = airportConfig.copy(
        crunchOffsetMinutes = 240,
        queues = Map(T1 -> Seq(Queues.EeaDesk)),
        terminals = Seq(T1)
      ),
      now = () => SDate(scheduled),
      minutesToCrunch = 1440,
      initialShifts = ShiftAssignments(Seq(assignment1, assignment2)),
      cruncher = TryRenjin.crunch,
      maxDaysToCrunch = 4
    )

    offerAndWait(crunch.baseArrivalsInput, ArrivalsFeedSuccess(baseFlights))

    crunch.portStateTestProbe.fishForMessage(10 seconds) {
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

    crunch.shutdown

    success
  }

  "Given a flight a live flight update after a base crunch & simulation, followed by a staffing change " +
    "When I look at the simulation numbers in the base port state for the day after the live flight update " +
    "Then I should see a corresponding deployed staff number " >> {

    val scheduled = "2017-01-01T00:00Z"
    val base = "2017-01-03T00:00Z"

    val baseArrival = ArrivalGenerator.arrival(schDt = base, iata = "BA0001", terminal = T1, actPax = Option(20))
    val baseFlights = Flights(List(baseArrival))

    val crunch = runCrunchGraph(
      airportConfig = airportConfig.copy(
        terminals = Seq(T1),
        queues = Map(T1 -> Seq(Queues.EeaDesk))
      ),
      now = () => SDate(scheduled)
    )

    val forecastStaffNumber = 5

    offerAndWait(crunch.baseArrivalsInput, ArrivalsFeedSuccess(baseFlights))
    /*s"shift a,T1,03/01/17,00:00,00:29,$forecastStaffNumber"*/
    val startDate = MilliDate(SDate("2017-01-03T00:00").millisSinceEpoch)
    val endDate = MilliDate(SDate("2017-01-03T00:29").millisSinceEpoch)
    val assignment = StaffAssignment("shift s", T1, startDate, endDate, forecastStaffNumber, None)
    offerAndWait(crunch.shiftsInput, ShiftAssignments(Seq(assignment)))

    val shiftMinuteMillis = (SDate(base).millisSinceEpoch until SDate(base).addMinutes(30).millisSinceEpoch by 60000).toList
    val expected = List.fill(30)(Some(forecastStaffNumber))

    crunch.portStateTestProbe.fishForMessage(10 seconds) {
      case ps: PortState =>
        val deployedStaff: Iterable[Option[Int]] = ps.crunchMinutes.values
          .filter(cm => shiftMinuteMillis.contains(cm.minute))
          .map(cm => cm.deployedDesks)
        deployedStaff == expected
    }

    crunch.shutdown

    success
  }


  "Given a base flight with 21 pax" +
    "When I ask for pax loads " +
    "Then I should see 20 pax in the 1st minute and 1 in the second" >> {

    val today = "2017-01-01T00:00Z"
    val baseScheduled = "2017-01-02T00:00Z"

    val baseArrival = ArrivalGenerator.arrival(schDt = baseScheduled, iata = "BA0001", terminal = T1, actPax = Option(21))
    val baseFlights = Flights(List(baseArrival))

    val crunch = runCrunchGraph(now = () => SDate(today))

    offerAndWait(crunch.baseArrivalsInput, ArrivalsFeedSuccess(baseFlights))

    val expectedForecast = Map(SDate(baseScheduled).millisSinceEpoch -> 20, SDate(baseScheduled).addMinutes(1).millisSinceEpoch -> 1)

    crunch.portStateTestProbe.fishForMessage(10 seconds) {
      case ps: PortState =>
        val forecastSummary = interestingPaxLoads(ps.crunchMinutes)
        forecastSummary == expectedForecast
    }

    crunch.shutdown

    success
  }

  "Given a forecast arrival with no matching base arrival " +
    "When I ask for arrivals " +
    "Then I should not see any arrivals" >> {

    val forecastScheduled = "2017-01-01T00:01Z"

    val forecastArrival = ArrivalGenerator.arrival(schDt = forecastScheduled, iata = "BA0001", terminal = T1, actPax = Option(21))
    val forecastArrivals = Flights(List(forecastArrival))

    val crunch = runCrunchGraph(now = () => SDate(forecastScheduled).addDays(-1))

    offerAndWait(crunch.forecastArrivalsInput, ArrivalsFeedSuccess(forecastArrivals))
    crunch.shutdown

    val gotAnyFlights = crunch.portStateTestProbe.receiveWhile(2 seconds) {
      case PortState(flights, _, _) => flights.size
    }.exists(_ > 0)

    crunch.shutdown

    gotAnyFlights === false
  }

  "Given a base arrival with 21 pax, and a forecast arrival scheduled 1 minute later " +
    "When I ask for arrivals " +
    "Then I should only see the base arrival as forecast arrival should be ignored when there's no matching base arrival" >> {

    val baseScheduled = "2017-01-01T00:00Z"
    val forecastScheduled = "2017-01-01T00:01Z"

    val baseArrival = ArrivalGenerator.arrival(schDt = baseScheduled, iata = "BA0001", terminal = T1, actPax = Option(21))
    val forecastArrival = ArrivalGenerator.arrival(schDt = forecastScheduled, iata = "BA0001", terminal = T1, actPax = Option(21))
    val baseArrivals = Flights(List(baseArrival))
    val forecastArrivals = Flights(List(forecastArrival))

    val crunch = runCrunchGraph(now = () => SDate(baseScheduled).addDays(-1))

    offerAndWait(crunch.forecastArrivalsInput, ArrivalsFeedSuccess(forecastArrivals))
    offerAndWait(crunch.baseArrivalsInput, ArrivalsFeedSuccess(baseArrivals))

    val expectedForecastArrivals = Set(baseArrival.copy(FeedSources = Set(AclFeedSource)))

    crunch.portStateTestProbe.fishForMessage(10 seconds) {
      case ps: PortState =>
        val crunchForecastArrivals = ps.flights.values.map(_.apiFlight).toSet

        crunchForecastArrivals == expectedForecastArrivals
    }

    crunch.shutdown

    success
  }

  "Given a base arrival with 21 pax, and a matching forecast arrival with 50 pax, 25 trans pax and a different format flight code " +
    "When I ask for arrivals " +
    "Then I should see the base arrival with the forecast's pax & transpax nos overlaid, but retaining the base's flight code" >> {

    val baseScheduled = "2017-01-01T00:00Z"
    val forecastScheduled = baseScheduled

    val baseArrival = ArrivalGenerator.arrival(schDt = baseScheduled, iata = "BA0001", terminal = T1, actPax = Option(21))
    val forecastArrival = ArrivalGenerator.arrival(schDt = forecastScheduled, iata = "BAW0001", terminal = T1, actPax = Option(50), tranPax = Option(25))
    val baseArrivals = Flights(List(baseArrival))
    val forecastArrivals = Flights(List(forecastArrival))

    val crunch = runCrunchGraph(now = () => SDate(baseScheduled).addDays(-1))

    offerAndWait(crunch.forecastArrivalsInput, ArrivalsFeedSuccess(forecastArrivals))
    offerAndWait(crunch.baseArrivalsInput, ArrivalsFeedSuccess(baseArrivals))

    val expectedForecastArrivals = Set(baseArrival.copy(ActPax = Some(50), TranPax = Some(25), FeedSources = Set(ForecastFeedSource, AclFeedSource)))

    crunch.portStateTestProbe.fishForMessage(10 seconds) {
      case ps: PortState =>
        val crunchForecastArrivals = ps.flights.values.map(_.apiFlight).toSet
        crunchForecastArrivals == expectedForecastArrivals
    }

    crunch.shutdown

    success
  }

  "Given a base arrival with 21 pax, followed by a matching forecast arrival with 50 pax, and finally a live flight with zero pax " +
    "When I ask for arrivals " +
    "Then I should see the base arrival details with the forecast pax" >> {

    val baseScheduled = "2017-01-01T00:00Z"
    val forecastScheduled = baseScheduled
    val liveScheduled = baseScheduled

    val baseArrival = ArrivalGenerator.arrival(schDt = baseScheduled, iata = "BA0001", terminal = T1, actPax = Option(21))
    val forecastArrival = ArrivalGenerator.arrival(schDt = forecastScheduled, iata = "BAW0001", terminal = T1, actPax = Option(50), tranPax = Option(25))
    val liveArrival = ArrivalGenerator.arrival(schDt = liveScheduled, iata = "BA0001", terminal = T1, actPax = None, tranPax = None, estDt = liveScheduled)
    val baseArrivals = Flights(List(baseArrival))
    val forecastArrivals = Flights(List(forecastArrival))
    val liveArrivals = Flights(List(liveArrival))

    val crunch = runCrunchGraph(now = () => SDate(baseScheduled).addDays(-1))

    offerAndWait(crunch.baseArrivalsInput, ArrivalsFeedSuccess(baseArrivals))
    offerAndWait(crunch.forecastArrivalsInput, ArrivalsFeedSuccess(forecastArrivals))
    offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(liveArrivals))

    val expectedForecastArrivals = Set(baseArrival.copy(ActPax = forecastArrival.ActPax, TranPax = forecastArrival.TranPax, Estimated = Some(SDate(liveScheduled).millisSinceEpoch), FeedSources = Set(AclFeedSource, ForecastFeedSource, LiveFeedSource)))

    crunch.portStateTestProbe.fishForMessage(10 seconds) {
      case ps: PortState =>
        val crunchForecastArrivals = ps.flights.values.map(_.apiFlight).toSet

        crunchForecastArrivals == expectedForecastArrivals
    }

    crunch.shutdown

    success
  }

  "Given 2 base arrivals followed by 1 matching forecast arrival, and then the other matching forecast arrival " +
    "When I ask for arrivals " +
    "Then I should see the forecast pax & status details for both arrivals" >> {

    val baseScheduled = "2017-01-01T00:00Z"
    val forecastScheduled = baseScheduled

    val baseArrival1 = ArrivalGenerator.arrival(schDt = baseScheduled, iata = "BA0001", terminal = T1, actPax = Option(21), status = ArrivalStatus("ACL Forecast"))
    val baseArrival2 = ArrivalGenerator.arrival(schDt = baseScheduled, iata = "AA1110", terminal = T1, actPax = Option(22), status = ArrivalStatus("ACL Forecast"))
    val forecastArrival1 = ArrivalGenerator.arrival(schDt = forecastScheduled, iata = "BAW0001", terminal = T1, actPax = Option(51), status = ArrivalStatus("Port Forecast"))
    val forecastArrival2 = ArrivalGenerator.arrival(schDt = forecastScheduled, iata = "AAW1110", terminal = T1, actPax = Option(52), status = ArrivalStatus("Port Forecast"))
    val baseArrivals = Flights(List(baseArrival1, baseArrival2))
    val forecastArrivals1st = Flights(List(forecastArrival1))
    val forecastArrivals2nd = Flights(List(forecastArrival2))

    val crunch = runCrunchGraph(now = () => SDate(baseScheduled).addDays(-1))

    offerAndWait(crunch.baseArrivalsInput, ArrivalsFeedSuccess(baseArrivals))
    crunch.portStateTestProbe.receiveOne(2 seconds)
    offerAndWait(crunch.forecastArrivalsInput, ArrivalsFeedSuccess(forecastArrivals1st))
    crunch.portStateTestProbe.receiveOne(2 seconds)
    offerAndWait(crunch.forecastArrivalsInput, ArrivalsFeedSuccess(forecastArrivals2nd))

    val expectedForecastArrivals = Set(
      baseArrival1.copy(ActPax = Some(51), Status = ArrivalStatus("Port Forecast"), FeedSources = Set(ForecastFeedSource, AclFeedSource)),
      baseArrival2.copy(ActPax = Some(52), Status = ArrivalStatus("Port Forecast"), FeedSources = Set(ForecastFeedSource, AclFeedSource)))

    crunch.portStateTestProbe.fishForMessage(10 seconds) {
      case ps: PortState =>
        val crunchForecastArrivals = ps.flights.values.map(_.apiFlight).toSet
        crunchForecastArrivals == expectedForecastArrivals
    }

    crunch.shutdown

    success
  }

  "Given an initial base arrivals of 1 flight, and and initial merged arrivals of 2 flights (from a port state that hadn't been updated) " +
    "When I send an updated base arrivals " +
    "Then I should only see arrivals that exist in the latest base arrivals" >> {

    val scheduled = "2017-01-01T00:00Z"
    val base = "2017-01-04T00:00Z"

    val initialBaseArrivals = mutable.SortedMap[UniqueArrival, Arrival]() ++ List(ArrivalGenerator.arrival(schDt = base, iata = "BA0001", terminal = T1, actPax = Option(21))).map(a => (a.unique, a))
    val initialPortStateArrivals = Seq(
      ArrivalGenerator.arrival(schDt = base, iata = "FR0001", terminal = T1, actPax = Option(101)),
      ArrivalGenerator.arrival(schDt = base, iata = "EZ1100", terminal = T1, actPax = Option(250))
    ).map(a => (a.unique, ApiFlightWithSplits(a, Set())))

    val updatedBaseArrivals = mutable.SortedMap[UniqueArrival, Arrival]() ++ List(ArrivalGenerator.arrival(schDt = base, iata = "AA0099", terminal = T1, actPax = Option(55))).map(a => (a.unique, a))

    val crunch = runCrunchGraph(
      now = () => SDate(scheduled),
      initialForecastBaseArrivals = initialBaseArrivals,
      initialPortState = Option(PortState(SortedMap[UniqueArrival, ApiFlightWithSplits]() ++ initialPortStateArrivals, SortedMap[TQM, CrunchMinute](), SortedMap[TM, StaffMinute]())),
      maxDaysToCrunch = 4
    )

    offerAndWait(crunch.baseArrivalsInput, ArrivalsFeedSuccess(Flights(updatedBaseArrivals.values.toSeq)))

    val expectedFlightCodes = updatedBaseArrivals.values.map(_.flightCode)

    crunch.portStateTestProbe.fishForMessage(2 seconds) {
      case PortState(flightsWithSplits, _, _) =>
        val flightCodes = flightsWithSplits.values.map(_.apiFlight.flightCode)
        flightCodes == expectedFlightCodes
    }

    crunch.shutdown

    success
  }

  def interestingPaxLoads(cms: Map[TQM, CrunchApi.CrunchMinute]): Map[MillisSinceEpoch, Double] = {
    cms.values.filter(cm => cm.paxLoad != 0).map(cm => (cm.minute, cm.paxLoad)).toMap
  }

  def interestingDeployments(cms: Map[TQM, CrunchApi.CrunchMinute]): scala.Seq[(MillisSinceEpoch, Terminal, Queue, Option[Int])] = {
    cms.values
      .filter(cm => cm.deployedDesks.getOrElse(0) != 0)
      .toSeq
      .sortBy(_.minute)
      .map(cm => (cm.minute, cm.terminal, cm.queue, cm.deployedDesks))
  }
}
