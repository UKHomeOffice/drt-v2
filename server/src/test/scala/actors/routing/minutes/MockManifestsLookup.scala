package actors.routing.minutes

import actors.routing.minutes.MinutesActorLike.{ManifestLookup, ManifestsUpdate}
import drt.shared.CrunchApi.MillisSinceEpoch
import org.apache.pekko.actor.ActorRef
import uk.gov.homeoffice.drt.actor.commands.TerminalUpdateRequest
import uk.gov.homeoffice.drt.models.{VoyageManifest, VoyageManifests}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, UtcDate}

import scala.concurrent.{ExecutionContextExecutor, Future}

case class MockManifestsLookup(probe: ActorRef, terminals: LocalDate => Iterable[Terminal]) {
  implicit val ec: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global

  def lookup(mockData: VoyageManifests = VoyageManifests.empty): ManifestLookup = {

    val byDay: Map[UtcDate, VoyageManifests] = mockData.manifests
      .groupBy { vm: VoyageManifest => vm.scheduleArrivalDateTime.map(_.toUtcDate) }
      .collect { case (Some(date), vms) => date -> VoyageManifests(vms) }

    (d: UtcDate, _: Option[MillisSinceEpoch]) => Future(byDay(d))
  }

  def update: ManifestsUpdate = (date: UtcDate, manifests: VoyageManifests) => {
    probe ! (date, manifests)
    Future(manifests.manifests.map(_.scheduled.toLocalDate).flatMap(d => terminals(SDate(date).toLocalDate).map(TerminalUpdateRequest(_, d))).toSet)
  }
}
