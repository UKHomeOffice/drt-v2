package services.crunch

import drt.shared.CrunchApi._
import drt.shared.{Forecast, MilliDate, Queues, SDateLike}
import org.specs2.mutable.Specification
import services.graphstages.Crunch
import services.graphstages.Crunch.getLocalLastMidnight
import services.{CSVData, SDate}

class ForecastPlanningToCSVDataTest extends Specification {

  "Forecast Planning export" >> {
    "Given a ForecastPeriod with 1 day when we export to CSV, we should see the same data as a CSV" >> {
      val day1Midnight = SDate("2017-10-25T00:00:00Z")
      val t0000Millis = day1Midnight.millisSinceEpoch
      val t0015Millis = day1Midnight.addMinutes(15).millisSinceEpoch
      val t0030Millis = day1Midnight.addMinutes(30).millisSinceEpoch
      val t0045Millis = day1Midnight.addMinutes(45).millisSinceEpoch
      val t0100Millis = day1Midnight.addMinutes(60).millisSinceEpoch

      val forecast = ForecastPeriod(Map(
        t0000Millis -> Seq(
          ForecastTimeSlot(t0000Millis, 1, 2),
          ForecastTimeSlot(t0015Millis, 3, 4),
          ForecastTimeSlot(t0030Millis, 5, 6),
          ForecastTimeSlot(t0045Millis, 7, 8),
          ForecastTimeSlot(t0100Millis, 9, 10)
        )
      ))

      val result = CSVData.forecastPeriodToCsv(forecast)

      val expected =
        s"""|,${day1Midnight.getDate()}/${day1Midnight.getMonth()} - available,25/10 - required,25/10 - difference
            |01:00,1,2,-1
            |01:15,3,4,-1
            |01:30,5,6,-1
            |01:45,7,8,-1
            |02:00,9,10,-1""".stripMargin

      result === expected
    }

    "Given ForecastPeriod with no data, we should get an empty list of timeslots" >> {

      val forecast = ForecastPeriod(Map())

      val result = Forecast.timeSlotStartTimes(forecast, CSVData.millisToHoursAndMinutesString)

      val expected = List()

      result === expected
    }

    "Given a ForecastPeriod with 3 days when we export to CSV, we should see the same data as a CSV" >> {
      val day1Midnight = SDate("2017-10-25T00:00:00Z")
      val d1t0000Millis = day1Midnight.millisSinceEpoch
      val d1t0015Millis = day1Midnight.addMinutes(15).millisSinceEpoch
      val d1t0030Millis = day1Midnight.addMinutes(30).millisSinceEpoch
      val d1t0045Millis = day1Midnight.addMinutes(45).millisSinceEpoch
      val d1t0100Millis = day1Midnight.addMinutes(60).millisSinceEpoch

      val day2Midnight = day1Midnight.addDays(1)
      val d2t0000Millis = day2Midnight.millisSinceEpoch
      val d2t0015Millis = day2Midnight.addMinutes(15).millisSinceEpoch
      val d2t0030Millis = day2Midnight.addMinutes(30).millisSinceEpoch
      val d2t0045Millis = day2Midnight.addMinutes(45).millisSinceEpoch
      val d2t0100Millis = day2Midnight.addMinutes(60).millisSinceEpoch

      val day3Midnight = day2Midnight.addDays(1)
      val d3t0000Millis = day3Midnight.millisSinceEpoch
      val d3t0015Millis = day3Midnight.addMinutes(15).millisSinceEpoch
      val d3t0030Millis = day3Midnight.addMinutes(30).millisSinceEpoch
      val d3t0045Millis = day3Midnight.addMinutes(45).millisSinceEpoch
      val d3t0100Millis = day3Midnight.addMinutes(60).millisSinceEpoch

      val forecast = ForecastPeriod(Map(
        d1t0000Millis -> Seq(
          ForecastTimeSlot(d1t0000Millis, 1, 2),
          ForecastTimeSlot(d1t0015Millis, 3, 4),
          ForecastTimeSlot(d1t0030Millis, 5, 6),
          ForecastTimeSlot(d1t0045Millis, 7, 8),
          ForecastTimeSlot(d1t0100Millis, 9, 10)
        ),
        d2t0000Millis -> Seq(
          ForecastTimeSlot(d1t0000Millis, 1, 2),
          ForecastTimeSlot(d1t0015Millis, 3, 4),
          ForecastTimeSlot(d1t0030Millis, 5, 6),
          ForecastTimeSlot(d1t0045Millis, 7, 8),
          ForecastTimeSlot(d1t0100Millis, 9, 10)
        ),
        d3t0000Millis -> Seq(
          ForecastTimeSlot(d1t0000Millis, 1, 2),
          ForecastTimeSlot(d1t0015Millis, 3, 4),
          ForecastTimeSlot(d1t0030Millis, 5, 6),
          ForecastTimeSlot(d1t0045Millis, 7, 8),
          ForecastTimeSlot(d1t0100Millis, 9, 10)
        )
      ))

      val result = CSVData.forecastPeriodToCsv(forecast)

      val dt1 = s"""${day1Midnight.getDate()}/${day1Midnight.getMonth()}"""
      val dt2 = s"""${day2Midnight.getDate()}/${day2Midnight.getMonth()}"""
      val dt3 = s"""${day3Midnight.getDate()}/${day3Midnight.getMonth()}"""
      val expected =
        s"""|,$dt1 - available,$dt1 - required,$dt1 - difference,$dt2 - available,$dt2 - required,$dt2 - difference,$dt3 - available,$dt3 - required,$dt3 - difference
            |01:00,1,2,-1,1,2,-1,1,2,-1
            |01:15,3,4,-1,3,4,-1,3,4,-1
            |01:30,5,6,-1,5,6,-1,5,6,-1
            |01:45,7,8,-1,7,8,-1,7,8,-1
            |02:00,9,10,-1,9,10,-1,9,10,-1""".stripMargin

      result === expected
    }
  }

