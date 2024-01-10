package drt.shared.redlist

import drt.shared.CrunchApi.MillisSinceEpoch
import uk.gov.homeoffice.drt.time.SDateLike

trait LhrRedListDates {
  val t3RedListOpeningDate: MillisSinceEpoch
  val t4RedListOpeningDate: MillisSinceEpoch

  val startRedListingDate: MillisSinceEpoch
  val endRedListingDate: MillisSinceEpoch
}

case object LhrRedListDatesImpl extends LhrRedListDates {
  def dayHasPaxDiversions(day: SDateLike): Boolean = day.millisSinceEpoch >= t3RedListOpeningDate

  def overlapsRedListDates(startDate: SDateLike, endDate: SDateLike): Boolean = {
    (startDate.millisSinceEpoch <= startRedListingDate && endDate.millisSinceEpoch >= startRedListingDate) ||
      (startDate.millisSinceEpoch <= endRedListingDate && endDate.millisSinceEpoch >= endRedListingDate) ||
      isRedListActive(startDate) || isRedListActive(endDate)
  }

  def isRedListActive(day: SDateLike): Boolean = day.millisSinceEpoch >= startRedListingDate && day.millisSinceEpoch <= endRedListingDate

  override val t3RedListOpeningDate = 1622502000000L // 2021-06-01 BST
  override val t4RedListOpeningDate = 1624921200000L // 2021-06-29 BST
  override val startRedListingDate = 1613347200000L // 2021-02-15 BST
  override val endRedListingDate = 1639526400000L // 2021-12-15 BST
}
