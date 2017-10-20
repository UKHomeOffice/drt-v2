package services.crunch

import controllers.{ArrivalGenerator, Forecast}
import drt.shared.FlightsApi.Flights
import drt.shared.{Crunch, Queues}
import services.SDate
import services.graphstages.Crunch._

import scala.collection.immutable.{List, Seq}
import scala.concurrent.duration._

class ForecastPageSpec() extends CrunchTestLike {

  import Crunch._

  "Given a forecast arriving on 2017-01-02T00:00Z with 1 pax and on 2017-01-03T00:00Z with 1 pax and 0 min desks" +
    "When I ask for a week of forecast beginning 2017-01-02T00:00Z" >> {

    val day1 = "2017-01-02T00:00Z"
    val day2 = "2017-01-03T00:00Z"
    val weekbeginning = "2017-01-02T00:00Z"

    val forecastArrivalDay1 = ArrivalGenerator.apiFlight(flightId = 1, schDt = day1, iata = "BA0001", terminal = "T1", actPax = 1)
    val forecastArrivalDay2 = ArrivalGenerator.apiFlight(flightId = 2, schDt = day2, iata = "BA0002", terminal = "T1", actPax = 1)
    val forecastFlights = Flights(List(forecastArrivalDay1, forecastArrivalDay2))

    val crunch = runCrunchGraph(
      minMaxDesks = Map("T1" -> Map(Queues.EeaDesk -> ((List.fill[Int](24)(0), List.fill[Int](24)(20))))),
      crunchStartDateProvider = (_) => getLocalLastMidnight(SDate(weekbeginning)),
      crunchEndDateProvider = (_) => getLocalLastMidnight(SDate(weekbeginning)).addDays(3))

    crunch.baseArrivalsInput.offer(forecastFlights)

    val forecastResult = crunch.forecastTestProbe.expectMsgAnyClassOf(30 seconds, classOf[PortState])

    val weekOf15MinSlots: Map[MillisSinceEpoch, Seq[ForecastTimeSlot]] = Forecast.rollUpForWeek(forecastResult.crunchMinutes.values.toSet, "T1")

    "Then I should see 1 desk rec for the first slot of the first day" >> {

      val expected = List(
        ForecastTimeSlot(SDate("2017-01-02T00:00Z").millisSinceEpoch, 0, 1),
        ForecastTimeSlot(SDate("2017-01-02T00:15Z").millisSinceEpoch, 0, 0),
        ForecastTimeSlot(SDate("2017-01-02T00:30Z").millisSinceEpoch, 0, 0),
        ForecastTimeSlot(SDate("2017-01-02T00:45Z").millisSinceEpoch, 0, 0)
      )
      val firstDayFirstHour = weekOf15MinSlots(SDate("2017-01-02T00:00Z").millisSinceEpoch).take(4)

      firstDayFirstHour === expected
    }

    "Then I should see 1 desk rec for the first slot of the second day" >> {

      val expected = List(
        ForecastTimeSlot(SDate("2017-01-03T00:00Z").millisSinceEpoch, 0, 1),
        ForecastTimeSlot(SDate("2017-01-03T00:15Z").millisSinceEpoch, 0, 0),
        ForecastTimeSlot(SDate("2017-01-03T00:30Z").millisSinceEpoch, 0, 0),
        ForecastTimeSlot(SDate("2017-01-03T00:45Z").millisSinceEpoch, 0, 0)
      )

      val secondDayFirstHour = weekOf15MinSlots(SDate("2017-01-03T00:00Z").millisSinceEpoch).take(4)

      secondDayFirstHour === expected
    }

    "Then I should see 0 desk recs for the whole of the 3rd day" >> {

      val expected = List.fill(96)(0)

      val deskRecsForThirdDay = weekOf15MinSlots(SDate("2017-01-04T00:00Z").millisSinceEpoch).map(_.required)

      deskRecsForThirdDay === expected
    }
  }
}
