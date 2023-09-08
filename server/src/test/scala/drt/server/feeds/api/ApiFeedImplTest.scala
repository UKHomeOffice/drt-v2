package drt.server.feeds.api

import akka.Done
import akka.actor.ActorRef
import akka.stream.KillSwitches
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.testkit.TestProbe
import drt.shared.CrunchApi.MillisSinceEpoch
import manifests.UniqueArrivalKey
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.arrivals.VoyageNumber
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.time.SDate

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}

case class MockManifestArrivalKeys(keysWithProcessedAts: List[(MillisSinceEpoch, Iterable[UniqueArrivalKey])]) extends ManifestArrivalKeys {
  var queuedKeysWithProcessedAts: List[(MillisSinceEpoch, Iterable[UniqueArrivalKey])] = keysWithProcessedAts
  override def nextKeys(since: MillisSinceEpoch): Future[(Option[MillisSinceEpoch], Iterable[UniqueArrivalKey])] = queuedKeysWithProcessedAts match {
    case (processedAt, keys) :: tail =>
      queuedKeysWithProcessedAts = tail
      Future.successful((Option(processedAt), keys))
    case _ =>
      Future.successful((None, Iterable()))
  }
}

case class MockManifestProcessor(probe: ActorRef) extends ManifestProcessor {
  override def reportNoNewData(processedAt: MillisSinceEpoch): Future[Done] =
    Future.successful(Done)

  override def process(uniqueArrivalKey: UniqueArrivalKey, processedAt: MillisSinceEpoch): Future[Done] = {
    probe ! ((uniqueArrivalKey, processedAt))
    Future.successful(Done)
  }

}

class ApiFeedImplTest extends CrunchTestLike {
  "A source" should {
    "continue with a collect" in {
      val c = Source(List(1, 2, 3))
        .collect {
          case 1 => 1
          case 3 => 3
        }
        .runWith(Sink.seq)

      Await.result(c, 1.second) === Seq(1, 3)
    }
  }
  "An ApiFeed" should {
    val key1 = UniqueArrivalKey(PortCode("LHR"), PortCode("JFK"), VoyageNumber(1), SDate("2022-06-01T00:00"))
    val processedAt1220 = SDate("2022-05-31T12:20").millisSinceEpoch
    val key2 = UniqueArrivalKey(PortCode("LHR"), PortCode("CDG"), VoyageNumber(2), SDate("2022-06-01T00:05"))
    val key3 = UniqueArrivalKey(PortCode("LHR"), PortCode("EDI"), VoyageNumber(3), SDate("2022-06-01T00:10"))
    val processedAt1230 = SDate("2022-05-31T12:30").millisSinceEpoch

    "process each unique arrival key it finds" in {
      val probe = TestProbe("apiFeed")
      val mockArrivalKeys = MockManifestArrivalKeys(List(
        (processedAt1220, Iterable(key1, key2)),
        (processedAt1230, Iterable(key3)),
      ))
      val mockProcessor = MockManifestProcessor(probe.ref)
      val feed = ApiFeedImpl(mockArrivalKeys, mockProcessor, 100.milliseconds)
      val killSwitch = feed.processFilesAfter(0L).viaMat(KillSwitches.single)(Keep.right).to(Sink.ignore).run()

      probe.expectMsg((key1, processedAt1220))
      probe.expectMsg((key2, processedAt1220))
      probe.expectMsg((key3, processedAt1230))
      probe.expectNoMessage(250.millis)

      killSwitch.shutdown()

      success
    }
  }
}