  "Given a ForecastPeriod which includes a period that spans a timezone change from BST to UTC " +
    "Then I should get back a rectangular List of Lists of ForecastTimeSlot Options containing 100 slots each" >> {

    val forecastPeriodDays: Map[MillisSinceEpoch, Seq[ForecastTimeSlot]] = forecastForPeriodStartingOnDay(2, SDate("2018-10-28T00:00:00Z")).days

    val result = Forecast.handleBSTToUTC(forecastPeriodDays).toList.map(_._2.length)

    val expected = 100 :: 100 :: Nil

    result === expected
  }

  "Given a ForecastPeriod which includes a period that spans a timezone change from BST to UTC " +
    "Then row headings should include 2 entries for 1am" >> {

    val forecastPeriod = forecastForPeriodStartingOnDay(2, SDate("2018-10-28T00:00:00Z"))

    val result = Forecast.timeSlotStartTimes(forecastPeriod, CSVData.millisToHoursAndMinutesString).take(12)

    val expected = List(
      "00:00", "00:15", "00:30", "00:45",
      "01:00", "01:15", "01:30", "01:45",
      "01:00", "01:15", "01:30", "01:45"
    )
    result === expected
  }

  "Given a ForecastPeriod which includes a period that spans a timezone change from BST to UTC " +
    "Then I should get a CSV with two rows for 1am with empty values for all days other than the timezone change day" >> {
    val forecastPeriodDays: ForecastPeriod = forecastForPeriodStartingOnDay(2, SDate("2018-10-28T00:00:00Z"))
    val result = CSVData.forecastPeriodToCsv(forecastPeriodDays)

    val expected =
      s"""|,28/10 - available,28/10 - required,28/10 - difference,29/10 - available,29/10 - required,29/10 - difference
          |00:00,1,1,0,1,1,0
          |00:15,1,1,0,1,1,0
          |00:30,1,1,0,1,1,0
          |00:45,1,1,0,1,1,0
          |01:00,1,1,0,1,1,0
          |01:15,1,1,0,1,1,0
          |01:30,1,1,0,1,1,0
          |01:45,1,1,0,1,1,0
          |01:00,1,1,0,,,
          |01:15,1,1,0,,,
          |01:30,1,1,0,,,
          |01:45,1,1,0,,,
          |02:00,1,1,0,1,1,0
          |02:15,1,1,0,1,1,0
          |02:30,1,1,0,1,1,0
          |02:45,1,1,0,1,1,0
          |03:00,1,1,0,1,1,0
          |03:15,1,1,0,1,1,0
          |03:30,1,1,0,1,1,0
          |03:45,1,1,0,1,1,0
          |04:00,1,1,0,1,1,0
          |04:15,1,1,0,1,1,0
          |04:30,1,1,0,1,1,0
          |04:45,1,1,0,1,1,0
          |05:00,1,1,0,1,1,0
          |05:15,1,1,0,1,1,0
          |05:30,1,1,0,1,1,0
          |05:45,1,1,0,1,1,0
          |06:00,1,1,0,1,1,0
          |06:15,1,1,0,1,1,0
          |06:30,1,1,0,1,1,0
          |06:45,1,1,0,1,1,0
          |07:00,1,1,0,1,1,0
          |07:15,1,1,0,1,1,0
          |07:30,1,1,0,1,1,0
          |07:45,1,1,0,1,1,0
          |08:00,1,1,0,1,1,0
          |08:15,1,1,0,1,1,0
          |08:30,1,1,0,1,1,0
          |08:45,1,1,0,1,1,0
          |09:00,1,1,0,1,1,0
          |09:15,1,1,0,1,1,0
          |09:30,1,1,0,1,1,0
          |09:45,1,1,0,1,1,0
          |10:00,1,1,0,1,1,0
          |10:15,1,1,0,1,1,0
          |10:30,1,1,0,1,1,0
          |10:45,1,1,0,1,1,0
          |11:00,1,1,0,1,1,0
          |11:15,1,1,0,1,1,0
          |11:30,1,1,0,1,1,0
          |11:45,1,1,0,1,1,0
          |12:00,1,1,0,1,1,0
          |12:15,1,1,0,1,1,0
          |12:30,1,1,0,1,1,0
          |12:45,1,1,0,1,1,0
          |13:00,1,1,0,1,1,0
          |13:15,1,1,0,1,1,0
          |13:30,1,1,0,1,1,0
          |13:45,1,1,0,1,1,0
          |14:00,1,1,0,1,1,0
          |14:15,1,1,0,1,1,0
          |14:30,1,1,0,1,1,0
          |14:45,1,1,0,1,1,0
          |15:00,1,1,0,1,1,0
          |15:15,1,1,0,1,1,0
          |15:30,1,1,0,1,1,0
          |15:45,1,1,0,1,1,0
          |16:00,1,1,0,1,1,0
          |16:15,1,1,0,1,1,0
          |16:30,1,1,0,1,1,0
          |16:45,1,1,0,1,1,0
          |17:00,1,1,0,1,1,0
          |17:15,1,1,0,1,1,0
          |17:30,1,1,0,1,1,0
          |17:45,1,1,0,1,1,0
          |18:00,1,1,0,1,1,0
          |18:15,1,1,0,1,1,0
          |18:30,1,1,0,1,1,0
          |18:45,1,1,0,1,1,0
          |19:00,1,1,0,1,1,0
          |19:15,1,1,0,1,1,0
          |19:30,1,1,0,1,1,0
          |19:45,1,1,0,1,1,0
          |20:00,1,1,0,1,1,0
          |20:15,1,1,0,1,1,0
          |20:30,1,1,0,1,1,0
          |20:45,1,1,0,1,1,0
          |21:00,1,1,0,1,1,0
          |21:15,1,1,0,1,1,0
          |21:30,1,1,0,1,1,0
          |21:45,1,1,0,1,1,0
          |22:00,1,1,0,1,1,0
          |22:15,1,1,0,1,1,0
          |22:30,1,1,0,1,1,0
          |22:45,1,1,0,1,1,0
          |23:00,1,1,0,1,1,0
          |23:15,1,1,0,1,1,0
          |23:30,1,1,0,1,1,0
          |23:45,1,1,0,1,1,0""".stripMargin

    result === expected
  }

