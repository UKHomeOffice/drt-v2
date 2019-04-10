package services.crunch

import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{Attributes, OverflowStrategy}
import manifests.passengers.S3ManifestPoller
import passengersplits.parsing.VoyageManifestParser.VoyageManifest
import server.feeds.{ManifestsFeedResponse, ManifestsFeedSuccess}
import services.graphstages.{DqManifests, TestApiProvider}

import scala.concurrent.Await
import scala.concurrent.duration._


class S3ManifestPollerSpec extends CrunchTestLike {
  "Given a manifest queue manager with a mock API provider " +
    "When I enqueue a manifest in the mock provider " +
    "Then I should see the manifest come out of the sink" >> {
    val provider = new TestApiProvider()
    provider.enqueueContent(Seq(("someZip_1234", provider.jsonContentStnDc)))

    val sink = Sink.queue[ManifestsFeedResponse]().withAttributes(Attributes.inputBuffer(1, 1))
    val (sourceQueue, sinkQueue) = Source
      .queue[ManifestsFeedResponse](0, OverflowStrategy.backpressure)
      .toMat(sink)(Keep.both)
      .run()

    def getNext: ManifestsFeedResponse = Await.result(sinkQueue.pull(), 1 second).get

    val queueManager: S3ManifestPoller = new S3ManifestPoller(sourceQueue, "LHR", "", provider)
    queueManager.startPollingForManifests()

    val result = getNext match {
      case ManifestsFeedSuccess(dqManifests, _) => dqManifests
      case _ => false
    }
    val expected = DqManifests("someZip_1234", Set())

    result === expected
  }
}
