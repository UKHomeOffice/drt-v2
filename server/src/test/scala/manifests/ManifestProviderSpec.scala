package manifests

import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import akka.testkit.TestProbe
import controllers.ArrivalGenerator
import manifests.passengers.{BestAvailableManifest, ManifestPaxCount}
import services.crunch.VoyageManifestGenerator.euPassport
import services.crunch.deskrecs.DynamicRunnableDeskRecs.HistoricManifestsProvider
import services.crunch.deskrecs.OptimisationProviders
import services.crunch.{CrunchTestLike, VoyageManifestGenerator}
import uk.gov.homeoffice.drt.arrivals.{Arrival, VoyageNumber}
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources
import uk.gov.homeoffice.drt.time.SDateLike

import scala.concurrent.Future

case class MockManifestLookupService(bestAvailableManifest: BestAvailableManifest, historicManifestPax: ManifestPaxCount)
                                    (implicit mat: Materializer) extends ManifestLookupLike {
  override def maybeBestAvailableManifest(arrivalPort: PortCode,
                                          departurePort: PortCode,
                                          voyageNumber: VoyageNumber,
                                          scheduled: SDateLike): Future[(UniqueArrivalKey, Option[BestAvailableManifest])] =
    Future.successful((UniqueArrivalKey(arrivalPort, departurePort, voyageNumber, scheduled), Option(bestAvailableManifest)))

  override def historicManifestPax(arrivalPort: PortCode, departurePort: PortCode, voyageNumber: VoyageNumber, scheduled: SDateLike): Future[(UniqueArrivalKey, Option[ManifestPaxCount])] =
    Future.successful((UniqueArrivalKey(arrivalPort, departurePort, voyageNumber, scheduled), Option(historicManifestPax)))

}

class ManifestProviderSpec extends CrunchTestLike {
  val arrival: Arrival = ArrivalGenerator.arrival(iata = "BA0001", schDt = "2021-06-01T12:00Z")
  val manifestForArrival: BestAvailableManifest = BestAvailableManifest(VoyageManifestGenerator.manifestForArrival(arrival, List(euPassport)))
  val manifestHistoricForArrival: ManifestPaxCount = ManifestPaxCount(VoyageManifestGenerator.manifestForArrival(arrival, List(euPassport)),
    SplitSources.Historical)

  val mockLookupService: MockManifestLookupService = MockManifestLookupService(manifestForArrival, manifestHistoricForArrival)
  val probe: TestProbe = TestProbe("manifests")

  "Given a mock lookup returning a BestAvailableManifest" >> {
    val lookup: HistoricManifestsProvider = OptimisationProviders.historicManifestsProvider(PortCode("STN"), mockLookupService)

    lookup(Seq(arrival)).runWith(Sink.seq).map(probe.ref ! _)

    probe.expectMsg(Iterable(manifestForArrival))

    success
  }
}
