package manifests

import org.apache.pekko.pattern.StatusReply
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.testkit.TestProbe
import controllers.ArrivalGenerator
import manifests.passengers.{BestAvailableManifest, ManifestPaxCount}
import services.crunch.VoyageManifestGenerator.euPassport
import services.crunch.deskrecs.OptimisationProviders
import services.crunch.{CrunchTestLike, VoyageManifestGenerator}
import uk.gov.homeoffice.drt.arrivals.{Arrival, VoyageNumber}
import uk.gov.homeoffice.drt.models.ManifestLike
import uk.gov.homeoffice.drt.ports.{LiveFeedSource, PortCode}
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources
import uk.gov.homeoffice.drt.time.SDateLike

import scala.concurrent.Future

case class MockManifestLookupService(bestAvailableManifest: BestAvailableManifest, historicManifestPax: ManifestPaxCount) extends ManifestLookupLike {
  override def maybeBestAvailableManifest(arrivalPort: PortCode,
                                          departurePort: PortCode,
                                          voyageNumber: VoyageNumber,
                                          scheduled: SDateLike): Future[(UniqueArrivalKey, Option[BestAvailableManifest])] =
    Future.successful((UniqueArrivalKey(arrivalPort, departurePort, voyageNumber, scheduled), Option(bestAvailableManifest)))

  override def maybeHistoricManifestPax(arrivalPort: PortCode, departurePort: PortCode, voyageNumber: VoyageNumber, scheduled: SDateLike): Future[(UniqueArrivalKey, Option[ManifestPaxCount])] =
    Future.successful((UniqueArrivalKey(arrivalPort, departurePort, voyageNumber, scheduled), Option(historicManifestPax)))

}

class ManifestProviderSpec extends CrunchTestLike {
  val arrival: Arrival = ArrivalGenerator.live(iata = "BA0001", schDt = "2021-06-01T12:00Z").toArrival(LiveFeedSource)
  val manifestForArrival: BestAvailableManifest = BestAvailableManifest(VoyageManifestGenerator.manifestForArrival(arrival, List(euPassport)))
  val manifestHistoricForArrival: ManifestPaxCount = ManifestPaxCount(VoyageManifestGenerator.manifestForArrival(arrival, List(euPassport)),
    SplitSources.Historical)

  val mockLookupService: MockManifestLookupService = MockManifestLookupService(manifestForArrival, manifestHistoricForArrival)
  val mockCacheLookup: Arrival => Future[Option[ManifestLike]] = _ => Future.successful(None)
  val mockCacheStore: (Arrival, ManifestLike) => Future[Any] = (_: Arrival, _: ManifestLike) => Future.successful(StatusReply.Ack)
  val probe: TestProbe = TestProbe("manifests")

  "Given a mock lookup returning a BestAvailableManifest" >> {
    val lookup = OptimisationProviders.historicManifestsProvider(PortCode("STN"), mockLookupService, mockCacheLookup, mockCacheStore)

    lookup(Seq(arrival)).runWith(Sink.seq).map(probe.ref ! _)

    probe.expectMsg(Iterable(manifestForArrival))

    success
  }
}
