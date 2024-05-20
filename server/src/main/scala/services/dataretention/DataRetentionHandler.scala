package services.dataretention

import actors.DateRange
import akka.Done
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import org.slf4j.LoggerFactory
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

  private def byDatePersistenceIdPrefixes(terminals: Seq[Terminal], sources: Set[FeedSource]): Seq[String] = Seq(
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

  def persistenceIdsForPurge(terminals: Seq[Terminal],
                             retentionPeriod: FiniteDuration,
                             sources: Set[FeedSource],
                            ): UtcDate => Seq[String] =
    today => {
      val date = SDate(today).addDays(-retentionPeriod.toDays.toInt).toUtcDate

      byDatePersistenceIdPrefixes(terminals, sources)
        .map { persistenceIdPrefix =>
          persistenceIdForDate(persistenceIdPrefix, date)
        }
    }

  def preRetentionForecastPersistenceIds(retentionPeriod: FiniteDuration,
                                         maxForecastDays: Int,
                                         terminals: Seq[Terminal],
                                         sources: Set[FeedSource],
                                        ): UtcDate => Seq[String] = today => {
    val todaySDate = SDate(today)
    val oldestForecastDate = todaySDate.addMillis(-(retentionPeriod.toMillis + maxForecastDays.days.toMillis)).toUtcDate
    val youngestForecastDate = todaySDate.addDays(-retentionPeriod.toDays.toInt).toUtcDate

    DateRange(oldestForecastDate, youngestForecastDate).flatMap { date =>
      byDatePersistenceIdPrefixes(terminals, sources).map { persistenceIdPrefix =>
        s"$persistenceIdPrefix-${date.toISOString}"
      }
    } ++ nonDatePersistenceIds
  }
}

case class DataRetentionHandler(persistenceIdsForSequenceNumberPurge: UtcDate => Seq[String],
                                persistenceIdsForPurge: UtcDate => Seq[String],
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

    purgeOldPersistenceIds(persistenceIdsForPurge(today))
  }

  private def purgeOldPersistenceIds(persistenceIds: Seq[String]): Future[Done] =
    Source(persistenceIds)
      .mapAsync(1)(deletePersistenceId)
      .run()

  private def purgeOldSequenceNumbers(persistenceIds: Seq[String]): Future[Done] =
    Source(persistenceIds)
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
}
