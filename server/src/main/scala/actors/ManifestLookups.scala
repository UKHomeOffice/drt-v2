package actors

import actors.daily.{DayManifestActor, RequestAndTerminate, RequestAndTerminateActor}
import actors.minutes.MinutesActorLike.{ManifestLookup, ManifestsUpdate}
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.UtcDate
import passengersplits.parsing.VoyageManifestParser.VoyageManifests

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.language.postfixOps

trait ManifestLookupsLike {
  val system: ActorSystem
  implicit val ec: ExecutionContext
  implicit val timeout: Timeout = new Timeout(60 seconds)

  val requestAndTerminateActor: ActorRef

  val updateManifests: ManifestsUpdate = (date: UtcDate, vms: VoyageManifests) => {
    val actor = system.actorOf(DayManifestActor.props(date))
    system.log.info(s"About to update $date with ${vms.manifests.size} manifests")
    requestAndTerminateActor.ask(RequestAndTerminate(actor, vms)).mapTo[Seq[MillisSinceEpoch]]
  }

  val manifestsByDayLookup: ManifestLookup = (date: UtcDate, maybePit: Option[MillisSinceEpoch]) => {
    val props = maybePit match {
      case None => DayManifestActor.props(date)
      case Some(pointInTime) => DayManifestActor.propsPointInTime(date, pointInTime)
    }
    val actor = system.actorOf(props)
    requestAndTerminateActor.ask(RequestAndTerminate(actor, GetState)).mapTo[VoyageManifests]
  }

}

case class ManifestLookups(system: ActorSystem)(implicit val ec: ExecutionContext) extends ManifestLookupsLike {
  override val requestAndTerminateActor: ActorRef = system
    .actorOf(Props(new RequestAndTerminateActor()), "manifests-lookup-kill-actor")
}
