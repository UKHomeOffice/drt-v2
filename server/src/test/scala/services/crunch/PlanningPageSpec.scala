package services.crunch

import controllers.{ArrivalGenerator, Forecast}
import drt.shared.FlightsApi.Flights
import drt.shared.{CrunchApi, Queues}
import services.SDate
import services.graphstages.Crunch._

import scala.concurrent.duration._

class PlanningPageSpec() extends CrunchTestLike {
  sequential
  isolated

  import CrunchApi._

  "Given a forecast arriving on 2017-01-02T00:00Z with 5 pax and on 2017-01-03T00:00Z with 20 staff on shift and 1 max desk" +
    "When I ask for 1 day of forecast on 2017-01-02T00:00Z " >> {

    val day1 = "2017-01-02T00:00Z"
    val weekbeginning = "2017-01-02T00:00Z"

    val forecastArrivalDay1 = ArrivalGenerator.apiFlight(flightId = 1, schDt = day1, iata = "BA0001", terminal = "T1", actPax = 5)
    val forecastFlights = Flights(List(forecastArrivalDay1))

    val crunch = runCrunchGraph(
      now = () => SDate(weekbeginning),
      airportConfig = airportConfig.copy(
        minMaxDesksByTerminalQueue = Map("T1" -> Map(Queues.EeaDesk -> ((List.fill[Int](24)(0), List.fill[Int](24)(1)))))
      ),
      minutesToCrunch = 1440,
      crunchStartDateProvider = (_) => getLocalLastMidnight(SDate(weekbeginning)),
      crunchEndDateProvider = (_) => getLocalLastMidnight(SDate(weekbeginning)).addDays(3),
      shifts =
        """shift a,T1,02/01/17,00:00,23:59,20
        """.stripMargin
    )

    crunch.baseArrivalsInput.offer(forecastFlights)

    val forecastResult = getLastMessageReceivedBy(crunch.forecastTestProbe, 2 seconds)

    val weekOf15MinSlots: Map[MillisSinceEpoch, Seq[ForecastTimeSlot]] = Forecast.rollUpForWeek(
      forecastResult.crunchMinutes.values.toSet,
      forecastResult.staffMinutes.values.toSet,
      "T1"
    )

    "Then I should see the actual staff numbers in the forecast" >> {

      val expected = List(
        ForecastTimeSlot(SDate("2017-01-02T00:00Z").millisSinceEpoch, 20, 1),
        ForecastTimeSlot(SDate("2017-01-02T00:15Z").millisSinceEpoch, 20, 0),
        ForecastTimeSlot(SDate("2017-01-02T00:30Z").millisSinceEpoch, 20, 0),
        ForecastTimeSlot(SDate("2017-01-02T00:45Z").millisSinceEpoch, 20, 0)
      )
      val firstDayFirstHour = weekOf15MinSlots(SDate("2017-01-02T00:00Z").millisSinceEpoch).take(4)

      firstDayFirstHour === expected
    }
  }
}
