package manifests.graph

import akka.NotUsed
import akka.pattern.pipe
import akka.stream.scaladsl.{Sink, Source}
import akka.testkit.TestProbe
import controllers.ArrivalGenerator
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.{Arrival, ArrivalKey, SDateLike}
import graphs.SinkToSourceBridge
import manifests.actors.RegisteredArrivals
import manifests.passengers.BestAvailableManifest
import services.SDate
import services.graphstages.Crunch

import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.duration._


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
    val registeredArrivalSinkProbe = TestProbe(name = "registered-arrival-test-probe")

    val testArrival = ArrivalGenerator.arrival(schDt = "2019-03-06T12:00:00Z")

    val (sink, source) = createAndRunGraph(registeredArrivalSinkProbe, testManifest, None, Crunch.isDueLookup, now = () => SDate("2019-03-06T11:00:00Z"))
    Source(List(List(testArrival))).runWith(sink)

    source.runWith(Sink.seq).pipeTo(manifestSinkProbe.ref)

    manifestSinkProbe.expectMsg(2 seconds, List(List(testManifest)))

    success
  }

  "Given an initial registered arrival with a recent lookup time " +
    "When the same arrival is sent into the ManifestGraph " +
    "Then no manifests should appear in the sink" >> {

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

    val lastLookup = scheduled.millisSinceEpoch

    val (requestSink, responseSource) = createAndRunGraph(
      registeredArrivalSinkProbe,
      testManifest,
      Some(RegisteredArrivals(mutable.SortedMap(ArrivalKey(testArrival) -> Option(lastLookup)))),
      Crunch.isDueLookup,
      () => SDate("2019-03-06T11:00:00Z"))

    Source(List(List(testArrival))).runWith(requestSink)

    val responses = Await.result(responseSource.runWith(Sink.seq), 2 second)

    responses.isEmpty
  }

  "Given an initial registered arrival with a very olf lookup time " +
    "When the same arrival is sent into the ManifestGraph " +
    "Then the manifest should appear in the sink" >> {

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

    val lastLookup = scheduled.addDays(-100).millisSinceEpoch

    val (requestSink, responseSource) = createAndRunGraph(
      registeredArrivalSinkProbe,
      testManifest,
      Some(RegisteredArrivals(mutable.SortedMap(ArrivalKey(testArrival) -> Option(lastLookup)))),
      Crunch.isDueLookup,
      () => SDate("2019-03-06T11:00:00Z"))

    Source(List(List(testArrival))).runWith(requestSink)

    val responses = Await.result(responseSource.runWith(Sink.seq), 2 second)

    responses.nonEmpty
  }

  "Given a lookup time 100 days earlier than now, and a scheduled date within the next day " +
    "isDueLookup should return true" >> {
    val now = SDate("2019-01-01")
    val scheduled = now.addHours(1)
    val lastLookup = now.addDays(-100)

    val result = Crunch.isDueLookup(scheduled.millisSinceEpoch, lastLookup.millisSinceEpoch, now)

    result === true
  }

  "Given a lookup time the same time as now, and a scheduled date within the next day " +
    "isDueLookup should return true" >> {
    val now = SDate("2019-01-01")
    val scheduled = now.addHours(1)
    val lastLookup = now

    val result = Crunch.isDueLookup(scheduled.millisSinceEpoch, lastLookup.millisSinceEpoch, now)

    result === false
  }

  def createAndRunGraph(registeredArrivalSinkProbe: TestProbe,
                        testManifest: BestAvailableManifest,
                        initialRegisteredArrivals: Option[RegisteredArrivals],
                        isDueLookup: (MillisSinceEpoch, MillisSinceEpoch, SDateLike) => Boolean,
                        now: () => SDateLike): (Sink[List[Arrival], NotUsed], Source[List[BestAvailableManifest], NotUsed]) = {
    val expireAfterMillis = (3 hours).length

    val batchStage = new BatchStage(now, isDueLookup, 1, expireAfterMillis, initialRegisteredArrivals, 0)

    val (manifestRequestsSource, _, manifestRequestsSink) = SinkToSourceBridge[List[Arrival]]
    val (manifestResponsesSource, _, manifestResponsesSink) = SinkToSourceBridge[List[BestAvailableManifest]]

    ManifestsGraph(
      manifestRequestsSource,
      batchStage,
      manifestResponsesSink,
      registeredArrivalSinkProbe.ref,
      "STN",
      MockManifestLookupService(testManifest)
    ).run()

    (manifestRequestsSink, manifestResponsesSource)
  }
}
