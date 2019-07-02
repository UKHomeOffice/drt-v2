package manifests.graph

import actors.AckingReceiver.Ack
import actors.VoyageManifestsRequestActor
import akka.actor.{ActorRef, Props}
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Source, SourceQueueWithComplete}
import akka.testkit.TestProbe
import controllers.ArrivalGenerator
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.{Arrival, ArrivalKey, SDateLike}
import manifests.ManifestLookupLike
import manifests.actors.RegisteredArrivals
import manifests.passengers.BestAvailableManifest
import services.SDate
import services.graphstages.Crunch

import scala.collection.immutable.SortedMap
import scala.concurrent.duration._


class TestableVoyageManifestsRequestActor(portCode: String, manifestLookup: ManifestLookupLike, probe: TestProbe) extends VoyageManifestsRequestActor(portCode, manifestLookup) {
  override def senderRef(): ActorRef = probe.ref

  override def handleManifestTries(bestManifests: List[Option[BestAvailableManifest]]): Unit = senderRef() ! ManifestTries(bestManifests)
}


class ManifestGraphSpec extends ManifestGraphTestLike {

  val scheduled = SDate("2019-03-06T12:00:00Z")
  "Given an arrival is sent into the ManifestGraph then we should find the manifest for that flight in the sink" >> {

    val testManifest = BestAvailableManifest(
      "test",
      "STN",
      "TST",
      "1234",
      "TST",
      scheduled,
      List()
    )
    val manifestSinkProbe = TestProbe("manifest-test-probe")
    val manifestSink = system.actorOf(Props(classOf[TestableVoyageManifestsRequestActor], "LHR", MockManifestLookupService(testManifest), manifestSinkProbe))
    val registeredArrivalSinkProbe = TestProbe(name = "registered-arrival-test-probe")

    val graphInput = createAndRunGraph(manifestSink, registeredArrivalSinkProbe, testManifest, None)

    val testArrival = ArrivalGenerator.apiFlight(schDt = "2019-03-06T12:00:00Z")
    graphInput.offer(List(testArrival))

    manifestSinkProbe.expectMsg(ManifestTries(List(Option(testManifest))))

    graphInput.complete()

    success
  }

  "Given an initial registered arrival with a recent lookup time " +
    "When the same arrival is sent into the ManifestGraph " +
    "Then no manifests should appear in the sink" >> {

    val manifestSinkProbe = TestProbe(name = "manifest-test-probe")
    val registeredArrivalSinkProbe = TestProbe(name = "registered-arrival-test-probe")

    val testManifest = BestAvailableManifest(
      "test",
      "STN",
      "TST",
      "1234",
      "TST",
      scheduled,
      List()
    )

    val testArrival = ArrivalGenerator.apiFlight(schDt = "2019-03-06T12:00:00Z")

    val manifestSink = system.actorOf(Props(classOf[TestableVoyageManifestsRequestActor], "LHR", MockManifestLookupService(testManifest), manifestSinkProbe))

    val graphInput = createAndRunGraph(
      manifestSink,
      registeredArrivalSinkProbe,
      testManifest,
      Some(RegisteredArrivals(SortedMap(ArrivalKey(testArrival) -> Option(scheduled.millisSinceEpoch)))),
      Crunch.isDueLookup
    )
    graphInput.offer(List(testArrival))

    manifestSinkProbe.expectNoMessage(1 second)

    graphInput.complete()

    success
  }

  def createAndRunGraph(manifestProbeActor: ActorRef, registeredArrivalSinkProbe: TestProbe, testManifest: BestAvailableManifest, initialRegisteredArrivals: Option[RegisteredArrivals], isDueLookup: (ArrivalKey, MillisSinceEpoch, SDateLike) => Boolean = (_, _, _) => true): SourceQueueWithComplete[List[Arrival]] = {
    val arrivalsSource = Source.queue[List[Arrival]](0, OverflowStrategy.backpressure)
    val minLookupQueueRefreshIntervalMillis = 50L
    val expireAfterMillis = (3 hours).length
    val batchStage = new BatchStage(() => SDate("2019-03-06T11:00:00Z"), isDueLookup, 1, expireAfterMillis, initialRegisteredArrivals, minLookupQueueRefreshIntervalMillis)

    val lookupStage = new LookupStage("STN", MockManifestLookupService(testManifest))

    val graph = ManifestsGraph(
      arrivalsSource,
      batchStage,
      lookupStage,
      manifestProbeActor,
      registeredArrivalSinkProbe.ref
    )

    val graphInput: SourceQueueWithComplete[List[Arrival]] = graph.run()

    graphInput
  }
}
