package drt.shared

import drt.shared.CrunchApi.MillisSinceEpoch


sealed trait FeedStatus {
  val name: String
  val date: MillisSinceEpoch
}

case class FeedStatusSuccess(name: String, date: MillisSinceEpoch, updateCount: Int) extends FeedStatus

case class FeedStatusFailure(name: String, date: MillisSinceEpoch, message: String) extends FeedStatus

case class FeedStatuses(name: String,
                        statuses: List[FeedStatus],
                        lastSuccess: Option[MillisSinceEpoch],
                        lastFailure: Option[MillisSinceEpoch],
                        lastUpdatesAt: Option[MillisSinceEpoch]) {
  def addStatus(createdAt: SDateLike, updatedArrivals: Set[Arrival]): FeedStatuses = {
    add(FeedStatusSuccess(name, createdAt.millisSinceEpoch, updatedArrivals.size))
  }

  def addStatus(createdAt: SDateLike, failureMessage: String): FeedStatuses = {
    add(FeedStatusFailure(name, createdAt.millisSinceEpoch, failureMessage))
  }

  def add(newStatus: FeedStatus): FeedStatuses = {
    val newStatuses = newStatus :: statuses
    val statusesLimited = if (newStatuses.length >= 10) newStatuses.dropRight(1) else newStatuses

    newStatus match {
      case fss: FeedStatusSuccess =>
        val newLastUpdatesAt = if (fss.updateCount > 0) Option(newStatus.date) else lastUpdatesAt
        this.copy(statuses = statusesLimited, lastSuccess = Option(newStatus.date), lastUpdatesAt = newLastUpdatesAt)

      case fsf: FeedStatusFailure =>
        this.copy(statuses = statusesLimited, lastFailure = Option(newStatus.date))
    }
  }
}

