package drt.shared

import drt.shared.CrunchApi.MillisSinceEpoch
import ujson.Js.Value
import upickle.Js
import upickle.default.{macroRW, readwriter, ReadWriter => RW}

sealed trait FeedStatus {
  val date: MillisSinceEpoch
}

case class FeedStatusSuccess(date: MillisSinceEpoch, updateCount: Int) extends FeedStatus
object FeedStatusSuccess {
  implicit val rw: RW[FeedStatusSuccess] = macroRW
}

case class FeedStatusFailure(date: MillisSinceEpoch, message: String) extends FeedStatus
object FeedStatusFailure {
  implicit val rw: RW[FeedStatusFailure] = macroRW
}

object FeedStatus {
  def apply(date: MillisSinceEpoch, updateCount: Int): FeedStatusSuccess = FeedStatusSuccess(date, updateCount)
  def apply(date: MillisSinceEpoch, message: String): FeedStatusFailure = FeedStatusFailure(date, message)

  implicit val rw: RW[FeedStatus] = RW.merge(FeedStatusSuccess.rw, FeedStatusFailure.rw)
}

sealed trait RagStatus

case object Red extends RagStatus {
  override def toString = "red"
}
case object Amber extends RagStatus {
  override def toString = "amber"
}
case object Green extends RagStatus {
  override def toString = "green"
}

case class FeedStatuses(name: String,
                        statuses: List[FeedStatus],
                        lastSuccessAt: Option[MillisSinceEpoch],
                        lastFailureAt: Option[MillisSinceEpoch],
                        lastUpdatesAt: Option[MillisSinceEpoch]) {
  val oneMinuteMillis: Int = 60 * 1000

  def ragStatus(now: MillisSinceEpoch): RagStatus = (lastSuccessAt, lastFailureAt) match {
    case (Some(s), Some(f)) if f > s => Red
    case (Some(_), Some(f)) if f > now - (5 * oneMinuteMillis) => Amber
    case (None, Some(_)) => Red
    case _ => Green
  }

  def hasConnectedAtLeastOnce: Boolean = lastSuccessAt.isDefined

  def addStatus(createdAt: SDateLike, updateCount: Int): FeedStatuses = {
    add(FeedStatusSuccess(createdAt.millisSinceEpoch, updateCount))
  }

  def addStatus(createdAt: SDateLike, failureMessage: String): FeedStatuses = {
    add(FeedStatusFailure(createdAt.millisSinceEpoch, failureMessage))
  }

  def add(newStatus: FeedStatus): FeedStatuses = {
    val newStatuses = newStatus :: statuses
    val statusesLimited = if (newStatuses.length >= 10) newStatuses.dropRight(1) else newStatuses

    newStatus match {
      case fss: FeedStatusSuccess =>
        val newLastUpdatesAt = if (fss.updateCount > 0) Option(newStatus.date) else lastUpdatesAt
        this.copy(statuses = statusesLimited, lastSuccessAt = Option(newStatus.date), lastUpdatesAt = newLastUpdatesAt)

      case fsf: FeedStatusFailure =>
        this.copy(statuses = statusesLimited, lastFailureAt = Option(newStatus.date))
    }
  }
}
object FeedStatuses {
  implicit val rw: RW[FeedStatuses] = macroRW
}
