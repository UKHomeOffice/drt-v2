package services.crunch

import drt.shared.CrunchApi.{StaffMinute, StaffMinutes}
import drt.shared.Terminals.T1
import org.specs2.mutable.Specification
import services.SDate
import services.graphstages.Crunch.{changedDays, europeLondonTimeZone}


class StaffBatchUpdateGraphStageSpec extends Specification {

  "StaffBatchUpdateGraphState should" in {

    "queue appropriate days given staff minute changes" in {
      val offsetMinutes = 240
      val yesterdayStaffMinute = StaffMinute(T1, SDate("2018-06-04T03:00:00", europeLondonTimeZone).millisSinceEpoch, 0, 0, 0, None)
      val todayStaffMinute = StaffMinute(T1, SDate("2018-06-04T05:00:00", europeLondonTimeZone).millisSinceEpoch, 0, 0, 0, None)
      val actualStaffMinutes = Seq(yesterdayStaffMinute, todayStaffMinute)

      val actualDates = changedDays(offsetMinutes, StaffMinutes(actualStaffMinutes))

      actualDates mustEqual Map(SDate("2018-06-02T23:00:00").millisSinceEpoch -> List(yesterdayStaffMinute), SDate("2018-06-03T23:00:00").millisSinceEpoch -> List(todayStaffMinute) )
    }
  }

}
