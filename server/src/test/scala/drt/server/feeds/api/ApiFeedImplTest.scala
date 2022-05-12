package drt.server.feeds.api

import akka.Done
import akka.actor.ActorRef
import akka.stream.KillSwitches
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.testkit.TestProbe
import drt.shared.CrunchApi.MillisSinceEpoch
import manifests.UniqueArrivalKey
import services.SDate
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.arrivals.VoyageNumber
import uk.gov.homeoffice.drt.ports.PortCode

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.DurationInt

case class MockManifestArrivalKeys(keysWithProcessedAts: List[Iterable[(UniqueArrivalKey, MillisSinceEpoch)]]) extends ManifestArrivalKeys {
  var queuedKeysWithProcessedAts: List[Iterable[(UniqueArrivalKey, MillisSinceEpoch)]] = keysWithProcessedAts
  override def nextKeys(since: MillisSinceEpoch): Future[Iterable[(UniqueArrivalKey, MillisSinceEpoch)]] = queuedKeysWithProcessedAts match {
    case next :: tail =>
      queuedKeysWithProcessedAts = tail
      Future.successful(next)
    case _ =>
      Future.successful(Iterable())
  }
}

case class MockManifestProcessor(probe: ActorRef) extends ManifestProcessor {
  override def reportNoNewData(processedAt: MillisSinceEpoch): Future[Done] =
    Future.successful(Done)

  override def process(uniqueArrivalKey: UniqueArrivalKey, processedAt: MillisSinceEpoch): Future[Done] = {
    probe ! (uniqueArrivalKey, processedAt)
    Future.successful(Done)
  }

}

class ApiFeedImplTest extends CrunchTestLike {
  "A source" should {
    "continue with a collect" in {
      val c = Source(List(1, 2, 3))
        .collect {
          case 1 => "1 yes"
          case 3 => "3 yes"
        }
//        .map { c => println(s"got: $c")}
        .run()

      Await.ready(c, 1.second)
      success
    }
  }
  "An ApiFeed" should {
    val key1 = UniqueArrivalKey(PortCode("LHR"), PortCode("JFK"), VoyageNumber(1), SDate("2022-06-01T00:00"))
    val processedAt1220 = SDate("2022-05-31T12:20").millisSinceEpoch
    val key2 = UniqueArrivalKey(PortCode("LHR"), PortCode("CDG"), VoyageNumber(2), SDate("2022-06-01T00:05"))
    val processedAt1225 = SDate("2022-05-31T12:25").millisSinceEpoch
    val key3 = UniqueArrivalKey(PortCode("LHR"), PortCode("EDI"), VoyageNumber(3), SDate("2022-06-01T00:10"))
    val processedAt1230 = SDate("2022-05-31T12:30").millisSinceEpoch

    "process each unique arrival key it finds" in {
      val probe = TestProbe("apiFeed")
      val mockArrivalKeys = MockManifestArrivalKeys(List(
        Iterable((key1, processedAt1220), (key2, processedAt1225)),
        Iterable((key3, processedAt1230)),
      ))
      val mockProcessor = MockManifestProcessor(probe.ref)
      val feed = ApiFeedImpl(mockArrivalKeys, mockProcessor, 100.milliseconds)
      val killSwitch = feed.processFilesAfter(0L).viaMat(KillSwitches.single)(Keep.right).to(Sink.ignore).run()

      probe.expectMsg((key1, processedAt1220))
      probe.expectMsg((key2, processedAt1225))
      probe.expectMsg((key3, processedAt1230))
      probe.expectNoMessage(250.millis)

      killSwitch.shutdown()

      success
    }
  }
}
