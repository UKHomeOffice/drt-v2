package drt.shared.redlist

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.SDateLike

trait LhrRedListDates {
  val t3RedListOpeningDate: MillisSinceEpoch
  val t4RedListOpeningDate: MillisSinceEpoch
}

case object LhrRedListDatesImpl extends LhrRedListDates {
  def dayHasPaxDiversions(day: SDateLike): Boolean = day.millisSinceEpoch >= t3RedListOpeningDate

  override val t3RedListOpeningDate = 1622502000000L // 2021-06-01 BST
  override val t4RedListOpeningDate = 1624921200000L // 2021-06-29 BST
}
