package services.crunch

import controllers.ArrivalGenerator
import drt.shared.Crunch.PortState
import drt.shared.FlightsApi.Flights
import drt.shared._
import services.SDate
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
      crunchStartDateProvider = (_) => getLocalLastMidnight(SDate(scheduled)),
      crunchEndDateProvider = (_) => getLocalLastMidnight(SDate(forecast)).addMinutes(30))

    crunch.liveArrivalsInput.offer(liveFlights)
    val liveResult = crunch.liveTestProbe.expectMsgAnyClassOf(10 seconds, classOf[PortState])
    crunch.forecastTestProbe.expectMsgAnyClassOf(10 seconds, classOf[PortState])

    crunch.baseArrivalsInput.offer(forecastFlights)
    val forecastResult = crunch.forecastTestProbe.expectMsgAnyClassOf(10 seconds, classOf[PortState])

    val liveSummary = paxLoadsFromPortState(liveResult, 2)
    val forecastSummary = paxLoadsFromPortState(forecastResult, 2, 3*1440)

    val expectedLive = Map("T1" -> Map(Queues.EeaDesk -> Seq(20, 1)))
    val expectedForecast = Map("T1" -> Map(Queues.EeaDesk -> Seq(20, 1)))

    (liveSummary, forecastSummary) === Tuple2(expectedLive, expectedForecast)
  }

}
