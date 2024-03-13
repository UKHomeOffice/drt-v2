package actors.routing.minutes

import actors.routing.minutes.MinutesActorLike.{ManifestLookup, ManifestsUpdate}
import actors.persistent.QueueLikeActor.UpdatedMillis
import akka.actor.ActorRef
import drt.shared.CrunchApi.MillisSinceEpoch
import uk.gov.homeoffice.drt.time.UtcDate
import passengersplits.parsing.VoyageManifestParser.{VoyageManifest, VoyageManifests}

import scala.concurrent.{ExecutionContextExecutor, Future}

case class MockManifestsLookup(probe: ActorRef) {
  implicit val ec: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global

  def lookup(mockData: VoyageManifests = VoyageManifests.empty): ManifestLookup = {

    val byDay: Map[UtcDate, VoyageManifests] = mockData.manifests
      .groupBy { vm: VoyageManifest => vm.scheduleArrivalDateTime.map(_.toUtcDate) }
      .collect { case (Some(date), vms) => date -> VoyageManifests(vms) }

    (d: UtcDate, pit: Option[MillisSinceEpoch]) => {
      Future(byDay(d))
    }

  }

  def update: ManifestsUpdate = (date: UtcDate, manifests: VoyageManifests) => {
    probe ! (date, manifests)
    Future(UpdatedMillis(manifests.manifests.map(_.scheduled.millisSinceEpoch).toSet))
  }
}