  "Given a ForecastPeriod which includes a period that spans a timezone change from BST to UTC starting before the switch date " +
    "Then I should get a CSV with two rows for 1am with empty values for all days other than the timezone change day" >> {
    val forecastPeriodDays: ForecastPeriod = forecastForPeriodStartingOnDay(3, SDate("2018-10-27T00:00:00Z"))
    val result = CSVData.forecastPeriodToCsv(forecastPeriodDays)

    val expected =
      s"""|,27/10 - available,27/10 - required,27/10 - difference,28/10 - available,28/10 - required,28/10 - difference,29/10 - available,29/10 - required,29/10 - difference
          |00:00,1,1,0,1,1,0,1,1,0
          |00:15,1,1,0,1,1,0,1,1,0
          |00:30,1,1,0,1,1,0,1,1,0
          |00:45,1,1,0,1,1,0,1,1,0
          |01:00,1,1,0,1,1,0,1,1,0
          |01:15,1,1,0,1,1,0,1,1,0
          |01:30,1,1,0,1,1,0,1,1,0
          |01:45,1,1,0,1,1,0,1,1,0
          |01:00,,,,1,1,0,,,
          |01:15,,,,1,1,0,,,
          |01:30,,,,1,1,0,,,
          |01:45,,,,1,1,0,,,
          |02:00,1,1,0,1,1,0,1,1,0
          |02:15,1,1,0,1,1,0,1,1,0
          |02:30,1,1,0,1,1,0,1,1,0
          |02:45,1,1,0,1,1,0,1,1,0
          |03:00,1,1,0,1,1,0,1,1,0
          |03:15,1,1,0,1,1,0,1,1,0
          |03:30,1,1,0,1,1,0,1,1,0
          |03:45,1,1,0,1,1,0,1,1,0
          |04:00,1,1,0,1,1,0,1,1,0
          |04:15,1,1,0,1,1,0,1,1,0
          |04:30,1,1,0,1,1,0,1,1,0
          |04:45,1,1,0,1,1,0,1,1,0
          |05:00,1,1,0,1,1,0,1,1,0
          |05:15,1,1,0,1,1,0,1,1,0
          |05:30,1,1,0,1,1,0,1,1,0
          |05:45,1,1,0,1,1,0,1,1,0
          |06:00,1,1,0,1,1,0,1,1,0
          |06:15,1,1,0,1,1,0,1,1,0
          |06:30,1,1,0,1,1,0,1,1,0
          |06:45,1,1,0,1,1,0,1,1,0
          |07:00,1,1,0,1,1,0,1,1,0
          |07:15,1,1,0,1,1,0,1,1,0
          |07:30,1,1,0,1,1,0,1,1,0
          |07:45,1,1,0,1,1,0,1,1,0
          |08:00,1,1,0,1,1,0,1,1,0
          |08:15,1,1,0,1,1,0,1,1,0
          |08:30,1,1,0,1,1,0,1,1,0
          |08:45,1,1,0,1,1,0,1,1,0
          |09:00,1,1,0,1,1,0,1,1,0
          |09:15,1,1,0,1,1,0,1,1,0
          |09:30,1,1,0,1,1,0,1,1,0
          |09:45,1,1,0,1,1,0,1,1,0
          |10:00,1,1,0,1,1,0,1,1,0
          |10:15,1,1,0,1,1,0,1,1,0
          |10:30,1,1,0,1,1,0,1,1,0
          |10:45,1,1,0,1,1,0,1,1,0
          |11:00,1,1,0,1,1,0,1,1,0
          |11:15,1,1,0,1,1,0,1,1,0
          |11:30,1,1,0,1,1,0,1,1,0
          |11:45,1,1,0,1,1,0,1,1,0
          |12:00,1,1,0,1,1,0,1,1,0
          |12:15,1,1,0,1,1,0,1,1,0
          |12:30,1,1,0,1,1,0,1,1,0
          |12:45,1,1,0,1,1,0,1,1,0
          |13:00,1,1,0,1,1,0,1,1,0
          |13:15,1,1,0,1,1,0,1,1,0
          |13:30,1,1,0,1,1,0,1,1,0
          |13:45,1,1,0,1,1,0,1,1,0
          |14:00,1,1,0,1,1,0,1,1,0
          |14:15,1,1,0,1,1,0,1,1,0
          |14:30,1,1,0,1,1,0,1,1,0
          |14:45,1,1,0,1,1,0,1,1,0
          |15:00,1,1,0,1,1,0,1,1,0
          |15:15,1,1,0,1,1,0,1,1,0
          |15:30,1,1,0,1,1,0,1,1,0
          |15:45,1,1,0,1,1,0,1,1,0
          |16:00,1,1,0,1,1,0,1,1,0
          |16:15,1,1,0,1,1,0,1,1,0
          |16:30,1,1,0,1,1,0,1,1,0
          |16:45,1,1,0,1,1,0,1,1,0
          |17:00,1,1,0,1,1,0,1,1,0
          |17:15,1,1,0,1,1,0,1,1,0
          |17:30,1,1,0,1,1,0,1,1,0
          |17:45,1,1,0,1,1,0,1,1,0
          |18:00,1,1,0,1,1,0,1,1,0
          |18:15,1,1,0,1,1,0,1,1,0
          |18:30,1,1,0,1,1,0,1,1,0
          |18:45,1,1,0,1,1,0,1,1,0
          |19:00,1,1,0,1,1,0,1,1,0
          |19:15,1,1,0,1,1,0,1,1,0
          |19:30,1,1,0,1,1,0,1,1,0
          |19:45,1,1,0,1,1,0,1,1,0
          |20:00,1,1,0,1,1,0,1,1,0
          |20:15,1,1,0,1,1,0,1,1,0
          |20:30,1,1,0,1,1,0,1,1,0
          |20:45,1,1,0,1,1,0,1,1,0
          |21:00,1,1,0,1,1,0,1,1,0
          |21:15,1,1,0,1,1,0,1,1,0
          |21:30,1,1,0,1,1,0,1,1,0
          |21:45,1,1,0,1,1,0,1,1,0
          |22:00,1,1,0,1,1,0,1,1,0
          |22:15,1,1,0,1,1,0,1,1,0
          |22:30,1,1,0,1,1,0,1,1,0
          |22:45,1,1,0,1,1,0,1,1,0
          |23:00,1,1,0,1,1,0,1,1,0
          |23:15,1,1,0,1,1,0,1,1,0
          |23:30,1,1,0,1,1,0,1,1,0
          |23:45,1,1,0,1,1,0,1,1,0""".stripMargin

    result === expected
  }

