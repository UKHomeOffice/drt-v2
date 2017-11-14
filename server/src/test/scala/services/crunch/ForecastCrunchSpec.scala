package services.crunch

import controllers.ArrivalGenerator
import drt.shared.CrunchApi.PortState
import drt.shared.FlightsApi.Flights
import drt.shared._
import services.SDate
import services.graphstages.Crunch
import services.graphstages.Crunch._

import scala.collection.immutable.{List, Seq}
import scala.concurrent.duration._


class ForecastCrunchSpec() extends CrunchTestLike {
  "Given a live flight and a base flight arriving 3 days later " +
    "When I ask for pax loads " +
    "Then I should see pax arriving in the 1st and 2nd minute for the live flight, and the 1st & 2nd minute 3 days later for the base flight" >> {

    val scheduled = "2017-01-01T00:00Z"
    val base = "2017-01-04T00:00Z"

    val liveArrival = ArrivalGenerator.apiFlight(schDt = scheduled, iata = "BA0001", terminal = "T1", actPax = 21)
    val liveFlights = Flights(List(liveArrival))
    val baseArrival = ArrivalGenerator.apiFlight(schDt = base, iata = "BA0001", terminal = "T1", actPax = 21)
    val baseFlights = Flights(List(baseArrival))

    val crunch = runCrunchGraph(
      now = () => SDate(scheduled),
      crunchStartDateProvider = (_) => getLocalLastMidnight(SDate(scheduled)),
      crunchEndDateProvider = (_) => getLocalLastMidnight(SDate(base)).addMinutes(30))

    crunch.liveArrivalsInput.offer(liveFlights)
    val liveResult = crunch.liveTestProbe.expectMsgAnyClassOf(10 seconds, classOf[PortState])
    crunch.forecastTestProbe.expectMsgAnyClassOf(10 seconds, classOf[PortState])

    crunch.baseArrivalsInput.offer(baseFlights)
    val forecastResult = crunch.forecastTestProbe.expectMsgAnyClassOf(10 seconds, classOf[PortState])

    val liveSummary = paxLoadsFromPortState(liveResult, 2)
    val forecastSummary = paxLoadsFromPortState(forecastResult, 2, 3 * 1440)

    val expectedLive = Map("T1" -> Map(Queues.EeaDesk -> Seq(20, 1)))
    val expectedForecast = Map("T1" -> Map(Queues.EeaDesk -> Seq(20, 1)))

    (liveSummary, forecastSummary) === Tuple2(expectedLive, expectedForecast)
  }

  "Given a live flight and a base flight arriving 3 days later, and shifts spanning the pcp time  " +
    "When I ask for pax loads " +
    "Then I should see deployed staff matching the staff available for the crunch period" >> {

    val scheduled = "2017-01-01T00:00Z"
    val base = "2017-01-04T00:00Z"

    val liveArrival = ArrivalGenerator.apiFlight(schDt = scheduled, iata = "BA0001", terminal = "T1", actPax = 21)
    val liveFlights = Flights(List(liveArrival))
    val baseArrival = ArrivalGenerator.apiFlight(schDt = base, iata = "BA0001", terminal = "T1", actPax = 21)
    val baseFlights = Flights(List(baseArrival))

    val crunch = runCrunchGraph(
      now = () => SDate(scheduled),
      crunchStartDateProvider = (_) => getLocalLastMidnight(SDate(scheduled)),
      crunchEndDateProvider = (_) => getLocalLastMidnight(SDate(base)).addMinutes(30),
      shifts =
        """shift a,T1,04/01/17,00:00,00:14,1
          |shift b,T1,04/01/17,00:15,00:29,2
        """.stripMargin)

    crunch.liveArrivalsInput.offer(liveFlights)

    crunch.liveTestProbe.expectMsgAnyClassOf(10 seconds, classOf[PortState])
    crunch.forecastTestProbe.expectMsgAnyClassOf(10 seconds, classOf[PortState])

    crunch.baseArrivalsInput.offer(baseFlights)
    val forecastResult = crunch.forecastTestProbe.expectMsgAnyClassOf(10 seconds, classOf[PortState])

    val deployedStaff: Seq[Option[Int]] = forecastResult
      .crunchMinutes
      .values.toList.sortBy(_.minute).takeRight(30)
      .map(_.deployedDesks)

    val expected = List.fill(15)(Some(1)) ::: List.fill(15)(Some(2))

    deployedStaff === expected
  }

