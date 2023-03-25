package actors

import uk.gov.homeoffice.drt.time.{MilliTimes, SDateLike}

object DrtStaticParameters {
  val expireAfterMillis: Int = 2 * MilliTimes.oneDayMillis

  def time48HoursAgo(now: () => SDateLike): () => SDateLike = () => now().addDays(-2)

  def timeBeforeThisMonth(now: () => SDateLike): () => SDateLike = () => now().startOfTheMonth
}
