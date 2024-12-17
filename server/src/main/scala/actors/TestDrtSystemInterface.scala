package actors

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.stream.KillSwitch
import drt.server.feeds.FeedPoller.Enable
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration
import uk.gov.homeoffice.drt.crunchsystem.{ActorsServiceLike, PersistentStateActors}
import uk.gov.homeoffice.drt.service.{ApplicationService, FeedService}
import uk.gov.homeoffice.drt.testsystem.RestartActor
import uk.gov.homeoffice.drt.testsystem.db.AggregateDbH2
import uk.gov.homeoffice.drt.time.{MilliDate => _}

import scala.collection.SortedSet
import scala.concurrent.ExecutionContext

trait TestDrtSystemActorsLike {
  val restartActor: ActorRef
}

case class TestDrtSystemActors(applicationService: ApplicationService,
                               feedService: FeedService,
                               actorService: ActorsServiceLike,
                               persistentActors: PersistentStateActors,
                               config: Configuration)
                              (implicit system: ActorSystem, ec: ExecutionContext) extends TestDrtSystemActorsLike {
  val log: Logger = LoggerFactory.getLogger(getClass)

  val clearAggDb = () => AggregateDbH2.dropAndCreateH2Tables()

  override val restartActor: ActorRef = system.actorOf(Props(new RestartActor(startSystem, clearAggDb)), name = "TestActor-ResetData")

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
    actorService.portStateActor,
    actorService.liveShiftsReadActor,
    actorService.liveFixedPointsReadActor,
    actorService.liveStaffMovementsReadActor
  ))

  private def startSystem: () => List[KillSwitch] = () => {
    val crunchInputs = applicationService.startCrunchSystem(
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


