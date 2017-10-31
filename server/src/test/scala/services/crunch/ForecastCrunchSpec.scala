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
  "Given a live flight and a forecast flight arriving 3 days later " +
    "When I ask for pax loads " +
    "Then I should see pax arriving in the 1st and 2nd minute for the live flight, and the 1st & 2nd minute 3 days later for the forecast flight" >> {

    val scheduled = "2017-01-01T00:00Z"
    val forecast = "2017-01-04T00:00Z"

    val liveArrival = ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled, iata = "BA0001", terminal = "T1", actPax = 21)
    val liveFlights = Flights(List(liveArrival))
    val forecastArrival = ArrivalGenerator.apiFlight(flightId = 1, schDt = forecast, iata = "BA0001", terminal = "T1", actPax = 21)
    val forecastFlights = Flights(List(forecastArrival))

    val crunch = runCrunchGraph(
      now = () => SDate(scheduled),
      crunchStartDateProvider = (_) => getLocalLastMidnight(SDate(scheduled)),
      crunchEndDateProvider = (_) => getLocalLastMidnight(SDate(forecast)).addMinutes(30))

    crunch.liveArrivalsInput.offer(liveFlights)
    val liveResult = crunch.liveTestProbe.expectMsgAnyClassOf(10 seconds, classOf[PortState])
    crunch.forecastTestProbe.expectMsgAnyClassOf(10 seconds, classOf[PortState])

    crunch.baseArrivalsInput.offer(forecastFlights)
    val forecastResult = crunch.forecastTestProbe.expectMsgAnyClassOf(10 seconds, classOf[PortState])

    val liveSummary = paxLoadsFromPortState(liveResult, 2)
    val forecastSummary = paxLoadsFromPortState(forecastResult, 2, 3 * 1440)

    val expectedLive = Map("T1" -> Map(Queues.EeaDesk -> Seq(20, 1)))
    val expectedForecast = Map("T1" -> Map(Queues.EeaDesk -> Seq(20, 1)))

    (liveSummary, forecastSummary) === Tuple2(expectedLive, expectedForecast)
  }

  "Given a live flight and a forecast flight arriving 3 days later, and shifts spanning the pcp time  " +
    "When I ask for pax loads " +
    "Then I should see deployed staff matching the staff available for the crunch period" >> {

    val scheduled = "2017-01-01T00:00Z"
    val forecast = "2017-01-04T00:00Z"

    val liveArrival = ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled, iata = "BA0001", terminal = "T1", actPax = 21)
    val liveFlights = Flights(List(liveArrival))
    val forecastArrival = ArrivalGenerator.apiFlight(flightId = 1, schDt = forecast, iata = "BA0001", terminal = "T1", actPax = 21)
    val forecastFlights = Flights(List(forecastArrival))

    val crunch = runCrunchGraph(
      now = () => SDate(scheduled),
      crunchStartDateProvider = (_) => getLocalLastMidnight(SDate(scheduled)),
      crunchEndDateProvider = (_) => getLocalLastMidnight(SDate(forecast)).addMinutes(30),
      shifts =
        """shift a,T1,04/01/17,00:00,00:14,1
          |shift b,T1,04/01/17,00:15,00:29,2
        """.stripMargin)

    crunch.liveArrivalsInput.offer(liveFlights)

    crunch.liveTestProbe.expectMsgAnyClassOf(10 seconds, classOf[PortState])
    crunch.forecastTestProbe.expectMsgAnyClassOf(10 seconds, classOf[PortState])

    crunch.baseArrivalsInput.offer(forecastFlights)
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
    val forecastArrival = ArrivalGenerator.apiFlight(schDt = beforeMidnight, iata = "BA0001", terminal = "T1", actPax = 20)
    val forecastFlights = Flights(List(forecastArrival))

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

    crunch.baseArrivalsInput.offer(forecastFlights)
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

  "Given a flight a live flight update after a forecast crunch & simulation, followed by a staffing change " +
    "When I look at the simulation numbers in the forecast port state for the day after the live flight update " +
    "Then I should see a corresponding deployed staff number " >> {

    val scheduled = "2017-01-01T00:00Z"
    val forecast = "2017-01-03T00:00Z"

    val liveArrival = ArrivalGenerator.apiFlight(schDt = scheduled, iata = "BA0001", terminal = "T1", actPax = 20)
    val liveFlights = Flights(List(liveArrival))

    val forecastArrival = ArrivalGenerator.apiFlight(schDt = forecast, iata = "BA0001", terminal = "T1", actPax = 20)
    val forecastFlights = Flights(List(forecastArrival))

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

    crunch.baseArrivalsInput.offer(forecastFlights)
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
}
