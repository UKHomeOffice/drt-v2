package slickdb

import org.scalatest.BeforeAndAfter
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import slickdb.dao.AkkaDao
import uk.gov.homeoffice.drt.testsystem.db.AkkaDbH2
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt

class AkkaDaoSpec extends AnyWordSpec with Matchers with BeforeAndAfter {
  private val akkaDb = AkkaDbH2

  import akkaDb.profile.api._

  private val today = SDate("2024-05-20")
  private val retentionPeriod7Days = 7.days

  private val twoDaysBeforeRetention: SDateLike = today.addDays(-(retentionPeriod7Days.toDays.toInt + 2))
  private val oneDayBeforeRetention: SDateLike = today.addDays(-(retentionPeriod7Days.toDays.toInt + 1))

  private val snapshotRows = Seq(
    SnapshotRow("persistence-id-a", 1, twoDaysBeforeRetention.millisSinceEpoch),
    SnapshotRow("persistence-id-a", 2, oneDayBeforeRetention.millisSinceEpoch),
    SnapshotRow("persistence-id-a", 3, today.millisSinceEpoch),
    SnapshotRow("persistence-id-b", 4, twoDaysBeforeRetention.millisSinceEpoch),
    SnapshotRow("persistence-id-b", 5, oneDayBeforeRetention.millisSinceEpoch),
    SnapshotRow("persistence-id-b", 6, today.millisSinceEpoch),
  )
  private val journalRows = Seq(
    JournalRow(1, "persistence-id-a", 1),
    JournalRow(2, "persistence-id-a", 2),
    JournalRow(3, "persistence-id-a", 3),
    JournalRow(4, "persistence-id-b", 4),
    JournalRow(5, "persistence-id-b", 5),
    JournalRow(6, "persistence-id-b", 6),
  )

  before {
    akkaDb.dropAndCreateH2Tables()
    Await.ready(akkaDb.run(akkaDb.snapshotTable ++= snapshotRows), 1.second)
    Await.ready(akkaDb.run(akkaDb.journalTable ++= journalRows), 1.second)
  }

  "getSequenceNumberBeforeRetentionPeriod" should {
    "return the latest sequence number from before the retention period" in {
      val dao = AkkaDao(akkaDb, () => today)

      val result = Await.result(dao.getSequenceNumberBeforeRetentionPeriod("persistence-id-a", retentionPeriod7Days), 1.second)

      result should be(Some(2))
    }

    "return None if there are no snapshots before the retention period" in {
      val dao = AkkaDao(akkaDb, () => today.addDays(-7))

      val result = Await.result(dao.getSequenceNumberBeforeRetentionPeriod("persistence-id-a", retentionPeriod7Days), 1.second)

      result should be(None)
    }
  }

  "deleteLowerSequenceNumbers" should {
    "delete all snapshots and journal entries with a sequence number lower than the given sequence number, only for the given persistence id" in {
      val dao = AkkaDao(akkaDb, () => today)

      val result = Await.result(dao.deleteLowerSequenceNumbers("persistence-id-a", 3), 1.second)

      result should be((2, 2))

      val snapshots = Await.result(akkaDb.run(akkaDb.snapshotTable.result), 1.second)
      snapshots should ===(Seq(
        SnapshotRow("persistence-id-a", 3, today.millisSinceEpoch),
        SnapshotRow("persistence-id-b", 4, twoDaysBeforeRetention.millisSinceEpoch),
        SnapshotRow("persistence-id-b", 5, oneDayBeforeRetention.millisSinceEpoch),
        SnapshotRow("persistence-id-b", 6, today.millisSinceEpoch),
      ))
    }
    "return (0, 0) and not delete any row if there are no snapshots or journal entries with a sequence number lower than the given sequence number" in {
      val dao = AkkaDao(akkaDb, () => today)

      val result = Await.result(dao.deleteLowerSequenceNumbers("persistence-id-a", 0), 1.second)

      result should be((0, 0))

      val snapshots = Await.result(akkaDb.run(akkaDb.snapshotTable.result), 1.second)
      snapshots should ===(snapshotRows)

      val journal = Await.result(akkaDb.run(akkaDb.journalTable.result), 1.second)
      journal should ===(journalRows)
    }
  }

  "deletePersistenceId" should {
    "delete all snapshots and journal entries for the given persistence id" in {
      val dao = AkkaDao(akkaDb, () => today)

      val result = Await.result(dao.deletePersistenceId("persistence-id-a"), 1.second)

      result should be(3)

      val snapshots = Await.result(akkaDb.run(akkaDb.snapshotTable.result), 1.second)
      snapshots should ===(Seq(
        SnapshotRow("persistence-id-b", 4, twoDaysBeforeRetention.millisSinceEpoch),
        SnapshotRow("persistence-id-b", 5, oneDayBeforeRetention.millisSinceEpoch),
        SnapshotRow("persistence-id-b", 6, today.millisSinceEpoch),
      ))

      val journal = Await.result(akkaDb.run(akkaDb.journalTable.result), 1.second)
      journal should ===(Seq(
        JournalRow(4, "persistence-id-b", 4),
        JournalRow(5, "persistence-id-b", 5),
        JournalRow(6, "persistence-id-b", 6),
      ))
    }
    "return 0 and not delete any rows if there are no snapshots or journal entries for the given persistence id" in {
      val dao = AkkaDao(akkaDb, () => today)

      val result = Await.result(dao.deletePersistenceId("non-existent-persistence-id"), 1.second)

      result should be(0)

      val snapshots = Await.result(akkaDb.run(akkaDb.snapshotTable.result), 1.second)
      snapshots should ===(snapshotRows)

      val journal = Await.result(akkaDb.run(akkaDb.journalTable.result), 1.second)
      journal should ===(journalRows)
    }
  }
}
