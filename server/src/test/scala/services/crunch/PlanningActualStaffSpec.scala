package services.crunch

import controllers.{ArrivalGenerator, Forecast}
import drt.shared.FlightsApi.Flights
import drt.shared.{CrunchApi, Queues}
import services.SDate
import services.graphstages.Crunch._

import scala.collection.immutable.{List, Seq}
import scala.concurrent.duration._

class PlanningActualStaffSpec() extends CrunchTestLike {

  import CrunchApi._

    "Given a forecast arriving on 2017-01-02T00:00Z with 5 pax and on 2017-01-03T00:00Z with 20 staff on shift and 1 max desk" +
      "When I ask for 1 day of forecast on 2017-01-02T00:00Z " >> {

      val day1 = "2017-01-02T00:00Z"
      val weekbeginning = "2017-01-02T00:00Z"

      val forecastArrivalDay1 = ArrivalGenerator.apiFlight(flightId = 1, schDt = day1, iata = "BA0001", terminal = "T1", actPax = 5)
      val forecastFlights = Flights(List(forecastArrivalDay1))

      val crunch = runCrunchGraph(
        now = () => SDate(weekbeginning),
        minutesToCrunch = 1440,
        minMaxDesks = Map("T1" -> Map(Queues.EeaDesk -> ((List.fill[Int](24)(0), List.fill[Int](24)(1))))),
        crunchStartDateProvider = (_) => getLocalLastMidnight(SDate(weekbeginning)),
        crunchEndDateProvider = (_) => getLocalLastMidnight(SDate(weekbeginning)).addDays(3),
        shifts =
          """shift a,T1,02/01/17,00:00,23:59,20
          """.stripMargin
      )

      crunch.baseArrivalsInput.offer(forecastFlights)

      val forecastResult = crunch.forecastTestProbe.expectMsgAnyClassOf(30 seconds, classOf[PortState])

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

  "Given a list of staff numbers for every minute, when I group by 15 minutes, " +
    "Then I should get the lowest number in each 15 minute block" >> {

    val staffMinutes: Set[StaffMinute] = ((0 to 58).map(index => {

      StaffMinute("T1", index * 60000, 20, 0, 0, None)
    }) :+ StaffMinute("T1", 59 * 60000, 10, 0, 0, None)).toSet

    val staffAvailable: Map[MillisSinceEpoch, Int] = staffByTimeSlot(15)(staffMinutes)


    val expected = Map(0 -> 20, 15 * 60000 -> 20, 30 * 60000 -> 20, 45 * 60000 -> 10)

    staffAvailable === expected
  }
}
