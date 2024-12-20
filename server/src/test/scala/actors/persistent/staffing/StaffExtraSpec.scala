package actors.persistent.staffing

import drt.shared.StaffAssignment
import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{SDate, UtcDate}

class StaffExtraSpec extends Specification {
  def millisSinceEpochToUTCSDate(millis: Long): UtcDate = SDate(millis).toUtcDate

  "StaffAssignment" should {
    "split into slots" in {
      val staffAssignment = StaffAssignment("afternoon", Terminal("terminal"), SDate(2023, 10, 1, 14, 0).millisSinceEpoch, SDate(2023, 10, 1, 15, 0).millisSinceEpoch, 0, None)
      val slots = staffAssignment.splitIntoSlots(15)
      val expected = Seq(StaffAssignment("day", Terminal("T1"), SDate(2023, 10, 1, 15, 0).millisSinceEpoch, SDate(2023, 10, 1, 15, 14).millisSinceEpoch, 5, None),
        StaffAssignment("day", Terminal("T1"), SDate(2023, 10, 1, 15, 15).millisSinceEpoch, SDate(2023, 10, 1, 15, 29).millisSinceEpoch, 5, None),
        StaffAssignment("day", Terminal("T1"), SDate(2023, 10, 1, 15, 30).millisSinceEpoch, SDate(2023, 10, 1, 15, 44).millisSinceEpoch, 5, None),
        StaffAssignment("day", Terminal("T1"), SDate(2023, 10, 1, 15, 45).millisSinceEpoch, SDate(2023, 10, 1, 15, 59).millisSinceEpoch, 5, None))
      slots.map(s => millisSinceEpochToUTCSDate(s.start)) === expected.map(s => millisSinceEpochToUTCSDate(s.start))
      slots.map(s => millisSinceEpochToUTCSDate(s.end)) === expected.map(s => millisSinceEpochToUTCSDate(s.end))
      slots.length must beEqualTo(4)
    }

    "daysBetweenInclusive" in {
      val startDate = SDate(2023, 10, 1)
      val endDate = SDate(2023, 10, 1)
      val daysBetween = startDate.daysBetweenInclusive(endDate)
      daysBetween must beEqualTo(1)
    }
  }

}
