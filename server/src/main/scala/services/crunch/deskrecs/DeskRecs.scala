package services.crunch.deskrecs

import drt.shared.CrunchApi
import drt.shared.CrunchApi.MillisSinceEpoch
import org.joda.time.DateTime
import org.slf4j.{Logger, LoggerFactory}
import uk.gov.homeoffice.drt.models.TQM
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}
import uk.gov.homeoffice.drt.time.TimeZoneHelper.europeLondonTimeZone

import scala.collection.immutable.{Map, NumericRange}
import scala.concurrent.{ExecutionContext, Future}

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

  def minDesksByWorkload(minDesks: Seq[Int], pax: Seq[Int]): Seq[Int] =
    minDesks
      .zip(pax)
      .map {
        case (minDesks, p) if p > 0 => Math.max(minDesks, 1)
        case (minDesks, _) => minDesks
      }
      .grouped(15)
      .flatMap(groupedMinDesks => Seq.fill(15)(groupedMinDesks.max))
      .toSeq

  def paxForQueue(paxProvider: (SDateLike, SDateLike, Terminal) => Future[Map[TQM, CrunchApi.PassengersMinute]])
                 (implicit ec: ExecutionContext): Terminal => (NumericRange[Long], Queue) => Future[Seq[Int]] =
    terminal => (millis, queue) => {
      paxProvider(SDate(millis.start), SDate(millis.end), terminal)
        .map(_.values.filter(_.key.queue == queue).toSeq.sortBy(_.minute).map(_.passengers.size))
    }

}

