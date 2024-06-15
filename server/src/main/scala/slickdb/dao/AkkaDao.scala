package slickdb.dao

import slickdb.AkkaDbTables
import uk.gov.homeoffice.drt.time.SDateLike

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

case class AkkaDao(db: AkkaDbTables,
                   now: () => SDateLike,
                  )
                  (implicit ec: ExecutionContext) {

  import db.profile.api._

  val deletePersistenceId: String => Future[Int] = persistenceId => {
    val deleteSnapshots = db.snapshotTable
      .filter(s => s.persistenceId === persistenceId)
      .delete

    val deleteJournal = db.journalTable
      .filter(j => j.persistenceId === persistenceId)
      .delete

    db.run(deleteSnapshots andThen deleteJournal)
  }

  val deleteLowerSequenceNumbers: (String, Long) => Future[(Int, Int)] = (persistenceId, sequenceNumber) => {
    val deleteSnapshots = db.snapshotTable
      .filter(s => s.persistenceId === persistenceId && s.sequenceNumber < sequenceNumber)
      .delete

    val deleteJournal = db.journalTable
      .filter(j => j.persistenceId === persistenceId && j.sequenceNumber < sequenceNumber)
      .delete

    db.run(deleteSnapshots)
      .flatMap(ssCount => db.run(deleteJournal).map(jcCount => (jcCount, ssCount)))
  }

  val getSequenceNumberBeforeRetentionPeriod: (String, FiniteDuration) => Future[Option[Long]] = (persistenceId, retentionPeriod) => {
    val retentionCutoff = now().addDays(-retentionPeriod.toDays.toInt).millisSinceEpoch

    val q = db.snapshotTable
      .filter(s => s.persistenceId === persistenceId && s.created < retentionCutoff)
      .sortBy(_.sequenceNumber.desc)
      .map(_.sequenceNumber)
      .take(1)
      .result
      .map(_.headOption)

    db.run(q)
  }
}
