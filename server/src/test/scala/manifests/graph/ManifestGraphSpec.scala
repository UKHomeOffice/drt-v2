package manifests.graph

import akka.NotUsed
import akka.pattern.pipe
import akka.stream.UniqueKillSwitch
import akka.stream.scaladsl.{Sink, Source}
import akka.testkit.TestProbe
import controllers.ArrivalGenerator
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.SplitRatiosNs.SplitSources.Historical
import drt.shared.{Arrival, ArrivalKey, CarrierCode, PortCode, SDateLike, VoyageNumber}
import graphs.SinkToSourceBridge
import manifests.actors.RegisteredArrivals
import manifests.passengers.BestAvailableManifest
import services.SDate
import services.graphstages.Crunch

import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.duration._


class ManifestGraphSpec extends ManifestGraphTestLike {
  sequential
  isolated

  val scheduled = SDate("2019-03-06T12:00:00Z")

  "Given an arrival is sent into the ManifestGraph then we should find the manifest for that flight in the sink" >> {

    val testManifest = BestAvailableManifest(
      Historical,
      PortCode("STN"),
      PortCode("TST"),
      VoyageNumber("1234"),
      CarrierCode("TST"),
      scheduled,
      List()
    )
    val manifestSinkProbe = TestProbe("manifest-test-probe")
    val registeredArrivalSinkProbe = TestProbe(name = "registered-arrival-test-probe")

    val testArrival = ArrivalGenerator.arrival(iata = "BA0001", schDt = "2019-03-06T12:00:00Z")

    val (killSwitch, sink, source) = createAndRunGraph(registeredArrivalSinkProbe, testManifest, None, Crunch.isDueLookup, now = () => SDate("2019-03-06T11:00:00Z"))
    Source(List(List(testArrival))).runWith(sink)

    source.runWith(Sink.seq).pipeTo(manifestSinkProbe.ref)

    manifestSinkProbe.expectMsg(2 seconds, List(List(testManifest)))

    killSwitch.shutdown()

    success
  }

  "Given an initial registered arrival with a recent lookup time " +
    "When the same arrival is sent into the ManifestGraph " +
    "Then no manifests should appear in the sink" >> {

    val registeredArrivalSinkProbe = TestProbe(name = "registered-arrival-test-probe")

    val testManifest = BestAvailableManifest(
      Historical,
      PortCode("STN"),
      PortCode("TST"),
      VoyageNumber("1234"),
      CarrierCode("TST"),
      scheduled,
      List()
    )

    val testArrival = ArrivalGenerator.arrival(iata = "BA0001", schDt = "2019-03-06T12:00:00Z")

    val lastLookup = scheduled.millisSinceEpoch

    val (killSwitch, requestSink, responseSource) = createAndRunGraph(
      registeredArrivalSinkProbe,
      testManifest,
      Some(RegisteredArrivals(mutable.SortedMap(ArrivalKey(testArrival) -> Option(lastLookup)))),
      Crunch.isDueLookup,
      () => SDate("2019-03-06T11:00:00Z"))

    Source(List(List(testArrival))).runWith(requestSink)

    val responses = Await.result(responseSource.runWith(Sink.seq), 2 second)

    killSwitch.shutdown()

    responses.isEmpty
  }

  "Given an initial registered arrival with a very old lookup time " +
    "When the same arrival is sent into the ManifestGraph " +
    "Then the manifest should appear in the sink" >> {

    val registeredArrivalSinkProbe = TestProbe(name = "registered-arrival-test-probe")

    val testManifest = BestAvailableManifest(
      Historical,
      PortCode("STN"),
      PortCode("TST"),
      VoyageNumber("1234"),
      CarrierCode("TST"),
      scheduled,
      List()
    )

    val testArrival = ArrivalGenerator.arrival(iata = "BA0001", schDt = "2019-03-06T12:00:00Z")

    val lastLookup = scheduled.addDays(-100).millisSinceEpoch

    val (killSwitch, requestSink, responseSource) = createAndRunGraph(
      registeredArrivalSinkProbe,
      testManifest,
      Some(RegisteredArrivals(mutable.SortedMap(ArrivalKey(testArrival) -> Option(lastLookup)))),
      Crunch.isDueLookup,
      () => SDate("2019-03-06T11:00:00Z"))

    Source(List(List(testArrival))).runWith(requestSink)

    val responses = Await.result(responseSource.runWith(Sink.seq), 2 second)

    killSwitch.shutdown()

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

  "Given a lookup time 8 days ago, and a scheduled date in 3 days time " +
    "isDueLookup should return true" >> {
    val now = SDate("2019-01-01")
    val scheduled = now.addDays(3)
    val lastLookup = now.addDays(-8)

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
                        now: () => SDateLike): (UniqueKillSwitch, Sink[List[Arrival], NotUsed], Source[List[BestAvailableManifest], NotUsed]) = {
    val expireAfterMillis = 3 * Crunch.oneHourMillis

    val batchStage = new BatchStage(now, isDueLookup, 1, expireAfterMillis, initialRegisteredArrivals, 0, (_: MillisSinceEpoch) => true)

    val (manifestRequestsSource, _, manifestRequestsSink) = SinkToSourceBridge[List[Arrival]]
    val (manifestResponsesSource, _, manifestResponsesSink) = SinkToSourceBridge[List[BestAvailableManifest]]

    val killSwitch: UniqueKillSwitch = ManifestsGraph(
      manifestRequestsSource,
      batchStage,
      manifestResponsesSink,
      registeredArrivalSinkProbe.ref,
      PortCode("STN"),
      MockManifestLookupService(testManifest)
    ).run()

    (killSwitch, manifestRequestsSink, manifestResponsesSource)
  }
}
