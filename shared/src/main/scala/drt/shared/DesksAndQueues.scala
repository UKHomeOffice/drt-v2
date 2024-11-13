package drt.shared

import drt.shared.CrunchApi.StaffMinute
import uk.gov.homeoffice.drt.model.CrunchMinute

object DesksAndQueues {
  def totalDeployed(staffMinute: StaffMinute, crunchMinutes: List[CrunchMinute]): Int = {
    crunchMinutes
      .map(_.deployedDesks.getOrElse(0))
      .sum + staffMinute.fixedPoints
  }

  def totalRequired(staffMinute: StaffMinute, crunchMinutes: List[CrunchMinute]): Int = {
    crunchMinutes
      .map(_.deskRec)
      .sum + staffMinute.fixedPoints
  }
}
