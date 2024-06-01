package services.dataretention

import akka.actor.ActorSystem
import akka.testkit.TestProbe
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.homeoffice.drt.ports.AclFeedSource
import uk.gov.homeoffice.drt.ports.Terminals.{T1, T2}
import uk.gov.homeoffice.drt.time.{SDate, UtcDate}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

class DataRetentionHandlerSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {
  implicit val system: ActorSystem = ActorSystem()

  override def afterAll(): Unit = {
    system.terminate()
  }

  "closestPreRetentionDate" should {
    "return the day prior to the retention period for a short retention period" in {
      val retentionPeriod = 7.days
      val today = UtcDate(2024, 5, 20)

      val result = DataRetentionHandler.closestPreRetentionDate(retentionPeriod, today)

      result should ===(UtcDate(2024, 5, 12))
    }
    "return the day prior to the retention period for a long retention period" in {
      val years = 5
      val retentionPeriod = years * 365.days
      val today = UtcDate(2024, 5, 20)

      val result = DataRetentionHandler.closestPreRetentionDate(retentionPeriod, today)

      result should ===(UtcDate(2019, 5, 21))
    }
  }

  "persistenceIdsForPurge" should {
    "return a list of persistence ids with dates 8 days ago for the given terminals and a 7 day retention period" in {
      val terminals = Seq(T1, T2)
      val retentionPeriod = 7.days
      val persistenceIds = DataRetentionHandler
        .persistenceIdsForFullPurge(terminals, retentionPeriod, Set(AclFeedSource))(UtcDate(2024, 5, 20))

      val expectedPersistenceIds = Seq(
        "terminal-flights-t1-2024-05-12",
        "terminal-flights-t2-2024-05-12",
        "terminal-passengers-t1-2024-05-12",
        "terminal-passengers-t2-2024-05-12",
        "terminal-queues-t1-2024-05-12",
        "terminal-queues-t2-2024-05-12",
        "terminal-staff-t1-2024-05-12",
        "terminal-staff-t2-2024-05-12",
        s"${AclFeedSource.id}-feed-arrivals-t1-2024-05-12",
        s"${AclFeedSource.id}-feed-arrivals-t2-2024-05-12",
      )
      persistenceIds.toSeq.sorted should ===(expectedPersistenceIds.sorted)
    }
  }

  "retentionForecastDateRange" should {
    "return a range of dates starting from earliest retention date and ending forecast days later" in {
      val retentionPeriod = 10.days
      val maxForecastDays = 3
      val today = SDate("2024-05-20")

      val result = DataRetentionHandler.retentionForecastDateRange(retentionPeriod, maxForecastDays, today)

      result should ===(Seq(
        UtcDate(2024, 5, 10),
        UtcDate(2024, 5, 11),
        UtcDate(2024, 5, 12),
        UtcDate(2024, 5, 13),
      ))
    }
  }

  "preRetentionForecastPersistenceIds" should {
    "return a list of persistence ids including a) non-date ids, b) date ids for dates in the " in {
      val retentionPeriod = 7.days
      val maxForecastDays = 1
      val terminals = Seq(T1)
      val persistenceIds = DataRetentionHandler
        .persistenceIdsForSequenceNumberPurge(retentionPeriod, maxForecastDays, terminals, Set(AclFeedSource))(UtcDate(2024, 5, 20))

      persistenceIds.toSeq.sorted should === (Seq(
        "terminal-flights-t1-2024-05-13",
        "terminal-flights-t1-2024-05-14",
        "terminal-passengers-t1-2024-05-13",
        "terminal-passengers-t1-2024-05-14",
        "terminal-queues-t1-2024-05-13",
        "terminal-queues-t1-2024-05-14",
        "terminal-staff-t1-2024-05-13",
        "terminal-staff-t1-2024-05-14",
        "acl-feed-arrivals-t1-2024-05-13",
        "acl-feed-arrivals-t1-2024-05-14",
        "daily-pax",
        "actors.ForecastBaseArrivalsActor-forecast-base",
        "actors.LiveBaseArrivalsActor-live-base",
        "actors.ForecastPortArrivalsActor-forecast-port",
        "actors.LiveArrivalsActor-live",
        "shifts-store",
        "staff-movements-store",
        "fixedPoints-store",
      ).sorted)
    }
  }

