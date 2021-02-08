package manifests

import akka.stream.scaladsl.Sink
import akka.testkit.TestProbe
import controllers.ArrivalGenerator
import drt.shared.PortCode
import drt.shared.api.Arrival
import manifests.graph.MockManifestLookupService
import manifests.passengers.BestAvailableManifest
import services.crunch.VoyageManifestGenerator.euPassport
import services.crunch.deskrecs.OptimisationProviders
import services.crunch.{CrunchTestLike, VoyageManifestGenerator}

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
