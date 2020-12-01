package actors.minutes

import actors.minutes.MinutesActorLike.{ManifestLookup, ManifestsUpdate}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.UtcDate
import passengersplits.parsing.VoyageManifestParser.{VoyageManifest, VoyageManifests}

import scala.concurrent.{ExecutionContextExecutor, Future}

case class MockManifestsLookup() {
  implicit val ec: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global

  var paramsLookup: List[(UtcDate, Option[MillisSinceEpoch])] = List()
  var paramsUpdate: List[(UtcDate, VoyageManifests)] = List()

  def lookup(mockData: VoyageManifests = VoyageManifests.empty): ManifestLookup = {

    val byDay: Map[UtcDate, VoyageManifests] = mockData.manifests.groupBy {
      case vm: VoyageManifest =>
        vm.scheduleArrivalDateTime.map(_.toUtcDate)
    }.collect {
      case (Some(date), vms) => date -> VoyageManifests(vms)
    }
    (d: UtcDate, pit: Option[MillisSinceEpoch]) => {
      paramsLookup = paramsLookup :+ (d, pit)
      Future(byDay(d))
    }

  }
  def update : ManifestsUpdate = (date: UtcDate, manifests: VoyageManifests) => {
    paramsUpdate = paramsUpdate :+ (date, manifests)
    Future(Unit)
  }
}
