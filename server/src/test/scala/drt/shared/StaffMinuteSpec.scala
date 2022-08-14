package drt.shared

import drt.shared.CrunchApi.StaffMinute
import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.ports.Terminals.T1

class StaffMinuteSpec extends Specification {
  "Given a existing StaffMinute, I should know a new StaffMinute is an update" >> {
    val shifts = 1
    val fixedPoints = 2
    val movements = 3
    val nowMillis = 10L
    val existing = StaffMinute(T1, 0L, shifts, fixedPoints, movements)
    "When shifts are updated" >> {
      val sm = StaffMinute(T1, 0L, shifts + 1, fixedPoints, movements)
      sm.maybeUpdated(existing, nowMillis) === Option(existing.copy(shifts = shifts + 1, lastUpdated = Option(nowMillis)))
    }
    "When fixedPoints are updated" >> {
      val sm = StaffMinute(T1, 0L, shifts, fixedPoints + 1, movements)
      sm.maybeUpdated(existing, nowMillis) === Option(existing.copy(fixedPoints = fixedPoints + 1, lastUpdated = Option(nowMillis)))
    }
    "When movements are updated" >> {
      val sm = StaffMinute(T1, 0L, shifts, fixedPoints, movements + 1)
      sm.maybeUpdated(existing, nowMillis) === Option(existing.copy(movements = movements + 1, lastUpdated = Option(nowMillis)))
    }
  }
}
