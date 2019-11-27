//package services.crunch
//
//import akka.stream.scaladsl.{Keep, Sink, SinkQueueWithCancel, Source}
//import akka.stream.{Attributes, OverflowStrategy}
//import drt.shared.PortCode
//import manifests.passengers.S3ManifestPoller
//import server.feeds.{ManifestsFeedResponse, ManifestsFeedSuccess}
//import services.graphstages.{DqManifests, TestApiProvider}
//
//import scala.concurrent.Await
//import scala.concurrent.duration._
//
//
//class S3ManifestPollerSpec extends CrunchTestLike {
//  val provider = new TestApiProvider()
//  val sink: Sink[ManifestsFeedResponse, SinkQueueWithCancel[ManifestsFeedResponse]] = Sink.queue[ManifestsFeedResponse]().withAttributes(Attributes.inputBuffer(1, 1))
//  val (sourceQueue, sinkQueue) = Source
//    .queue[ManifestsFeedResponse](0, OverflowStrategy.backpressure)
//    .toMat(sink)(Keep.both)
//    .run()
//
//  def getNext: ManifestsFeedResponse = Await.result(sinkQueue.pull(), 1 second).get
//
//  "Given a manifest queue manager with a mock API provider " +
//    "When I enqueue a manifest in the mock provider " +
//    "Then I should see the manifest come out of the sink" >> {
//
//    val queueManager: S3ManifestPoller = new S3ManifestPoller(sourceQueue, PortCode("LHR"), "", provider)
//
//    provider.enqueueContent(Seq(("someZip_1234", provider.jsonContentStnDc)))
//
//    queueManager.startPollingForManifests()
//
//
//    val result = getNext match {
//      case ManifestsFeedSuccess(dqManifests, _) => dqManifests
//      case _ => false
//    }
//    val expected = DqManifests("someZip_1234", Set())
//
//    result === expected
//  }
//}
