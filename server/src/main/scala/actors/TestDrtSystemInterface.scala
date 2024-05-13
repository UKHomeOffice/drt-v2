package actors

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.stream.KillSwitch
import drt.server.feeds.FeedPoller.Enable
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration
import uk.gov.homeoffice.drt.crunchsystem.{ActorsServiceLike, PersistentStateActors}
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
    feedService.forecastBaseFeedArrivalsActor,
    feedService.forecastFeedArrivalsActor,
    feedService.liveFeedArrivalsActor,
    feedService.liveBaseFeedArrivalsActor,
    persistentActors.manifestsRouterActor,
    persistentActors.mergeArrivalsQueueActor,
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
      startUpdateGraphs = applicationService.startUpdateGraphs(
        applicationService.persistentStateActors,
        SortedSet(),
        SortedSet(),
        SortedSet(),
        SortedSet(),
        SortedSet(),
      )
    )

    feedService.liveFeedPollingActor ! Enable(crunchInputs.liveArrivalsResponse)

    crunchInputs.killSwitches
  }
}


