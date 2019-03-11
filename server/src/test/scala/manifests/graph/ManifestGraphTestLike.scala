package manifests.graph

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.testkit.TestKit
import drt.shared.SDateLike
import manifests.passengers.BestAvailableManifest
import org.specs2.mutable.SpecificationLike
import passengersplits.InMemoryPersistence
import services.ManifestLookupLike
import scala.concurrent.ExecutionContext.Implicits.global

import scala.concurrent.Future
import scala.util.{Success, Try}

case class MockManifestLookupService(bestAvailableManifest: BestAvailableManifest) extends ManifestLookupLike {
  override def tryBestAvailableManifest(
                                         arrivalPort: String, departurePort: String,
                                         voyageNumber: String, scheduled: SDateLike):
  Future[Try[BestAvailableManifest]] = Future(Success(bestAvailableManifest))
}

class ManifestGraphTestLike extends TestKit(ActorSystem("ManifestTests", InMemoryPersistence.akkaAndAggregateDbConfig))
      with SpecificationLike {
    isolated
    sequential

  implicit val actorSystem: ActorSystem = system
  implicit val materializer: ActorMaterializer = ActorMaterializer()

}
