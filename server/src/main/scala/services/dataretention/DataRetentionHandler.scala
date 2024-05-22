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

import scala.concurrent.duration.FiniteDuration
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
    terminals.map(t => s"terminal-flights-${lowerCaseTerminalString(t)}"),
    terminals.map(t => s"terminal-passengers-${lowerCaseTerminalString(t)}"),
    terminals.map(t => s"terminal-queues-${lowerCaseTerminalString(t)}"),
    terminals.map(t => s"terminal-staff-${lowerCaseTerminalString(t)}"),
    for {
      fs <- sources
      t <- terminals
    } yield {
      s"${fs.id}-feed-arrivals-${lowerCaseTerminalString(t)}"
    },
  ).flatten

  private def lowerCaseTerminalString(t: Terminal) = t.toString.toLowerCase

  private def persistenceIdForDate(persistenceIdPrefix: String, date: UtcDate): String =
    s"$persistenceIdPrefix-${date.toISOString}"

  def closestPreRetentionDate(retentionPeriod: FiniteDuration, today: UtcDate): UtcDate =
    SDate(today).addDays(-(retentionPeriod.toDays.toInt + 1)).toUtcDate

  def retentionForecastDateRange(retentionPeriod: FiniteDuration, maxForecastDays: Int, todaySDate: SDateLike): Seq[UtcDate] = {
    val preRetentionDate = closestPreRetentionDate(retentionPeriod, todaySDate.toUtcDate)
    val retentionForecastStartDate = SDate(preRetentionDate).addDays(1).toUtcDate
    val retentionForecastEndDate = SDate(retentionForecastStartDate).addDays(maxForecastDays).toUtcDate
    DateRange(retentionForecastStartDate, retentionForecastEndDate)
  }

  def persistenceIdsForFullPurge(terminals: Iterable[Terminal],
                                 retentionPeriod: FiniteDuration,
                                 sources: Iterable[FeedSource],
                                ): UtcDate => Iterable[String] =
    today => {
      val date = closestPreRetentionDate(retentionPeriod, today)

      byDatePersistenceIdPrefixes(terminals, sources)
        .map { persistenceIdPrefix =>
          persistenceIdForDate(persistenceIdPrefix, date)
        }
    }


  def persistenceIdsForSequenceNumberPurge(retentionPeriod: FiniteDuration,
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
      .persistenceIdsForSequenceNumberPurge(retentionPeriod, maxForecastDays, terminals, FeedSource.feedSources)
    val pIdsForFullPurge = DataRetentionHandler
      .persistenceIdsForFullPurge(terminals, retentionPeriod, FeedSource.feedSources)
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
      .flatMap(_ => purgeOldPersistenceIds(persistenceIdsForFullPurge(today)))
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
        log.info(s"Looking for pre-retention period snapshot sequence number for $persistenceId")
        getSequenceNumberBeforeRetentionPeriod(persistenceId, retentionPeriod)
          .map {
            case Some(seq) =>
              log.info(s"Found snapshot sequence number $seq for $persistenceId")
              Option((persistenceId, seq))
            case None =>
              log.info(s"No pre-retention period snapshot sequence number found for $persistenceId")
              None
          }
          .recover {
            case t: Throwable =>
              log.error(s"Failed to get pre-retention period sequence number for $persistenceId", t)
              None
          }
      }
      .collect {
        case Some((persistenceId, sequenceNumber)) =>
          deleteLowerSequenceNumbers(persistenceId, sequenceNumber)
            .map { counts =>
              log.info(s"Deleted ${counts._1} journal events and ${counts._2} snapshots for $persistenceId")
              counts
            }
            .recover {
              case t: Throwable =>
                log.error(s"Failed to delete sequence numbers for $persistenceId", t)
                (0, 0)
            }
      }
      .run()
}
