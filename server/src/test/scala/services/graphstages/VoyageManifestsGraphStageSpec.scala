package services.graphstages

import java.io.InputStream

import actors.acking.AckingReceiver.StreamCompleted
import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl.{GraphDSL, RunnableGraph, Sink}
import akka.stream.{ActorMaterializer, ClosedShape}
import akka.testkit.{TestKit, TestProbe}
import drt.server.feeds.api.ApiProviderLike
import org.slf4j.{Logger, LoggerFactory}
import org.specs2.mutable.SpecificationLike
import server.feeds.ManifestsFeedSuccess

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._

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

class VoyageManifestsGraphStageSpec extends TestKit(ActorSystem("VoyageManifestsGraphStageSpec"))
  with SpecificationLike {

  implicit val actorSystem: ActorSystem = system
  implicit val materializer: ActorMaterializer = ActorMaterializer()

  "Given a manifests stage with a test provider " +
    "When I give the test provider a manifest " +
    "Then I should observe that manifest and its zip file name in the stage's output" >> {
    val provider = new TestApiProvider()
    provider.enqueueContent(Seq(("someZip_1234", provider.jsonContentStnDc)))

    val probe = TestProbe()

    val stage: RunnableGraph[NotUsed] = TestableVoyageManifestsGraphStage(probe, "STN", provider)

    stage.run()

    val lastSeenAndManifests = probe
      .receiveN(1, 5 seconds)
      .map {
        case ManifestsFeedSuccess(manifests, _) => (manifests.lastSeenFileName, manifests.manifests.size)
      }
      .toList

    val expected = List(("someZip_1234", 1))

    lastSeenAndManifests === expected
  }

  "Given a manifests stage with a test provider " +
    "When I give the test provider 3 manifests from different zip files " +
    "Then I should observe 3 manifests with the latest zip file name in the stage's output" >> {
    val provider = new TestApiProvider()
    val zipFileNamesAndManifests = Seq(
      ("someZip_1234", provider.jsonContentNonStn),
      ("someZip_4444", provider.jsonContentStnCi),
      ("someZip_0101", provider.jsonContentStnDc)
    )

    provider.enqueueContent(zipFileNamesAndManifests)

    val probe = TestProbe()

    val stage: RunnableGraph[NotUsed] = TestableVoyageManifestsGraphStage(probe, "STN", provider)

    stage.run()

    val lastSeenAndManifestCount = probe
      .receiveN(1, 5 seconds)
      .map {
        case ManifestsFeedSuccess(manifests, _) => (manifests.lastSeenFileName, manifests.manifests.size)
      }
      .toList

    val expectedManifestCount = 1
    val expected = List(("someZip_4444", expectedManifestCount))

    lastSeenAndManifestCount === expected
  }
}

object TestableVoyageManifestsGraphStage {
  val oneDayMillis: Int = 60 * 60 * 24 * 1000

  def apply(testProbe: TestProbe, portCode: String, provider: ApiProviderLike): RunnableGraph[NotUsed] = {
    val workloadStage = new VoyageManifestsGraphStage(portCode, provider, "", 30000)

    import akka.stream.scaladsl.GraphDSL.Implicits._

    val graph = GraphDSL.create() {

      implicit builder =>
        val workload = builder.add(workloadStage.async)
        val sink = builder.add(Sink.actorRef(testProbe.ref, StreamCompleted))

        workload ~> sink

        ClosedShape
    }

    RunnableGraph.fromGraph(graph)
  }
}
