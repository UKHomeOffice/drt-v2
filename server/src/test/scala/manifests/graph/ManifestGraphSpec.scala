package manifests.graph

import actors.{ManifestTries, VoyageManifestsRequestActor}
import akka.actor.{ActorRef, Props}
import akka.testkit.TestProbe
import controllers.ArrivalGenerator
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.{ArrivalKey, SDateLike}
import manifests.ManifestLookupLike
import manifests.actors.RegisteredArrivals
import manifests.passengers.BestAvailableManifest
import services.SDate
import services.graphstages.Crunch

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._


class TestableVoyageManifestsRequestActor(portCode: String, manifestLookup: ManifestLookupLike, probe: TestProbe, now: () => SDateLike) extends VoyageManifestsRequestActor(portCode, manifestLookup, now, 1, 0) {
  override def senderRef(): ActorRef = probe.ref

  override def handleManifestTries(bestManifests: List[Option[BestAvailableManifest]]): Unit = {
    senderRef() ! ManifestTries(bestManifests)
  }
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
    val manifestSink = system.actorOf(Props(classOf[TestableVoyageManifestsRequestActor], "LHR", MockManifestLookupService(testManifest), manifestSinkProbe, () => SDate.now()))
    val registeredArrivalSinkProbe = TestProbe(name = "registered-arrival-test-probe")

    val graphInput = createAndRunGraph(manifestSink, registeredArrivalSinkProbe, testManifest, None)

    val testArrival = ArrivalGenerator.arrival(schDt = "2019-03-06T12:00:00Z")
    graphInput ! List(testArrival)

    manifestSinkProbe.expectMsg(5 seconds, ManifestTries(List(Option(testManifest))))

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

    val testArrival = ArrivalGenerator.arrival(schDt = "2019-03-06T12:00:00Z")

    val manifestSink = system.actorOf(Props(classOf[TestableVoyageManifestsRequestActor], "LHR", MockManifestLookupService(testManifest), manifestSinkProbe, () => SDate.now()))

    val graphInput = createAndRunGraph(
      manifestSink,
      registeredArrivalSinkProbe,
      testManifest,
      Some(RegisteredArrivals(mutable.SortedMap(ArrivalKey(testArrival) -> Option(scheduled.millisSinceEpoch)))),
      Crunch.isDueLookup
    )
    graphInput ! List(testArrival)

    manifestSinkProbe.expectNoMessage(1 second)

    success
  }

  def createAndRunGraph(manifestProbeActor: ActorRef,
                        registeredArrivalSinkProbe: TestProbe,
                        testManifest: BestAvailableManifest,
                        initialRegisteredArrivals: Option[RegisteredArrivals],
                        isDueLookup: (ArrivalKey, MillisSinceEpoch, SDateLike) => Boolean = (_, _, _) => true): ActorRef = {
    val expireAfterMillis = (3 hours).length

    implicit val ec: ExecutionContext = ExecutionContext.global
    val batchStage = new BatchStage(() => SDate("2019-03-06T11:00:00Z"), isDueLookup, 1, expireAfterMillis, initialRegisteredArrivals, 0)

    val graph = ManifestsGraph(
      batchStage,
      manifestProbeActor,
      registeredArrivalSinkProbe.ref,
      "STN",
      MockManifestLookupService(testManifest)
    )

    val graphInput: ActorRef = graph.run()

    graphInput
  }
}