  "purgeDataOutsideRetentionPeriod" should {
    "call full deletion for ids returned by the full purge provider, and sequence number deletion for ids " +
      "returned by the sequence number ids purge provider" in {
      val testProbeFullDelete = TestProbe()
      val testProbeSequenceNrDelete = TestProbe()
      val testProbeGetSequenceNumberBeforeRetentionPeriod = TestProbe()

      val retentionPeriod = 7.days
      val today = UtcDate(2024, 5, 20)
      val lastSeqNrBeforeRetPeriod = 1

      val handler = DataRetentionHandler(
        persistenceIdsForSequenceNumberPurge = _ => Seq("partial-delete-pid-a", "partial-delete-pid-b"),
        preRetentionPersistenceIdsForFullPurge = _ => Seq("full-delete-pid-a-2024-05-13", "full-delete-pid-b-2024-05-13"),
        persistenceIdsForDate = _ => Seq.empty,
        retentionPeriod = retentionPeriod,
        now = () => SDate(today),
        deletePersistenceId = pid => {
          testProbeFullDelete.ref ! pid
          Future.successful(1)
        },
        deleteLowerSequenceNumbers = (pid, seqNr) => {
          testProbeSequenceNrDelete.ref ! (pid, seqNr)
          Future.successful((1, 1))
        },
        getSequenceNumberBeforeRetentionPeriod = (pid, rp) => {
          testProbeGetSequenceNumberBeforeRetentionPeriod.ref ! (pid, rp)
          Future.successful(Option(lastSeqNrBeforeRetPeriod))
        },
      )

      handler.purgeDataOutsideRetentionPeriod()

      testProbeFullDelete.expectMsg("full-delete-pid-a-2024-05-13")
      testProbeFullDelete.expectMsg("full-delete-pid-b-2024-05-13")
      testProbeSequenceNrDelete.expectMsg(("partial-delete-pid-a", lastSeqNrBeforeRetPeriod))
      testProbeSequenceNrDelete.expectMsg(("partial-delete-pid-b", lastSeqNrBeforeRetPeriod))
      testProbeGetSequenceNumberBeforeRetentionPeriod.expectMsg(("partial-delete-pid-a", retentionPeriod))
      testProbeGetSequenceNumberBeforeRetentionPeriod.expectMsg(("partial-delete-pid-b", retentionPeriod))
    }
  }

  "persistenceIdsForDate" should {
    "return a list of persistence ids with dates for the given terminals and sources" in {
      val terminals = Seq(T1, T2)
      val persistenceIds = DataRetentionHandler
        .persistenceIdsForDate(terminals, Set(AclFeedSource))(UtcDate(2024, 5, 20))

      val expectedPersistenceIds = Seq(
        "terminal-flights-t1-2024-05-20",
        "terminal-flights-t2-2024-05-20",
        "terminal-passengers-t1-2024-05-20",
        "terminal-passengers-t2-2024-05-20",
        "terminal-queues-t1-2024-05-20",
        "terminal-queues-t2-2024-05-20",
        "terminal-staff-t1-2024-05-20",
        "terminal-staff-t2-2024-05-20",
        s"${AclFeedSource.id}-feed-arrivals-t1-2024-05-20",
        s"${AclFeedSource.id}-feed-arrivals-t2-2024-05-20",
      )
      persistenceIds.toSeq.sorted should ===(expectedPersistenceIds.sorted)
    }
  }

  "purgeDateRange" should {
    "call deletePersistenceId for each date in the range" in {
      val testProbeDeletePersistenceId = TestProbe()

      val retentionPeriod = 7.days
      val today = UtcDate(2024, 5, 20)

      val handler = DataRetentionHandler(
        persistenceIdsForSequenceNumberPurge = _ => Seq.empty,
        preRetentionPersistenceIdsForFullPurge = _ => Seq.empty,
        persistenceIdsForDate = date => Seq(s"full-delete-pid-a-${date.toISOString}", s"full-delete-pid-b-${date.toISOString}"),
        retentionPeriod = retentionPeriod,
        now = () => SDate(today),
        deletePersistenceId = pid => {
          testProbeDeletePersistenceId.ref ! pid
          Future.successful(1)
        },
        deleteLowerSequenceNumbers = (_, _) => Future.successful((0, 0)),
        getSequenceNumberBeforeRetentionPeriod = (_, _) => Future.successful(None),
      )

      val start = UtcDate(2024, 5, 10)
      val end = UtcDate(2024, 5, 12)

      handler.purgeDateRange(start, end)

      testProbeDeletePersistenceId.expectMsg("full-delete-pid-a-2024-05-10")
      testProbeDeletePersistenceId.expectMsg("full-delete-pid-b-2024-05-10")
      testProbeDeletePersistenceId.expectMsg("full-delete-pid-a-2024-05-11")
      testProbeDeletePersistenceId.expectMsg("full-delete-pid-b-2024-05-11")
      testProbeDeletePersistenceId.expectMsg("full-delete-pid-a-2024-05-12")
      testProbeDeletePersistenceId.expectMsg("full-delete-pid-b-2024-05-12")
    }
  }

  "dateIsSafeToPurge" should {
    val retentionPeriod = 5 * 365.days
    val today = UtcDate(2024, 5, 20)
    val isSafe = DataRetentionHandler.dateIsSafeToPurge(retentionPeriod, () => SDate(today))

    "return true if the date is outside the retention period" in {
      isSafe(UtcDate(2019, 5, 21)) should ===(true)
    }

    "return false if the date is within the retention period" in {
      isSafe(UtcDate(2019, 5, 22)) should ===(false)
    }
  }
}
