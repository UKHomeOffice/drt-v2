package services.exports

import drt.shared.CrunchApi.{CrunchMinute, StaffMinute}
import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.ports.Queues.{EGate, EeaDesk, NonEeaDesk}
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.time.MilliTimes.oneMinuteMillis
import uk.gov.homeoffice.drt.time.TimeZoneHelper.europeLondonTimeZone
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, SDateLike}

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}

class StaffRequirementExportsSpec extends Specification {
  "groupByXMinutes" should {
    "group a list of minutes into groups of 15 minutes" in {
      val start = SDate("2023-09-26T00:00", europeLondonTimeZone)
      val minutes = (0 to 59).map { m =>
        StaffMinute(T1, start.addMinutes(m).millisSinceEpoch, 0, 0, 0, None)
      }

      def fifteenMins(start: SDateLike): Seq[Long] = (start.millisSinceEpoch to start.addMinutes(14).millisSinceEpoch by oneMinuteMillis).toList

      val grouped = StaffRequirementExports.groupByMinutes(minutes, 15).view.mapValues(_.map(_.minute)).toMap
      val expected = Map(
        0 -> fifteenMins(SDate("2023-09-26T00:00", europeLondonTimeZone)),
        1 -> fifteenMins(SDate("2023-09-26T00:15", europeLondonTimeZone)),
        2 -> fifteenMins(SDate("2023-09-26T00:30", europeLondonTimeZone)),
        3 -> fifteenMins(SDate("2023-09-26T00:45", europeLondonTimeZone))
      )
      grouped === expected
    }
  }

  "maxRequired" should {
    "return the maximum required staff for a day" in {
      val start = SDate("2023-09-26T00:00", europeLondonTimeZone)
      val staff = (0 to 14).map { fp =>
        StaffMinute(T1, start.addMinutes(fp).millisSinceEpoch, 0, fp, 0, None)
      }
      val crunch = (0 to 14).flatMap { m =>
        Seq(EeaDesk, EGate, NonEeaDesk).map (queue =>
          CrunchMinute(T1, queue, start.addMinutes(m).millisSinceEpoch, 0d, 0d, m, 0, None, None, None, None, None, None, None),
        )
      }

      val max = StaffRequirementExports.maxRequired(crunch, staff)

      val highestStaffAcrossQueues = 14 * 3
      val highestFixedPoints = 14

      max === highestStaffAcrossQueues + highestFixedPoints
    }
  }

  "toHourlyStaffing" should {
    "return the available, required & diff columns for the specified slot sizes" in {
      val staff = Seq(
        StaffMinute(T1, SDate("2023-09-26T00:00", europeLondonTimeZone).millisSinceEpoch, 15, 2, 0, None),
        StaffMinute(T1, SDate("2023-09-26T00:01", europeLondonTimeZone).millisSinceEpoch, 16, 3, 0, None),
        StaffMinute(T1, SDate("2023-09-26T12:15", europeLondonTimeZone).millisSinceEpoch, 11, 2, 0, None),
        StaffMinute(T1, SDate("2023-09-26T12:17", europeLondonTimeZone).millisSinceEpoch, 10, 4, 0, None),
      )
      val crunch = Seq(
        CrunchMinute(T1, EeaDesk, SDate("2023-09-26T00:00", europeLondonTimeZone).millisSinceEpoch, 0d, 0d, 10, 0, None, None, None, None, None, None, None),
        CrunchMinute(T1, EeaDesk, SDate("2023-09-26T00:01", europeLondonTimeZone).millisSinceEpoch, 0d, 0d, 11, 0, None, None, None, None, None, None, None),
        CrunchMinute(T1, EeaDesk, SDate("2023-09-26T12:15", europeLondonTimeZone).millisSinceEpoch, 0d, 0d, 12, 0, None, None, None, None, None, None, None),
        CrunchMinute(T1, EeaDesk, SDate("2023-09-26T12:17", europeLondonTimeZone).millisSinceEpoch, 0d, 0d, 13, 0, None, None, None, None, None, None, None),
      )
      import scala.concurrent.ExecutionContext.Implicits.global
      val getColumns = StaffRequirementExports.toHourlyStaffing(_ => Future.successful(staff), 720)
      Await.result(getColumns(LocalDate(2023, 9, 26), crunch), 1.second) === List(
        ("26/09 - available", "26/09 - required", "26/09 - difference"),
        ("16", "14", "2"),
        ("11", "17", "-6"),
      )
    }
  }
}
