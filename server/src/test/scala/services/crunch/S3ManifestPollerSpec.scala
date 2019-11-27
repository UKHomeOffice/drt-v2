package services.crunch

import java.io.InputStream

import akka.stream.scaladsl.{Keep, Sink, SinkQueueWithCancel, Source}
import akka.stream.{Attributes, OverflowStrategy}
import drt.server.feeds.api.ApiProviderLike
import drt.shared.PortCode
import manifests.passengers.S3ManifestPoller
import org.slf4j.{Logger, LoggerFactory}
import server.feeds.{DqManifests, ManifestsFeedResponse, ManifestsFeedSuccess}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class S3ManifestPollerSpec extends CrunchTestLike {
  val provider = new TestApiProvider()
  val sink: Sink[ManifestsFeedResponse, SinkQueueWithCancel[ManifestsFeedResponse]] = Sink.queue[ManifestsFeedResponse]().withAttributes(Attributes.inputBuffer(1, 1))
  val (sourceQueue, sinkQueue) = Source
    .queue[ManifestsFeedResponse](0, OverflowStrategy.backpressure)
    .toMat(sink)(Keep.both)
    .run()

  def getNext: ManifestsFeedResponse = Await.result(sinkQueue.pull(), 1 second).get

  "Given a manifest queue manager with a mock API provider " +
    "When I enqueue a manifest in the mock provider " +
    "Then I should see the manifest come out of the sink" >> {

    val queueManager: S3ManifestPoller = new S3ManifestPoller(sourceQueue, PortCode("LHR"), "", provider)

    provider.enqueueContent(Seq(("someZip_1234", provider.jsonContentStnDc)))

    queueManager.startPollingForManifests()

    val result = getNext match {
      case ManifestsFeedSuccess(dqManifests, _) => dqManifests
      case _ => false
    }
    val expected = DqManifests("someZip_1234", Set())

    result === expected
  }
}

class TestApiProvider() extends ApiProviderLike {
  val log: Logger = LoggerFactory.getLogger(getClass)
  var contentToPush: Seq[(String, String)] = Seq()

  override def manifestsFuture(latestFile: String): Future[Seq[(String, String)]] = {
    contentToPush match {
      case content if content.isEmpty =>
        log.info("No json to push")
        Future(Seq())

      case content =>
        contentToPush = Seq()
        Future(content)
    }
  }

  def enqueueContent(content: Seq[(String, String)]): Unit = {
    contentToPush = content
  }

  def jsonContentStnDc: String = {
    val jsonInputStream: InputStream = getClass.getClassLoader.getResourceAsStream("s3content/unzippedtest/drt_dq_160617_165737_5153/drt_160302_060000_FR3631_DC_4089.json")

    scala.io.Source.fromInputStream(jsonInputStream).getLines().mkString("\n")
  }

  def jsonContentStnCi: String = {
    val jsonInputStream: InputStream = getClass.getClassLoader.getResourceAsStream("s3content/unzippedtest/drt_dq_160617_165737_5153/drt_160302_092500_FR4542_CI_6033.json")

    scala.io.Source.fromInputStream(jsonInputStream).getLines().mkString("\n")
  }

  def jsonContentNonStn: String = {
    val jsonInputStream: InputStream = getClass.getClassLoader.getResourceAsStream("s3content/unzippedtest/drt_dq_160617_165737_5153/drt_160302_061500_BA8450_DC_5123.json")

    scala.io.Source.fromInputStream(jsonInputStream).getLines().mkString("\n")
  }
}