  "Given a flight with pcp times just before midnight " +
    "When I crunch  " +
    "Then I should see a wait time at midnight of one minute higher than the wait time at 23:59, ie the pax before midnight did not get forgotten " >> {

    val scheduled = "2017-01-01T00:00Z"
    val beforeMidnight = "2017-01-03T23:55Z"

    val liveArrival = ArrivalGenerator.apiFlight(schDt = scheduled, iata = "BA0001", terminal = "T1", actPax = 20)
    val liveFlights = Flights(List(liveArrival))
    val baseArrival = ArrivalGenerator.apiFlight(schDt = beforeMidnight, iata = "BA0001", terminal = "T1", actPax = 20)
    val baseFlights = Flights(List(baseArrival))

    val crunch = runCrunchGraph(
      now = () => SDate(scheduled),
      crunchStartDateProvider = (_) => getLocalLastMidnight(SDate(scheduled)),
      crunchEndDateProvider = (_) => SDate("2017-01-04T00:30Z"),
      shifts =
        """shift a,T1,04/01/17,00:00,00:14,1
          |shift b,T1,04/01/17,00:15,00:29,2
        """.stripMargin)

    crunch.liveArrivalsInput.offer(liveFlights)

    crunch.liveTestProbe.expectMsgAnyClassOf(10 seconds, classOf[PortState])
    crunch.forecastTestProbe.expectMsgAnyClassOf(10 seconds, classOf[PortState])

    crunch.baseArrivalsInput.offer(baseFlights)
    val forecastResult = crunch.forecastTestProbe.expectMsgAnyClassOf(10 seconds, classOf[PortState])

    val waitTimes: Seq[Int] = forecastResult
      .crunchMinutes
      .values.toList.sortBy(_.minute).takeRight(31).dropRight(29)
      .map(_.waitTime)

    val waitTimeOneMinuteBeforeMidnight = waitTimes.head
    val waitTimeOneMinuteAtMidnight = waitTimes(1)

    val expected = waitTimeOneMinuteBeforeMidnight + 1

    waitTimeOneMinuteAtMidnight === expected
  }

  "Given a flight a live flight update after a base crunch & simulation, followed by a staffing change " +
    "When I look at the simulation numbers in the base port state for the day after the live flight update " +
    "Then I should see a corresponding deployed staff number " >> {

    val scheduled = "2017-01-01T00:00Z"
    val base = "2017-01-03T00:00Z"

    val liveArrival = ArrivalGenerator.apiFlight(schDt = scheduled, iata = "BA0001", terminal = "T1", actPax = 20)
    val liveFlights = Flights(List(liveArrival))

    val baseArrival = ArrivalGenerator.apiFlight(schDt = base, iata = "BA0001", terminal = "T1", actPax = 20)
    val baseFlights = Flights(List(baseArrival))

    val crunch = runCrunchGraph(
      now = () => SDate(scheduled),
      crunchStartDateProvider = (_) => getLocalLastMidnight(SDate(scheduled)),
      crunchEndDateProvider = (maxPcpTime: SDateLike) => getLocalNextMidnight(maxPcpTime),
      earliestAndLatestAffectedPcpTime = Crunch.earliestAndLatestAffectedPcpTimeFromFlights(maxDays = 3),
      shifts =
        """shift a,T1,04/01/17,00:00,00:14,1
          |shift b,T1,04/01/17,00:15,00:29,2
        """.stripMargin)

    crunch.liveArrivalsInput.offer(liveFlights)
    crunch.liveTestProbe.expectMsgAnyClassOf(10 seconds, classOf[PortState])
    crunch.forecastTestProbe.expectMsgAnyClassOf(10 seconds, classOf[PortState])

    crunch.baseArrivalsInput.offer(baseFlights)
    crunch.liveTestProbe.expectMsgAnyClassOf(10 seconds, classOf[PortState])
    crunch.forecastTestProbe.expectMsgAnyClassOf(10 seconds, classOf[PortState])

    crunch.liveArrivalsInput.offer(Flights(List(liveArrival.copy(ActPax = 10))))
    crunch.liveTestProbe.expectMsgAnyClassOf(10 seconds, classOf[PortState])
    crunch.forecastTestProbe.expectMsgAnyClassOf(10 seconds, classOf[PortState])

    crunch.forecastShiftsInput.offer("shift a,T1,03/01/17,00:00,00:29,5")
    val forecastResult = crunch.forecastTestProbe.expectMsgAnyClassOf(10 seconds, classOf[PortState])

    val deployedStaff: Seq[Option[Int]] = forecastResult
      .crunchMinutes
      .values.toList.sortBy(_.minute).takeRight(1440).take(30)
      .map(_.deployedDesks)

    val expected = List.fill(30)(Some(5))

    deployedStaff === expected
  }

