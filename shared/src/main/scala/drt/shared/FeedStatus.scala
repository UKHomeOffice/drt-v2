package drt.shared

import drt.shared.CrunchApi.MillisSinceEpoch


sealed trait FeedStatus {
  val name: String
  val date: MillisSinceEpoch
}

case class FeedStatusSuccess(name: String, date: MillisSinceEpoch, updateCount: Int) extends FeedStatus

case class FeedStatusFailure(name: String, date: MillisSinceEpoch, message: String) extends FeedStatus

case class FeedStatuses(statuses: List[FeedStatus])

