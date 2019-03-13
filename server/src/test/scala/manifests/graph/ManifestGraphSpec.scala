package manifests.graph

import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Source, SourceQueueWithComplete}
import akka.testkit.TestProbe
import controllers.ArrivalGenerator
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.{Arrival, ArrivalKey, SDateLike}
import manifests.actors.RegisteredArrivals
import manifests.passengers.BestAvailableManifest
import services.SDate
import services.graphstages.Crunch

import scala.util.Success
import scala.concurrent.duration._

class ManifestGraphSpec extends ManifestGraphTestLike {

  val scheduled = SDate("2019-03-06T12:00:00Z")
  "Given an arrival is sent into the ManifestGraph then we should find the manifest for that flight in the sink" >> {

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

    val graphInput = createAndRunGraph(manifestSinkProbe, registeredArrivalSinkProbe, testManifest, None)

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

    val graphInput = createAndRunGraph(
      manifestSinkProbe,
      registeredArrivalSinkProbe,
      testManifest,
      Some(RegisteredArrivals(Map(ArrivalKey(testArrival) -> Option(scheduled.millisSinceEpoch)))),
      Crunch.isDueLookup
    )
    graphInput.offer(List(testArrival))

    manifestSinkProbe.expectNoMessage(1 second)

    graphInput.complete()

    success
  }

  def createAndRunGraph(manifestProbe: TestProbe, registeredArrivalSinkProbe: TestProbe, testManifest: BestAvailableManifest, initialRegisteredArrivals: Option[RegisteredArrivals], isDueLookup: (ArrivalKey, MillisSinceEpoch, SDateLike) => Boolean = (_, _, _) => true): SourceQueueWithComplete[List[Arrival]] = {
    val arrivalsSource = Source.queue[List[Arrival]](0, OverflowStrategy.backpressure)
    val minLookupQueueRefreshIntervalMillis = 0L
    val expireAfterMillis = (3 hours).length
    val batchStage = new BatchStage(() => SDate("2019-03-06T11:00:00Z"), isDueLookup, 1, expireAfterMillis, initialRegisteredArrivals, minLookupQueueRefreshIntervalMillis)

    val lookupStage = new LookupStage("STN", MockManifestLookupService(testManifest))

    val graph = ManifestsGraph(
      arrivalsSource,
      batchStage,
      lookupStage,
      manifestProbe.ref,
      registeredArrivalSinkProbe.ref
    )

    val graphInput: SourceQueueWithComplete[List[Arrival]] = graph.run()

    graphInput
  }
}