  "Given a base flight with 21 pax" +
    "When I ask for pax loads " +
    "Then I should see 20 pax in the 1st minute and 1 in the second" >> {

    val baseScheduled = "2017-01-01T00:00Z"

    val baseArrival = ArrivalGenerator.apiFlight(schDt = baseScheduled, iata = "BA0001", terminal = "T1", actPax = 21)
    val baseFlights = Flights(List(baseArrival))

    val crunch = runCrunchGraph(
      now = () => SDate(baseScheduled),
      crunchStartDateProvider = (_) => getLocalLastMidnight(SDate(baseScheduled)),
      crunchEndDateProvider = (_) => getLocalLastMidnight(SDate(baseScheduled)).addMinutes(30))

    crunch.baseArrivalsInput.offer(baseFlights)
    val forecastResult = crunch.forecastTestProbe.expectMsgAnyClassOf(10 seconds, classOf[PortState])

    val forecastSummary = paxLoadsFromPortState(forecastResult, 2, SDate(baseScheduled))

    val expectedForecast = Map("T1" -> Map(Queues.EeaDesk -> Seq(20, 1)))

    forecastSummary === expectedForecast
  }

  "Given a forecast arrival with no matching base arrival " +
    "When I ask for arrivals " +
    "Then I should not see any arrivals" >> {

    val forecastScheduled = "2017-01-01T00:01Z"

    val forecastArrival = ArrivalGenerator.apiFlight(schDt = forecastScheduled, iata = "BA0001", terminal = "T1", actPax = 21)
    val forecastArrivals = Flights(List(forecastArrival))

    val crunch = runCrunchGraph(
      now = () => SDate(forecastScheduled),
      crunchStartDateProvider = (_) => getLocalLastMidnight(SDate(forecastScheduled)),
      crunchEndDateProvider = (_) => getLocalLastMidnight(SDate(forecastScheduled)).addMinutes(30))

    crunch.forecastArrivalsInput.offer(forecastArrivals)
    crunch.forecastTestProbe.expectNoMsg(1 seconds)

    true
  }

  "Given a base arrival with 21 pax, and a forecast arrival scheduled 1 minute later " +
    "When I ask for arrivals " +
    "Then I should only see the base arrival as forecast arrival should be ignored when there's no matching base arrival" >> {

    val baseScheduled = "2017-01-01T00:00Z"
    val forecastScheduled = "2017-01-01T00:01Z"

    val baseArrival = ArrivalGenerator.apiFlight(schDt = baseScheduled, iata = "BA0001", terminal = "T1", actPax = 21)
    val forecastArrival = ArrivalGenerator.apiFlight(schDt = forecastScheduled, iata = "BA0001", terminal = "T1", actPax = 21)
    val baseArrivals = Flights(List(baseArrival))
    val forecastArrivals = Flights(List(forecastArrival))

    val crunch = runCrunchGraph(
      now = () => SDate(baseScheduled),
      crunchStartDateProvider = (_) => getLocalLastMidnight(SDate(baseScheduled)),
      crunchEndDateProvider = (_) => getLocalLastMidnight(SDate(baseScheduled)).addMinutes(30))

    crunch.forecastArrivalsInput.offer(forecastArrivals)
    crunch.baseArrivalsInput.offer(baseArrivals)
    val crunchForecastArrivals = crunch.forecastTestProbe.expectMsgAnyClassOf(10 seconds, classOf[PortState]).flights.values.map(_.apiFlight).toSet

    val expectedForecastArrivals = Set(baseArrival)

    crunchForecastArrivals === expectedForecastArrivals
  }

