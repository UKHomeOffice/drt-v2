package services

import drt.shared.{SDateLike, TQM}
import org.slf4j.{Logger, LoggerFactory}
import org.specs2.mutable.SpecificationLike
import services.graphstages.Crunch

import scala.collection.mutable


class MutableStateSpec extends SpecificationLike {
  val log: Logger = LoggerFactory.getLogger(getClass)

  "Given a few million items in a sorted map " +
    "When I ask to purge a large chunk of them " +
    "The purge operation should not take longer than a second" >> {
    val oneDayMillis = 1000L * 60 * 60 * 24
    val startDate = SDate("2019-01-01")
    val endDate = startDate.addDays(180)
    val minutes: List[Long] = List.range(startDate.millisSinceEpoch, endDate.millisSinceEpoch, 60000L)

    val tms = for {
      t <- Seq("T2", "T3", "T4", "T5")
      q <- Seq("EEA", "NEEA", "FT", "GATES")
      m <- minutes
    } yield (TQM(t, q, m.toLong), m.toLong)

    log.info(s"Created ${tms.size} items for sorted map")
    val things = mutable.SortedMap[TQM, Long]() ++= tms

    val start = SDate.now().millisSinceEpoch

    val like: () => SDateLike = () => SDate("2019-02-01T00:00")

    Crunch.purgeExpired(things, TQM.atTime, like, oneDayMillis.toInt * 2)

    val end = SDate.now().millisSinceEpoch

    val msTaken = end - start

    log.info(s"purge took ${msTaken}ms")

    msTaken must beLessThan(1000L)
  }
}
