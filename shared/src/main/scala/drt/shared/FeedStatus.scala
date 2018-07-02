package drt.shared


sealed trait FeedStatus {
  val name: String
  val date: SDateLike
}

case class FeedStatusSuccess(name: String, date: SDateLike, updateCount: Int) extends FeedStatus

case class FeedStatusFailure(name: String, date: SDateLike, message: String) extends FeedStatus
