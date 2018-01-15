package services.crunch

import drt.shared.CrunchApi.{ForecastHeadlineFigures, ForecastPeriod, ForecastTimeSlot, QueueHeadline}
import drt.shared.Queues
import org.specs2.mutable.Specification
import services.{CSVData, SDate}

import scala.collection.immutable.Seq

class ForecastPlanningToCSVDataTest extends Specification {

//  "Forecast Planning export" >> {
//    "Given a ForecastPeriod with 1 day when we export to CSV, we should see the same data as a CSV" >> {
//      val day1Midnight = SDate("2017-10-25T00:00:00Z")
//      val t0000Millis = day1Midnight.millisSinceEpoch
//      val t0015Millis = day1Midnight.addMinutes(15).millisSinceEpoch
//      val t0030Millis = day1Midnight.addMinutes(30).millisSinceEpoch
//      val t0045Millis = day1Midnight.addMinutes(45).millisSinceEpoch
//      val t0100Millis = day1Midnight.addMinutes(60).millisSinceEpoch
//
//      val forecast = ForecastPeriod(Map(
//        t0000Millis -> Seq(
//          ForecastTimeSlot(t0000Millis, 1, 2),
//          ForecastTimeSlot(t0015Millis, 3, 4),
//          ForecastTimeSlot(t0030Millis, 5, 6),
//          ForecastTimeSlot(t0045Millis, 7, 8),
//          ForecastTimeSlot(t0100Millis, 9, 10)
//        )
//      ))
//
//      val result = CSVData.forecastPeriodToCsv(forecast)
//
//      val expected =
//        s"""|,${day1Midnight.getDate()}/${day1Midnight.getMonth()}
//            |Start Time,Avail,Rec
//            |00:00,1,2
//            |00:15,3,4
//            |00:30,5,6
//            |00:45,7,8
//            |01:00,9,10""".stripMargin
//
//      result === expected
//    }
//
//    "Given a ForecastPeriod with 3 days when we export to CSV, we should see the same data as a CSV" >> {
//      val day1Midnight = SDate("2017-10-25T00:00:00Z")
//      val d1t0000Millis = day1Midnight.millisSinceEpoch
//      val d1t0015Millis = day1Midnight.addMinutes(15).millisSinceEpoch
//      val d1t0030Millis = day1Midnight.addMinutes(30).millisSinceEpoch
//      val d1t0045Millis = day1Midnight.addMinutes(45).millisSinceEpoch
//      val d1t0100Millis = day1Midnight.addMinutes(60).millisSinceEpoch
//
//      val day2Midnight = day1Midnight.addDays(1)
//      val d2t0000Millis = day2Midnight.millisSinceEpoch
//      val d2t0015Millis = day2Midnight.addMinutes(15).millisSinceEpoch
//      val d2t0030Millis = day2Midnight.addMinutes(30).millisSinceEpoch
//      val d2t0045Millis = day2Midnight.addMinutes(45).millisSinceEpoch
//      val d2t0100Millis = day2Midnight.addMinutes(60).millisSinceEpoch
//
//      val day3Midnight = day2Midnight.addDays(1)
//      val d3t0000Millis = day3Midnight.millisSinceEpoch
//      val d3t0015Millis = day3Midnight.addMinutes(15).millisSinceEpoch
//      val d3t0030Millis = day3Midnight.addMinutes(30).millisSinceEpoch
//      val d3t0045Millis = day3Midnight.addMinutes(45).millisSinceEpoch
//      val d3t0100Millis = day3Midnight.addMinutes(60).millisSinceEpoch
//
//      val forecast = ForecastPeriod(Map(
//        d1t0000Millis -> Seq(
//          ForecastTimeSlot(d1t0000Millis, 1, 2),
//          ForecastTimeSlot(d1t0015Millis, 3, 4),
//          ForecastTimeSlot(d1t0030Millis, 5, 6),
//          ForecastTimeSlot(d1t0045Millis, 7, 8),
//          ForecastTimeSlot(d1t0100Millis, 9, 10)
//        ),
//        d2t0000Millis -> Seq(
//          ForecastTimeSlot(d1t0000Millis, 1, 2),
//          ForecastTimeSlot(d1t0015Millis, 3, 4),
//          ForecastTimeSlot(d1t0030Millis, 5, 6),
//          ForecastTimeSlot(d1t0045Millis, 7, 8),
//          ForecastTimeSlot(d1t0100Millis, 9, 10)
//        ),
//        d3t0000Millis -> Seq(
//          ForecastTimeSlot(d1t0000Millis, 1, 2),
//          ForecastTimeSlot(d1t0015Millis, 3, 4),
//          ForecastTimeSlot(d1t0030Millis, 5, 6),
//          ForecastTimeSlot(d1t0045Millis, 7, 8),
//          ForecastTimeSlot(d1t0100Millis, 9, 10)
//        )
//      ))
//
//      val result = CSVData.forecastPeriodToCsv(forecast)
//
//      val expected =
//        s"""|,${day1Midnight.getDate()}/${day1Midnight.getMonth()},,${day2Midnight.getDate()}/${day2Midnight.getMonth()},,${day3Midnight.getDate()}/${day3Midnight.getMonth()}
//            |Start Time,Avail,Rec,Avail,Rec,Avail,Rec
//            |00:00,1,2,1,2,1,2
//            |00:15,3,4,3,4,3,4
//            |00:30,5,6,5,6,5,6
//            |00:45,7,8,7,8,7,8
//            |01:00,9,10,9,10,9,10""".stripMargin
//
//      result === expected
//    }
//  }

  "Forecast Headline Export" >> {
    "Given forecast headline figures for 3 days, then I should get those days exported as a CSV" >> {
      val day1StartMinute = SDate("2017-01-01T00:00Z")
      val day2StartMinute = SDate("2017-01-02T00:00Z")
      val day3StartMinute = SDate("2017-01-03T00:00Z")

      val headlines = ForecastHeadlineFigures(Set(
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
