package services.dataretention

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import org.slf4j.LoggerFactory
import services.dataretention.DataRetentionHandler.byDatePersistenceIds
import slickdb.AkkaDbTables
import uk.gov.homeoffice.drt.ports.FeedSource
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{SDateLike, UtcDate}

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

object DataRetentionHandler {
  def byDatePersistenceIds(terminals: Seq[Terminal]): Seq[String] = Seq(
    terminals.map(t => s"terminal-flights-${t.toString}"),
    terminals.map(t => s"terminal-passengers-${t.toString}"),
    terminals.map(t => s"terminal-queues-${t.toString}"),
    terminals.map(t => s"terminal-staff-${t.toString}"),
    for {
      fs <- FeedSource.feedSources
      t <- terminals
    } yield s"${fs.id}-feed-arrivals-${t.toString}",
    terminals.map(t => s"${}-feed-arrivals-${t.toString}"),
  ).flatten

}

case class DataRetentionHandler(terminals: Seq[Terminal], db: AkkaDbTables, now: () => SDateLike)
                               (implicit ec: ExecutionContext, mat: Materializer) {
  private val log = LoggerFactory.getLogger(getClass)

  import db.profile.api._


  private val nonDatePersistenceIds: Seq[String] = Seq(
    "daily-pax",
    "actors.ForecastBaseArrivalsActor-forecast-base",
    "actors.LiveBaseArrivalsActor-live-base",
    "actors.ForecastPortArrivalsActor-forecast-port",
    "actors.LiveArrivalsActor-live",
    "shifts-store",
    "staff-movements-store",
  )

  //s"${modelCategory.name}-prediction-${identifier.id}".toLowerCase


  def purgeDataOutsideRetentionPeriod(retentionPeriod: FiniteDuration): Unit = {
    log.info("Purging data outside retention period")
    // for each persistence id:
    // get the sequence number of the last snapshot taken before the retention period cutoff
    // delete all snapshots & journal events with a sequence number less than the last snapshot sequence number
    Source(nonDatePersistenceIds)
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

    // for each persistence id by date
    // delete all journal events and snapshots for persistence ids prior to the retention period cutoff
    Source(byDatePersistenceIds(terminals))
      .mapAsync(1) { persistenceId =>
        deletePersistenceIdForDate(persistenceId, now().addDays(-1).toUtcDate)
      }
      .run()
  }

  def getSequenceNumberBeforeRetentionPeriod(persistenceId: String, retentionPeriod: FiniteDuration): Future[Option[Long]] = {
    val retentionCutoff = now().addMillis(-retentionPeriod.toMillis)

    val q = db.snapshotTable
      .filter(s => s.persistenceId === persistenceId && s.created < retentionCutoff.millisSinceEpoch)
      .sortBy(_.sequenceNumber.desc)
      .map(_.sequenceNumber)
      .take(1)
      .result
      .map(_.headOption)

    db.run(q)
  }

  def deleteLowerSequenceNumbers(persistenceId: String, sequenceNumber: Long): Future[(Int, Int)] = {
    val deleteSnapshots = db.snapshotTable
      .filter(s => s.persistenceId === persistenceId && s.sequenceNumber < sequenceNumber)
      .delete

    val deleteJournal = db.journalTable
      .filter(j => j.persistenceId === persistenceId && j.sequenceNumber < sequenceNumber)
      .delete

    db.run(deleteSnapshots)
      .flatMap(ssCount => db.run(deleteJournal).map(jcCount => (jcCount, ssCount)))
  }

  def deletePersistenceIdForDate(persistenceIdPrefix: String, date: UtcDate): Future[Int] = {
    val persistenceId = s"$persistenceIdPrefix-${date.toISOString}"
    val deleteSnapshots = db.snapshotTable
      .filter(s => s.persistenceId === persistenceId)
      .delete

    val deleteJournal = db.journalTable
      .filter(j => j.persistenceId === persistenceId)
      .delete

    db.run(deleteSnapshots andThen deleteJournal)
  }
}

/*
SELECT persistence_id, sequence_number, TO_CHAR(TO_TIMESTAMP(created / 1000), 'YYYY-MM-DD HH24:MI:SS')
FROM snapshot
WHERE persistence_id = 'actors.ForecastBaseArrivalsActor-forecast-base'
  AND created < EXTRACT(epoch from now() - interval '5 year') * 1000
ORDER BY sequence_number DESC
LIMIT 1
 */
