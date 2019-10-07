package services.crunch

import controllers.{ArrivalGenerator, application}
import drt.shared.FlightsApi.Flights
import drt.shared._
import server.feeds.ArrivalsFeedSuccess
import services.{SDate, TryRenjin}

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

    val forecastArrivalDay1 = ArrivalGenerator.arrival(flightId = Option(1), schDt = day1, iata = "BA0001", terminal = "T1", actPax = Option(5))
    val baseFlights = Flights(List(forecastArrivalDay1))

    val startDate1 = MilliDate(SDate("2017-01-02T00:00").millisSinceEpoch)
    val endDate1 = MilliDate(SDate("2017-01-02T23:59").millisSinceEpoch)
    val assignment1 = StaffAssignment("shift a", "T1", startDate1, endDate1, 20, None)
    val crunch = runCrunchGraph(
      now = () => SDate(weekBeginning).addDays(-1),
      airportConfig = airportConfig.copy(
        minMaxDesksByTerminalQueue = Map("T1" -> Map(Queues.EeaDesk -> ((List.fill[Int](24)(0), List.fill[Int](24)(1)))))
      ),
      initialShifts = ShiftAssignments(Seq(assignment1)),
      cruncher = TryRenjin.crunch,
      checkRequiredStaffUpdatesOnStartup = true
    )

    crunch.forecastTestProbe.receiveOne(5 seconds)

    offerAndWait(crunch.baseArrivalsInput, ArrivalsFeedSuccess(baseFlights))

    val expected = List(
      ForecastTimeSlot(SDate("2017-01-02T00:00Z").millisSinceEpoch, 20, 1),
      ForecastTimeSlot(SDate("2017-01-02T00:15Z").millisSinceEpoch, 20, 0),
      ForecastTimeSlot(SDate("2017-01-02T00:30Z").millisSinceEpoch, 20, 0),
      ForecastTimeSlot(SDate("2017-01-02T00:45Z").millisSinceEpoch, 20, 0)
    )

    crunch.forecastTestProbe.fishForMessage(5 seconds) {
      case ps: PortState =>
        val cs = ps.crunchSummary(SDate(startDate1), 4, 15, "T1", airportConfig.queues("T1").toList)
        val ss = ps.staffSummary(SDate(startDate1), 4, 15, "T1")
        val weekOf15MinSlots: Map[MillisSinceEpoch, Seq[ForecastTimeSlot]] = application.Forecast.rollUpForWeek(cs, ss)
        val firstDayFirstHour = weekOf15MinSlots.getOrElse(SDate("2017-01-02T00:00Z").millisSinceEpoch, Seq()).take(4)

        firstDayFirstHour == expected
    }

    crunch.liveArrivalsInput.complete()

    success
  }
}