  "Given a base arrival with 21 pax, and a matching forecast arrival with 50 pax, 25 trans pax and a different format flight code " +
    "When I ask for arrivals " +
    "Then I should see the base arrival with the forecast's pax & transpax nos overlaid, but retaining the base's flight code" >> {

    val baseScheduled = "2017-01-01T00:00Z"
    val forecastScheduled = baseScheduled

    val baseArrival = ArrivalGenerator.apiFlight(schDt = baseScheduled, iata = "BA0001", terminal = "T1", actPax = 21)
    val forecastArrival = ArrivalGenerator.apiFlight(schDt = forecastScheduled, iata = "BAW0001", terminal = "T1", actPax = 50, tranPax = 25)
    val baseArrivals = Flights(List(baseArrival))
    val forecastArrivals = Flights(List(forecastArrival))

    val crunch = runCrunchGraph(
      now = () => SDate(baseScheduled),
      crunchStartDateProvider = (_) => getLocalLastMidnight(SDate(baseScheduled)),
      crunchEndDateProvider = (_) => getLocalLastMidnight(SDate(baseScheduled)).addMinutes(30))

    crunch.forecastArrivalsInput.offer(forecastArrivals)
    Thread.sleep(250L)
    crunch.baseArrivalsInput.offer(baseArrivals)
    val crunchForecastArrivals = crunch.forecastTestProbe.expectMsgAnyClassOf(10 seconds, classOf[PortState]).flights.values.map(_.apiFlight).toSet

    val expectedForecastArrivals = Set(baseArrival.copy(ActPax = 50, TranPax = 25))

    crunchForecastArrivals === expectedForecastArrivals
  }

  "Given 2 base arrivals followed by 1 matching forecast arrival, and then the other matching forecast arrival " +
    "When I ask for arrivals " +
    "Then I should see the forecast pax & status details for both arrivals" >> {

    val baseScheduled = "2017-01-01T00:00Z"
    val forecastScheduled = baseScheduled

    val baseArrival1 = ArrivalGenerator.apiFlight(schDt = baseScheduled, iata = "BA0001", terminal = "T1", actPax = 21, status = "ACL Forecast")
    val baseArrival2 = ArrivalGenerator.apiFlight(schDt = baseScheduled, iata = "AA1110", terminal = "T1", actPax = 22, status = "ACL Forecast")
    val forecastArrival1 = ArrivalGenerator.apiFlight(schDt = forecastScheduled, iata = "BAW0001", terminal = "T1", actPax = 51, status = "Port Forecast")
    val forecastArrival2 = ArrivalGenerator.apiFlight(schDt = forecastScheduled, iata = "AAW1110", terminal = "T1", actPax = 52, status = "Port Forecast")
    val baseArrivals = Flights(List(baseArrival1, baseArrival2))
    val forecastArrivals1st = Flights(List(forecastArrival1))
    val forecastArrivals2nd = Flights(List(forecastArrival2))

    val crunch = runCrunchGraph(
      now = () => SDate(baseScheduled),
      crunchStartDateProvider = (_) => getLocalLastMidnight(SDate(baseScheduled)),
      crunchEndDateProvider = (_) => getLocalLastMidnight(SDate(baseScheduled)).addMinutes(30))

    crunch.baseArrivalsInput.offer(baseArrivals)
    crunch.forecastTestProbe.expectMsgAnyClassOf(10 seconds, classOf[PortState])

    crunch.forecastArrivalsInput.offer(forecastArrivals1st)
    crunch.forecastTestProbe.expectMsgAnyClassOf(10 seconds, classOf[PortState])

    crunch.forecastArrivalsInput.offer(forecastArrivals2nd)
    val crunchForecastArrivals = crunch.forecastTestProbe.expectMsgAnyClassOf(10 seconds, classOf[PortState]).flights.values.map(_.apiFlight).toSet

    val expectedForecastArrivals = Set(baseArrival1.copy(ActPax = 51, Status = "Port Forecast"), baseArrival2.copy(ActPax = 52, Status = "Port Forecast"))

    crunchForecastArrivals === expectedForecastArrivals
  }
}
