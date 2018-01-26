package drt.shared

import drt.shared.CrunchApi.{CrunchMinute, StaffMinute}

object DesksAndQueues {
  def totalDeployed(staffMinute: StaffMinute, crunchMinutes: Set[CrunchMinute]): Int = {
    crunchMinutes
      .toList
      .map(_.deployedDesks.getOrElse(0))
      .sum + staffMinute.fixedPoints
  }

  def totalRequired(staffMinute: StaffMinute, crunchMinutes: Set[CrunchMinute]): Int = {
    crunchMinutes
      .toList
      .map(_.deskRec)
      .sum + staffMinute.fixedPoints
  }
}