  "Given a ForecastPeriod which includes a period that spans a timezone change from UTC to BST " +
    "Then then the switch over day should have blank entries for the period between 1am and 2am" >> {

    val forecastPeriodDays: ForecastPeriod = forecastForPeriodStartingOnDay(3, SDate("2019-03-30T00:00:00Z"))
    val result = CSVData.forecastPeriodToCsv(forecastPeriodDays)

    val expected =
      s"""|,30/03 - available,30/03 - required,30/03 - difference,31/03 - available,31/03 - required,31/03 - difference,01/04 - available,01/04 - required,01/04 - difference
          |00:00,1,1,0,1,1,0,1,1,0
          |00:15,1,1,0,1,1,0,1,1,0
          |00:30,1,1,0,1,1,0,1,1,0
          |00:45,1,1,0,1,1,0,1,1,0
          |01:00,1,1,0,,,,1,1,0
          |01:15,1,1,0,,,,1,1,0
          |01:30,1,1,0,,,,1,1,0
          |01:45,1,1,0,,,,1,1,0
          |02:00,1,1,0,1,1,0,1,1,0
          |02:15,1,1,0,1,1,0,1,1,0
          |02:30,1,1,0,1,1,0,1,1,0
          |02:45,1,1,0,1,1,0,1,1,0
          |03:00,1,1,0,1,1,0,1,1,0
          |03:15,1,1,0,1,1,0,1,1,0
          |03:30,1,1,0,1,1,0,1,1,0
          |03:45,1,1,0,1,1,0,1,1,0
          |04:00,1,1,0,1,1,0,1,1,0
          |04:15,1,1,0,1,1,0,1,1,0
          |04:30,1,1,0,1,1,0,1,1,0
          |04:45,1,1,0,1,1,0,1,1,0
          |05:00,1,1,0,1,1,0,1,1,0
          |05:15,1,1,0,1,1,0,1,1,0
          |05:30,1,1,0,1,1,0,1,1,0
          |05:45,1,1,0,1,1,0,1,1,0
          |06:00,1,1,0,1,1,0,1,1,0
          |06:15,1,1,0,1,1,0,1,1,0
          |06:30,1,1,0,1,1,0,1,1,0
          |06:45,1,1,0,1,1,0,1,1,0
          |07:00,1,1,0,1,1,0,1,1,0
          |07:15,1,1,0,1,1,0,1,1,0
          |07:30,1,1,0,1,1,0,1,1,0
          |07:45,1,1,0,1,1,0,1,1,0
          |08:00,1,1,0,1,1,0,1,1,0
          |08:15,1,1,0,1,1,0,1,1,0
          |08:30,1,1,0,1,1,0,1,1,0
          |08:45,1,1,0,1,1,0,1,1,0
          |09:00,1,1,0,1,1,0,1,1,0
          |09:15,1,1,0,1,1,0,1,1,0
          |09:30,1,1,0,1,1,0,1,1,0
          |09:45,1,1,0,1,1,0,1,1,0
          |10:00,1,1,0,1,1,0,1,1,0
          |10:15,1,1,0,1,1,0,1,1,0
          |10:30,1,1,0,1,1,0,1,1,0
          |10:45,1,1,0,1,1,0,1,1,0
          |11:00,1,1,0,1,1,0,1,1,0
          |11:15,1,1,0,1,1,0,1,1,0
          |11:30,1,1,0,1,1,0,1,1,0
          |11:45,1,1,0,1,1,0,1,1,0
          |12:00,1,1,0,1,1,0,1,1,0
          |12:15,1,1,0,1,1,0,1,1,0
          |12:30,1,1,0,1,1,0,1,1,0
          |12:45,1,1,0,1,1,0,1,1,0
          |13:00,1,1,0,1,1,0,1,1,0
          |13:15,1,1,0,1,1,0,1,1,0
          |13:30,1,1,0,1,1,0,1,1,0
          |13:45,1,1,0,1,1,0,1,1,0
          |14:00,1,1,0,1,1,0,1,1,0
          |14:15,1,1,0,1,1,0,1,1,0
          |14:30,1,1,0,1,1,0,1,1,0
          |14:45,1,1,0,1,1,0,1,1,0
          |15:00,1,1,0,1,1,0,1,1,0
          |15:15,1,1,0,1,1,0,1,1,0
          |15:30,1,1,0,1,1,0,1,1,0
          |15:45,1,1,0,1,1,0,1,1,0
          |16:00,1,1,0,1,1,0,1,1,0
          |16:15,1,1,0,1,1,0,1,1,0
          |16:30,1,1,0,1,1,0,1,1,0
          |16:45,1,1,0,1,1,0,1,1,0
          |17:00,1,1,0,1,1,0,1,1,0
          |17:15,1,1,0,1,1,0,1,1,0
          |17:30,1,1,0,1,1,0,1,1,0
          |17:45,1,1,0,1,1,0,1,1,0
          |18:00,1,1,0,1,1,0,1,1,0
          |18:15,1,1,0,1,1,0,1,1,0
          |18:30,1,1,0,1,1,0,1,1,0
          |18:45,1,1,0,1,1,0,1,1,0
          |19:00,1,1,0,1,1,0,1,1,0
          |19:15,1,1,0,1,1,0,1,1,0
          |19:30,1,1,0,1,1,0,1,1,0
          |19:45,1,1,0,1,1,0,1,1,0
          |20:00,1,1,0,1,1,0,1,1,0
          |20:15,1,1,0,1,1,0,1,1,0
          |20:30,1,1,0,1,1,0,1,1,0
          |20:45,1,1,0,1,1,0,1,1,0
          |21:00,1,1,0,1,1,0,1,1,0
          |21:15,1,1,0,1,1,0,1,1,0
          |21:30,1,1,0,1,1,0,1,1,0
          |21:45,1,1,0,1,1,0,1,1,0
          |22:00,1,1,0,1,1,0,1,1,0
          |22:15,1,1,0,1,1,0,1,1,0
          |22:30,1,1,0,1,1,0,1,1,0
          |22:45,1,1,0,1,1,0,1,1,0
          |23:00,1,1,0,1,1,0,1,1,0
          |23:15,1,1,0,1,1,0,1,1,0
          |23:30,1,1,0,1,1,0,1,1,0
          |23:45,1,1,0,1,1,0,1,1,0""".stripMargin

    result === expected
  }

