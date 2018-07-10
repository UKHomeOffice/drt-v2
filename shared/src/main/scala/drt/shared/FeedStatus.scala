package drt.shared

import drt.shared.CrunchApi.MillisSinceEpoch


sealed trait FeedStatus {
  val date: MillisSinceEpoch
}

case class FeedStatusSuccess(date: MillisSinceEpoch, updateCount: Int) extends FeedStatus

case class FeedStatusFailure(date: MillisSinceEpoch, message: String) extends FeedStatus

object FeedStatus {
  def apply(date: MillisSinceEpoch, updateCount: Int): FeedStatusSuccess = FeedStatusSuccess(date, updateCount)
  def apply(date: MillisSinceEpoch, message: String): FeedStatusFailure = FeedStatusFailure(date, message)
}

case class FeedStatuses(name: String,
                        statuses: List[FeedStatus],
                        lastSuccessAt: Option[MillisSinceEpoch],
                        lastFailureAt: Option[MillisSinceEpoch],
                        lastUpdatesAt: Option[MillisSinceEpoch]) {
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

