package actors.queues

import akka.NotUsed
import akka.stream.scaladsl.Source
import drt.shared.{MilliTimes, SDateLike, UtcDate}
import services.SDate

object DateRange {

  def utcDateRangeWithBuffer(startBuffer: Int, endBuffer: Int)(start: SDateLike, end: SDateLike): Seq[UtcDate] = {
    val lookupStartMillis = start.addDays(startBuffer * -1).millisSinceEpoch
    val lookupEndMillis = end.addDays(endBuffer).millisSinceEpoch
    val daysRangeMillis = lookupStartMillis to lookupEndMillis by MilliTimes.oneDayMillis
    daysRangeMillis.map(SDate(_).toUtcDate)
  }

  def utcDateRange(start: SDateLike, end: SDateLike): Seq[UtcDate] = {
    val lookupStartMillis = start.millisSinceEpoch
    val lookupEndMillis = end.millisSinceEpoch
    val daysRangeMillis = lookupStartMillis to lookupEndMillis by MilliTimes.oneDayMillis
    daysRangeMillis.map(SDate(_).toUtcDate)
  }

  def utcDateRangeSource(start: SDateLike, end: SDateLike): Source[UtcDate, NotUsed]
  = Source(utcDateRange(start, end).toList)
}
