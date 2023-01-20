package services.crunch.deskrecs

import drt.shared.CrunchApi.MillisSinceEpoch
import uk.gov.homeoffice.drt.ports.Queues.Queue
import org.joda.time.DateTime
import org.slf4j.{Logger, LoggerFactory}
import services.graphstages.Crunch.europeLondonTimeZone

import scala.collection.immutable.{Map, NumericRange}

object DeskRecs {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def desksForHourOfDayInUKLocalTime(dateTimeMillis: MillisSinceEpoch, desks: IndexedSeq[Int]): Int = {
    val date = new DateTime(dateTimeMillis).withZone(europeLondonTimeZone)
    desks(date.getHourOfDay)
  }

  def desksByMinuteForQueues(queueDesks24Hrs: Map[Queue, IndexedSeq[Int]],
                             minuteMillis: NumericRange[Long],
                             queues: Set[Queue]): Map[Queue, IndexedSeq[Int]] = queueDesks24Hrs
    .view.filterKeys(queues.contains)
    .mapValues(mds => desksForMillis(minuteMillis, mds)).toMap

  def desksForMillis(millisRange: NumericRange[Long], desks24Hrs: IndexedSeq[Int]): IndexedSeq[Int] = millisRange
    .map(m => DeskRecs.desksForHourOfDayInUKLocalTime(m, desks24Hrs))
}

