package drt.shared

import org.specs2.mutable.Specification

import scala.concurrent.duration._

class FeedStatusesSpec extends Specification {
  "Given a feed status and a success threshold of 36 hours" >> {
    val now = MilliTimes.oneDayMillis
    val updateThreshold: Option[FiniteDuration] = Option(36 hours)
    val oneHourAgo = Option(now - MilliTimes.oneHourMillis.toLong)
    val twoHoursAgo = Option(now - MilliTimes.oneHourMillis.toLong * 2)
    val thirtySevenHoursAgo = Option(now - MilliTimes.oneHourMillis.toLong * 37)

    "When the last success is more recent than the last failure and the last update is within the threshold hours" >> {
      val feedStatuses = FeedStatuses(List(), oneHourAgo, twoHoursAgo, oneHourAgo)
      "Then I should get a green RAG" >> {
        val rag = FeedStatuses.ragStatus(now, updateThreshold, feedStatuses)
        rag === Green
      }
    }

    "When the last success is more recent than the last failure, but the update was longer ago than the threshold" >> {
      val feedStatuses = FeedStatuses(List(), oneHourAgo, twoHoursAgo, thirtySevenHoursAgo)
      "Then I should get a red RAG" >> {
        val rag = FeedStatuses.ragStatus(now, updateThreshold, feedStatuses)
        rag === Red
      }
    }

    "When the last failure is more recent than the last success" >> {
      val feedStatuses = FeedStatuses(List(), twoHoursAgo, oneHourAgo, None)
      "Then I should get a red RAG" >> {
        val rag = FeedStatuses.ragStatus(now, updateThreshold, feedStatuses)
        rag === Red
      }
    }
  }
}
