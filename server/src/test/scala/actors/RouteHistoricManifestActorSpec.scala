package actors

import drt.shared.ArrivalGenerator
import manifests.passengers.BestAvailableManifest
import uk.gov.homeoffice.drt.time.SDate
import services.crunch.{CrunchTestLike, VoyageManifestGenerator}
import uk.gov.homeoffice.drt.ports.{LiveFeedSource, PortCode}
import uk.gov.homeoffice.drt.ports.Terminals.T1

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class RouteHistoricManifestActorSpec extends CrunchTestLike {
  "Given a manifest" >> {
    "When I send it to the actor" >> {
      "Then I be able to ask the actor for it" >> {
        val scheduled = SDate("2022-07-12T12:00")
        val arrival = ArrivalGenerator.arrival(iata = "BA0001", terminal = T1, sch = scheduled.millisSinceEpoch).toArrival(LiveFeedSource)
        val manifest = VoyageManifestGenerator.manifestForArrival(arrival, List(VoyageManifestGenerator.visa))
        val bestAvailableManifest = BestAvailableManifest(manifest)
        val cacheLookup = RouteHistoricManifestActor.manifestCacheLookup(PortCode("LHR"), () => scheduled, system, timeout, ec)
        val cacheStore = RouteHistoricManifestActor.manifestCacheStore(PortCode("LHR"), () => scheduled, system, timeout, ec)
        val result = Await.result(cacheStore(arrival, manifest).flatMap { _ =>
          cacheLookup(arrival)
        }, 1.second)

        result === Option(bestAvailableManifest)
      }
    }
  }
}
