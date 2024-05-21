package services.dataretention

import actors.DateRange
import akka.Done
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import org.slf4j.LoggerFactory
import slickdb.AkkaDao
import uk.gov.homeoffice.drt.db.AkkaDb
import uk.gov.homeoffice.drt.ports.FeedSource
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.prediction.ModelCategory
import uk.gov.homeoffice.drt.prediction.category.FlightCategory
import uk.gov.homeoffice.drt.time.{SDate, SDateLike, UtcDate}

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}

object DataRetentionHandler {
  private val nonDatePersistenceIds: Seq[String] = Seq(
    "daily-pax",
    "actors.ForecastBaseArrivalsActor-forecast-base",
    "actors.LiveBaseArrivalsActor-live-base",
    "actors.ForecastPortArrivalsActor-forecast-port",
    "actors.LiveArrivalsActor-live",
    "shifts-store",
    "staff-movements-store",
    "fixedPoints-store",
  )

  private def byDatePersistenceIdPrefixes(terminals: Iterable[Terminal], sources: Iterable[FeedSource]): Iterable[String] = Seq(
    terminals.map(t => s"terminal-flights-${t.toString}"),
    terminals.map(t => s"terminal-passengers-${t.toString}"),
    terminals.map(t => s"terminal-queues-${t.toString}"),
    terminals.map(t => s"terminal-staff-${t.toString}"),
    for {
      fs <- sources
      t <- terminals
    } yield {
      s"${fs.id}-feed-arrivals-${t.toString}"
    },
  ).flatten

  private def persistenceIdForDate(persistenceIdPrefix: String, date: UtcDate): String = {
    s"$persistenceIdPrefix-${date.toISOString}"
  }

  def retentionForecastDateRange(retentionPeriod: FiniteDuration, maxForecastDays: Int, todaySDate: SDateLike): Seq[UtcDate] = {
    val oldestForecastDate = todaySDate.addMillis(-retentionPeriod.toMillis)
    val youngestForecastDate = oldestForecastDate.addDays(maxForecastDays)
    DateRange(oldestForecastDate.toUtcDate, youngestForecastDate.toUtcDate)
  }

  def persistenceIdsForPurge(terminals: Iterable[Terminal],
                             retentionPeriod: FiniteDuration,
                             sources: Iterable[FeedSource],
                            ): UtcDate => Iterable[String] =
    today => {
      val date = SDate(today).addDays(-retentionPeriod.toDays.toInt).toUtcDate

      byDatePersistenceIdPrefixes(terminals, sources)
        .map { persistenceIdPrefix =>
          persistenceIdForDate(persistenceIdPrefix, date)
        }
    }

  def retentionForecastPersistenceIds(retentionPeriod: FiniteDuration,
                                      maxForecastDays: Int,
                                      terminals: Iterable[Terminal],
                                      sources: Set[FeedSource],
                                     ): UtcDate => Iterable[String] = today => {
    val todaySDate = SDate(today)
    val dateRange = retentionForecastDateRange(retentionPeriod, maxForecastDays, todaySDate)

    dateRange.flatMap { date =>
      byDatePersistenceIdPrefixes(terminals, sources).map { persistenceIdPrefix =>
        s"$persistenceIdPrefix-${date.toISOString}"
      }
    } ++ nonDatePersistenceIds
  }

  def apply(retentionPeriod: FiniteDuration,
            maxForecastDays: Int,
            terminals: Iterable[Terminal],
            now: () => SDateLike,
           )
           (implicit ec: ExecutionContext, mat: Materializer): DataRetentionHandler = {
    val pIdsForSequenceNumberPurge = DataRetentionHandler
      .retentionForecastPersistenceIds(retentionPeriod, maxForecastDays, terminals, FeedSource.feedSources)
    val pIdsForFullPurge = DataRetentionHandler
      .persistenceIdsForPurge(terminals, retentionPeriod, FeedSource.feedSources)
    val akkaDao = AkkaDao(AkkaDb, now)
    DataRetentionHandler(
      pIdsForSequenceNumberPurge,
      pIdsForFullPurge,
      retentionPeriod,
      now,
      akkaDao.deletePersistenceId,
      akkaDao.deleteLowerSequenceNumbers,
      akkaDao.getSequenceNumberBeforeRetentionPeriod,
    )
  }

}

case class DataRetentionHandler(persistenceIdsForSequenceNumberPurge: UtcDate => Iterable[String],
                                persistenceIdsForFullPurge: UtcDate => Iterable[String],
                                retentionPeriod: FiniteDuration,
                                now: () => SDateLike,
                                deletePersistenceId: String => Future[Int],
                                deleteLowerSequenceNumbers: (String, Long) => Future[(Int, Int)],
                                getSequenceNumberBeforeRetentionPeriod: (String, FiniteDuration) => Future[Option[Long]],
                               )
                               (implicit ec: ExecutionContext, mat: Materializer) {
  private val log = LoggerFactory.getLogger(getClass)

  val models: Seq[ModelCategory] = Seq(
    FlightCategory
  )

  def purgeDataOutsideRetentionPeriod(): Unit = {
    log.info("Purging data outside retention period")
    val today = now().toUtcDate

    purgeOldSequenceNumbers(persistenceIdsForSequenceNumberPurge(today))

    purgeOldPersistenceIds(persistenceIdsForFullPurge(today))
  }

  private def purgeOldPersistenceIds(persistenceIds: Iterable[String]): Future[Done] =
    Source(persistenceIds.toList)
      .mapAsync(1) { pId =>
        log.info(s"Deleting persistenceId $pId")
        deletePersistenceId(pId)
      }
      .run()
      .recover {
        case t: Throwable =>
          log.error("Failed to delete persistenceIds", t)
          Done
      }

  private def purgeOldSequenceNumbers(persistenceIds: Iterable[String]): Future[Done] =
    Source(persistenceIds.toList)
      .mapAsync(1) { persistenceId =>
        getSequenceNumberBeforeRetentionPeriod(persistenceId, retentionPeriod)
          .map { maybeSeq =>
            maybeSeq.map { seq =>
              log.info(s"Found snapshot sequence number $maybeSeq for $persistenceId")
              (persistenceId, seq)
            }
          }
      }
      .collect {
        case Some((persistenceId, sequenceNumber)) =>
          deleteLowerSequenceNumbers(persistenceId, sequenceNumber)
            .map { counts =>
              log.info(s"Deleted ${counts._1} journal events and ${counts._2} snapshots for $persistenceId")
              counts
            }
      }
      .run()
      .recover {
        case t: Throwable =>
          log.error("Failed to delete sequence numbers", t)
          Done
      }
}
