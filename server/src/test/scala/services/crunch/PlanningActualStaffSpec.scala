package services.crunch

import controllers.{ArrivalGenerator, Forecast}
import drt.shared.FlightsApi.Flights
import drt.shared._
import server.feeds.ArrivalsFeedSuccess
import services.{SDate, TryRenjin}
import services.graphstages.Crunch._

import scala.concurrent.Await
import scala.concurrent.duration._

class PlanningActualStaffSpec() extends CrunchTestLike {
  sequential
  isolated

  val slot0To14 = 0 * 60000
  val slot15To29 = 15 * 60000
  val slot30To44 = 30 * 60000
  val slot45To59 = 45 * 60000

  import CrunchApi._

  "Given a forecast arriving on 2017-01-02T00:00Z with 5 pax and on 2017-01-03T00:00Z with 20 staff on shift and 1 max desk" +
    "When I ask for 1 day of forecast on 2017-01-02T00:00Z " +
    "Then I should see the actual staff numbers in the forecast" >> {

    val day1 = "2017-01-02T00:00Z"
    val weekBeginning = "2017-01-02T00:00Z"

    val forecastArrivalDay1 = ArrivalGenerator.apiFlight(flightId = Option(1), schDt = day1, iata = "BA0001", terminal = "T1", actPax = Option(5))
    val forecastFlights = Set(forecastArrivalDay1)
    val startDate1 = MilliDate(SDate("2017-01-02T00:00").millisSinceEpoch)
    val endDate1 = MilliDate(SDate("2017-01-02T23:59").millisSinceEpoch)
    val assignment1 = StaffAssignment("shift a", "T1", startDate1, endDate1, 20, None)

    val crunch = runCrunchGraph(
      now = () => SDate(weekBeginning).addDays(-1),
      airportConfig = airportConfig.copy(
        terminalNames = Seq("T1"),
        minMaxDesksByTerminalQueue = Map("T1" -> Map(Queues.EeaDesk -> ((List.fill[Int](24)(0), List.fill[Int](24)(1)))))
      ),
      cruncher = TryRenjin.crunch
    )

    Await.ready(crunch.baseArrivalsInput.offer(ArrivalsFeedSuccess(Flights(forecastFlights.toSeq))), 1 second)
    Await.ready(crunch.shiftsInput.offer(ShiftAssignments(Seq(assignment1))), 1 second)

    val expected = List(
      ForecastTimeSlot(SDate("2017-01-02T00:00Z").millisSinceEpoch, 20, 1),
      ForecastTimeSlot(SDate("2017-01-02T00:15Z").millisSinceEpoch, 20, 0),
      ForecastTimeSlot(SDate("2017-01-02T00:30Z").millisSinceEpoch, 20, 0),
      ForecastTimeSlot(SDate("2017-01-02T00:45Z").millisSinceEpoch, 20, 0)
    )

    crunch.forecastTestProbe.fishForMessage(10 seconds) {
      case ps: PortState =>
        val weekOf15MinSlots: Map[MillisSinceEpoch, Seq[ForecastTimeSlot]] = Forecast.rollUpForWeek(
          ps.crunchMinutes,
          ps.staffMinutes,
          "T1")

        val firstDayFirstHour = weekOf15MinSlots.getOrElse(SDate("2017-01-02T00:00Z").millisSinceEpoch, Seq()).take(4)

        firstDayFirstHour == expected
    }

    crunch.liveArrivalsInput.complete()

    success
  }

  "Given a list of staff numbers for every minute, when I group by 15 minutes, " +
    "Then I should get the lowest number in each 15 minute block" >> {

    val staffMinutes: Set[StaffMinute] = ((0 to 58)
      .map(index => {
        StaffMinute(terminalName = "T1", minute = index * 60000, shifts = 20, fixedPoints = 2, movements = 1, lastUpdated = None)
      })
      :+ StaffMinute(terminalName = "T1", minute = 59 * 60000, shifts = 10, fixedPoints = 1, movements = 0, lastUpdated = None)).toSet

    val staffAvailable: Map[MillisSinceEpoch, Int] = staffByTimeSlot(15)(staffMinutes, "T1")

    val expected = Map(
      slot0To14 -> 20,
      slot15To29 -> 20,
      slot30To44 -> 20,
      slot45To59 -> 10)

    staffAvailable === expected
  }

  "Given a set of forecast minutes and staff minutes for all terminals, " +
    "When I roll up for week per terminal " +
    "Then I should get the lowest number in each 15 minute block relevant to the particular terminal" >> {

    val staffMinutesT1: Set[StaffMinute] = (0 to 59)
      .map(index => StaffMinute(terminalName = "T1", minute = index * 60000, shifts = 20, fixedPoints = 2, movements = 1, lastUpdated = None)).toSet
    val staffMinutesT2 = staffMinutesT1.map(_.copy(terminalName = "T2", fixedPoints = 3))

    val crunchMinutesT1: Set[CrunchMinute] = (0 to 58)
      .map(index => CrunchMinute(terminalName = "T1", queueName = Queues.EeaDesk, minute = index * 60000,
        lastUpdated = None, paxLoad = 0d, workLoad = 0d, deskRec = 1, waitTime = 0)).toSet
    val crunchMinutesT2 = crunchMinutesT1.map(_.copy(terminalName = "T2", deskRec = 2))

    val crunchMinutes = (crunchMinutesT1 ++ crunchMinutesT2).map(cm => (TQM(cm), cm)).toMap
    val staffMinutes = (staffMinutesT1 ++ staffMinutesT2).map(sm => (TM(sm), sm)).toMap

    val result = Forecast
      .rollUpForWeek(crunchMinutes, staffMinutes, "T1")
      .values.head.toSet

    val expected = Set(
      ForecastTimeSlot(0, 20, 3),
      ForecastTimeSlot(15 * 60000, 20, 3),
      ForecastTimeSlot(30 * 60000, 20, 3),
      ForecastTimeSlot(45 * 60000, 20, 3)
    )

    result === expected
  }
}
