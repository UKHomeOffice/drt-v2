package manifests

import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import akka.testkit.TestProbe
import controllers.ArrivalGenerator
import manifests.passengers.BestAvailableManifest
import services.crunch.VoyageManifestGenerator.euPassport
import services.crunch.deskrecs.OptimisationProviders
import services.crunch.{CrunchTestLike, VoyageManifestGenerator}
import uk.gov.homeoffice.drt.arrivals.{Arrival, VoyageNumber}
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.time.SDateLike

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

case class MockManifestLookupService(bestAvailableManifest: BestAvailableManifest) extends ManifestLookupLike {
  override def maybeBestAvailableManifest(arrivalPort: PortCode,
                                          departurePort: PortCode,
                                          voyageNumber: VoyageNumber,
                                          scheduled: SDateLike)
                                         (implicit mat: Materializer): Future[(UniqueArrivalKey, Option[BestAvailableManifest])] =
    Future((UniqueArrivalKey(arrivalPort, departurePort, voyageNumber, scheduled), Option(bestAvailableManifest)))
}

class ManifestProviderSpec extends CrunchTestLike {
  val arrival: Arrival = ArrivalGenerator.arrival(iata = "BA0001", schDt = "2021-06-01T12:00Z")
  val manifestForArrival: BestAvailableManifest = BestAvailableManifest(VoyageManifestGenerator.manifestForArrival(arrival, List(euPassport)))
  val mockLookupService: MockManifestLookupService = MockManifestLookupService(manifestForArrival)
  val probe: TestProbe = TestProbe("manifests")

  "Given a mock lookup returning a BestAvailableManifest" >> {
    val lookup = OptimisationProviders.historicManifestsProvider(PortCode("STN"), mockLookupService)

    lookup(Seq(arrival)).map(_.runWith(Sink.seq)).flatten.map(probe.ref ! _)

    probe.expectMsg(Iterable(manifestForArrival))

    success
  }
}
