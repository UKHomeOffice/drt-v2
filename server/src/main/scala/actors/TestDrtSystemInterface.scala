package actors

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.stream.KillSwitch
import drt.server.feeds.FeedPoller.Enable
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration
import uk.gov.homeoffice.drt.crunchsystem.{PersistentStateActors, ActorsServiceLike}
import uk.gov.homeoffice.drt.service.{ApplicationService, FeedService}
import uk.gov.homeoffice.drt.testsystem.RestartActor
import uk.gov.homeoffice.drt.time.{MilliDate => _}
import scala.collection.SortedSet

trait TestDrtSystemActorsLike {
  val restartActor: ActorRef
}

case class TestDrtSystemActors(applicationService: ApplicationService,
                               feedService: FeedService,
                               actorService: ActorsServiceLike,
                               persistentActors: PersistentStateActors,
                               config: Configuration)
                              (implicit system: ActorSystem) extends TestDrtSystemActorsLike {
  val log: Logger = LoggerFactory.getLogger(getClass)
  override val restartActor: ActorRef = system.actorOf(Props(new RestartActor(startSystem)), name = "TestActor-ResetData")

  restartActor ! RestartActor.AddResetActors(Seq(
    persistentActors.forecastBaseArrivalsActor,
    persistentActors.forecastArrivalsActor,
    persistentActors.liveArrivalsActor,
    persistentActors.liveBaseArrivalsActor,
    persistentActors.manifestsRouterActor,
    persistentActors.crunchQueueActor,
    persistentActors.deskRecsQueueActor,
    persistentActors.deploymentQueueActor,
    persistentActors.staffingQueueActor,
    persistentActors.aggregatedArrivalsActor,
    actorService.portStateActor,
    actorService.liveShiftsReadActor,
    actorService.liveFixedPointsReadActor,
    actorService.liveStaffMovementsReadActor
  ))

  private def startSystem: () => List[KillSwitch] = () => {
    val crunchInputs = applicationService.startCrunchSystem(
      actors = applicationService.persistentStateActors,
      initialPortState = None,
      initialForecastBaseArrivals = None,
      initialForecastArrivals = None,
      initialLiveBaseArrivals = None,
      initialLiveArrivals = None,
      refreshArrivalsOnStart = false,
      startUpdateGraphs = applicationService.startUpdateGraphs(applicationService.persistentStateActors, SortedSet(), SortedSet(), SortedSet(), SortedSet())
    )

    feedService.liveActor ! Enable(crunchInputs.liveArrivalsResponse)

    applicationService.setSubscribers(crunchInputs, applicationService.persistentStateActors.manifestsRouterActor)

    crunchInputs.killSwitches
  }
}


