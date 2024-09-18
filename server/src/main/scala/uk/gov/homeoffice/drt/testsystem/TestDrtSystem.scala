
package uk.gov.homeoffice.drt.testsystem

import actors._
import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.util.Timeout
import com.google.inject.Inject
import manifests.ManifestLookupLike
import play.api.Configuration
import play.api.mvc.{Headers, Session}
import slickdb._
import uk.gov.homeoffice.drt.auth.Roles.Role
import uk.gov.homeoffice.drt.crunchsystem.{ActorsServiceLike, DrtSystemInterface}
import uk.gov.homeoffice.drt.db.dao.{IABFeatureDao, IUserFeedbackDao}
import uk.gov.homeoffice.drt.ports.AirportConfig
import uk.gov.homeoffice.drt.service.FeedService
import uk.gov.homeoffice.drt.testsystem.RestartActor.StartTestSystem
import uk.gov.homeoffice.drt.testsystem.crunchsystem.TestPersistentStateActors
import uk.gov.homeoffice.drt.testsystem.db.{AggregateDbH2, AkkaDbH2}
import uk.gov.homeoffice.drt.time.{MilliTimes, SDateLike}

import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.language.postfixOps

@Singleton
case class TestDrtSystem @Inject()(airportConfig: AirportConfig,
                                   params: DrtParameters,
                                   now: () => SDateLike)
                                  (implicit val materializer: Materializer,
                                   val ec: ExecutionContext,
                                   val system: ActorSystem,
                                   val timeout: Timeout) extends DrtSystemInterface {

  log.warn("Using test System")

  lazy override val aggregatedDb: AggregatedDbTables = AggregateDbH2
  lazy override val akkaDb: AkkaDbTables = AkkaDbH2

  override def getRoles(config: Configuration,
                        headers: Headers,
                        session: Session): Set[Role] = TestUserRoleProvider.getRoles(config, headers, session)


  override val userService: UserTableLike = MockUserTable()
  override val featureGuideService: FeatureGuideTableLike = MockFeatureGuideTable()
  override val featureGuideViewService: FeatureGuideViewLike = MockFeatureGuideViewTable()
  override val dropInService: DropInTableLike = MockDropInTable()
  override val dropInRegistrationService: DropInsRegistrationTableLike = MockDropInsRegistrationTable()
  override val userFeedbackService: IUserFeedbackDao = MockUserFeedbackDao()
  override val abFeatureService: IABFeatureDao = MockAbFeatureDao()

  override val minuteLookups: MinuteLookupsLike = TestMinuteLookups(system, now, MilliTimes.oneDayMillis, airportConfig.queuesByTerminal)
  override val flightLookups: FlightLookupsLike = TestFlightLookups(
    system,
    now,
    airportConfig.queuesByTerminal,
    paxFeedSourceOrder,
    splitsCalculator.terminalSplits,
  )
  override val manifestLookupService: ManifestLookupLike = MockManifestLookupService()
  override val manifestLookups: ManifestLookupsLike = ManifestLookups(system, airportConfig.terminals)
  lazy override val actorService: ActorsServiceLike = TestActorService(journalType,
    airportConfig,
    now,
    params.forecastMaxDays,
    flightLookups,
    minuteLookups)

  val persistentActors: TestPersistentStateActors = TestPersistentStateActors(
    system,
    now,
    airportConfig.minutesToCrunch,
    airportConfig.crunchOffsetMinutes,
    manifestLookups,
    airportConfig.terminals,
  )

  lazy val feedService: FeedService = TestFeedService(
    journalType = journalType,
    airportConfig = airportConfig,
    now = now,
    params = params,
    config = config,
    paxFeedSourceOrder = paxFeedSourceOrder,
    flightLookups = flightLookups,
    manifestLookups = manifestLookups,
    requestAndTerminateActor = actorService.requestAndTerminateActor,
    forecastMaxDays = params.forecastMaxDays,
  )

  val testDrtSystemActor: TestDrtSystemActorsLike = TestDrtSystemActors(applicationService, feedService, actorService, persistentActors, config)

  override def run(): Unit = {
    testDrtSystemActor.restartActor ! StartTestSystem
  }
}