  def forecastForPeriodStartingOnDay(daysToAdd: Int, startDate: SDateLike) = {
    val forecastStart = getLocalLastMidnight(startDate)
    val forecastEnd = forecastStart.addDays(daysToAdd)

    val range = forecastStart.millisSinceEpoch until forecastEnd.millisSinceEpoch by (15 * 60 * 1000)
    val days = range.toList.groupBy(m => getLocalLastMidnight(MilliDate(m)).millisSinceEpoch)

    ForecastPeriod(days.mapValues(_.map(ts => ForecastTimeSlot(ts, 1, 1))))
  }

  "Forecast Headline Export" >> {
    "Given forecast headline figures for 3 days, then I should get those days exported as a CSV" >> {
      val day1StartMinute = SDate("2017-01-01T00:00Z")
      val day2StartMinute = SDate("2017-01-02T00:00Z")
      val day3StartMinute = SDate("2017-01-03T00:00Z")

      val headlines = ForecastHeadlineFigures(Seq(
        QueueHeadline(day1StartMinute.millisSinceEpoch, Queues.EeaDesk, 1, 2),
        QueueHeadline(day1StartMinute.millisSinceEpoch, Queues.EGate, 1, 2),
        QueueHeadline(day2StartMinute.millisSinceEpoch, Queues.EeaDesk, 1, 2),
        QueueHeadline(day2StartMinute.millisSinceEpoch, Queues.EGate, 1, 2),
        QueueHeadline(day3StartMinute.millisSinceEpoch, Queues.EeaDesk, 1, 2),
        QueueHeadline(day3StartMinute.millisSinceEpoch, Queues.EGate, 1, 2)
      ))

      val result = CSVData.forecastHeadlineToCSV(headlines, Queues.exportQueueOrderSansFastTrack)

      val expected =
        f"""|,${day1StartMinute.getDate()}%02d/${day1StartMinute.getMonth()}%02d,${day2StartMinute.getDate()}%02d/${day2StartMinute.getMonth()}%02d,${day3StartMinute.getDate()}%02d/${day3StartMinute.getMonth()}%02d
            |Total Pax,2,2,2
            |EEA,1,1,1
            |e-Gates,1,1,1
            |Total Workload,4,4,4""".stripMargin

      result === expected
    }
  }
}
