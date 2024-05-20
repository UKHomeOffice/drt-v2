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

  "persistenceIdsForPurge" should {
    "return a list of persistence ids for the given terminals and retention period" in {
      val terminals = Seq(T1, T2)
      val retentionPeriod = 7.days
      val persistenceIds = DataRetentionHandler
        .persistenceIdsForPurge(terminals, retentionPeriod, Set(AclFeedSource))(UtcDate(2024, 5, 20))

      val expectedPersistenceIds = Seq(
        "terminal-flights-T1-2024-05-13",
        "terminal-flights-T2-2024-05-13",
        "terminal-passengers-T1-2024-05-13",
        "terminal-passengers-T2-2024-05-13",
        "terminal-queues-T1-2024-05-13",
        "terminal-queues-T2-2024-05-13",
        "terminal-staff-T1-2024-05-13",
        "terminal-staff-T2-2024-05-13",
        s"${AclFeedSource.id}-feed-arrivals-T1-2024-05-13",
        s"${AclFeedSource.id}-feed-arrivals-T2-2024-05-13",
      )
      persistenceIds.sorted should ===(expectedPersistenceIds.sorted)
    }
  }

  "preRetentionForecastPersistenceIds" should {
    "return a list of persistence ids for the given retention period and max forecast days" in {
      val retentionPeriod = 7.days
      val maxForecastDays = 1
      val terminals = Seq(T1)
      val persistenceIds = DataRetentionHandler
        .preRetentionForecastPersistenceIds(retentionPeriod, maxForecastDays, terminals, Set(AclFeedSource))(UtcDate(2024, 5, 20))

      persistenceIds.sorted should === (Seq(
        "terminal-flights-T1-2024-05-12",
        "terminal-flights-T1-2024-05-13",
        "terminal-passengers-T1-2024-05-12",
        "terminal-passengers-T1-2024-05-13",
        "terminal-queues-T1-2024-05-12",
        "terminal-queues-T1-2024-05-13",
        "terminal-staff-T1-2024-05-12",
        "terminal-staff-T1-2024-05-13",
        "acl-feed-arrivals-T1-2024-05-12",
        "acl-feed-arrivals-T1-2024-05-13",
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
    "call deletePersistenceId for" in {
      val testProbeFullDelete = TestProbe()
      val testProbePartialDelete = TestProbe()
      val testProbeGetSequenceNumberBeforeRetentionPeriod = TestProbe()

      val retentionPeriod = 7.days
      val today = UtcDate(2024, 5, 20)
      val lastSeqNrBeforeRetPeriod = 1

      val handler = DataRetentionHandler(
        persistenceIdsForSequenceNumberPurge = _ => Seq("partial-delete-pid-a", "partial-delete-pid-b"),
        persistenceIdsForPurge = _ => Seq("full-delete-pid-a-2024-05-13", "full-delete-pid-b-2024-05-13"),
        retentionPeriod = retentionPeriod,
        now = () => SDate(today),
        deletePersistenceId = pid => {
          testProbeFullDelete.ref ! pid
          Future.successful(1)
        },
        deleteLowerSequenceNumbers = (pid, seqNr) => {
          testProbePartialDelete.ref ! (pid, seqNr)
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
      testProbePartialDelete.expectMsg(("partial-delete-pid-a", lastSeqNrBeforeRetPeriod))
      testProbePartialDelete.expectMsg(("partial-delete-pid-b", lastSeqNrBeforeRetPeriod))
      testProbeGetSequenceNumberBeforeRetentionPeriod.expectMsg(("partial-delete-pid-a", retentionPeriod))
      testProbeGetSequenceNumberBeforeRetentionPeriod.expectMsg(("partial-delete-pid-b", retentionPeriod))
    }
  }
}
