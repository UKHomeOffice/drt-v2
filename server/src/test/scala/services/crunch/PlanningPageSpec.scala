package services.crunch

import controllers.{ArrivalGenerator, Forecast}
import drt.shared.FlightsApi.Flights
import drt.shared.{CrunchApi, Queues}
import services.{SDate, TryRenjin}
import services.graphstages.Crunch._

import scala.concurrent.duration._

class PlanningPageSpec() extends CrunchTestLike {
  sequential
  isolated

  import CrunchApi._

  "Given a forecast arriving on 2017-01-02T00:00Z with 5 pax and on 2017-01-03T00:00Z with 20 staff on shift and 1 max desk " +
    "When I ask for 1 day of forecast on 2017-01-02T00:00Z " +
    "Then I should see the actual staff numbers in the forecast" >> {

    val day1 = "2017-01-02T00:00Z"
    val weekBeginning = "2017-01-02T00:00Z"

    val forecastArrivalDay1 = ArrivalGenerator.apiFlight(flightId = Option(1), schDt = day1, iata = "BA0001", terminal = "T1", actPax = Option(5))
    val forecastFlights = Flights(List(forecastArrivalDay1))

    val crunch = runCrunchGraph(
      now = () => SDate(weekBeginning).addDays(-1),
      airportConfig = airportConfig.copy(
        minMaxDesksByTerminalQueue = Map("T1" -> Map(Queues.EeaDesk -> ((List.fill[Int](24)(0), List.fill[Int](24)(1)))))
      ),
      minutesToCrunch = 60,
      initialShifts =
        """shift a,T1,02/01/17,00:00,23:59,20
        """.stripMargin,
      cruncher = TryRenjin.crunch
    )

    crunch.forecastTestProbe.receiveOne(5 seconds)

    offerAndWait(crunch.baseArrivalsInput, forecastFlights)

    val expected = List(
      ForecastTimeSlot(SDate("2017-01-02T00:00Z").millisSinceEpoch, 20, 1),
      ForecastTimeSlot(SDate("2017-01-02T00:15Z").millisSinceEpoch, 20, 0),
      ForecastTimeSlot(SDate("2017-01-02T00:30Z").millisSinceEpoch, 20, 0),
      ForecastTimeSlot(SDate("2017-01-02T00:45Z").millisSinceEpoch, 20, 0)
    )

    crunch.forecastTestProbe.fishForMessage(10 seconds) {
      case ps: PortState =>
        val weekOf15MinSlots: Map[MillisSinceEpoch, Seq[ForecastTimeSlot]] = Forecast.rollUpForWeek(
          ps.crunchMinutes.values.toSet,
          ps.staffMinutes.values.toSet,
          "T1"
        )
        val firstDayFirstHour = weekOf15MinSlots.getOrElse(SDate("2017-01-02T00:00Z").millisSinceEpoch, Seq()).take(4)

        firstDayFirstHour == expected
    }

    true
  }
}
