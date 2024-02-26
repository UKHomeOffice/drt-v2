package uk.gov.homeoffice.drt.crunchsystem

import actors._
import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import controllers.{ABFeatureProviderLike, DropInProviderLike, FeatureGuideProviderLike, UserFeedBackProviderLike}
import manifests.ManifestLookupLike
import play.api.Configuration
import play.api.mvc.{Headers, Session}
import slickdb.Tables
import uk.gov.homeoffice.drt.AppEnvironment
import uk.gov.homeoffice.drt.AppEnvironment.AppEnvironment
import uk.gov.homeoffice.drt.auth.Roles
import uk.gov.homeoffice.drt.auth.Roles.Role
import uk.gov.homeoffice.drt.ports._
import uk.gov.homeoffice.drt.routes.UserRoleProviderLike
import uk.gov.homeoffice.drt.service.{ApplicationService, FeedService}
import uk.gov.homeoffice.drt.time._

import scala.concurrent.ExecutionContext

trait DrtSystemInterface extends UserRoleProviderLike
  with FeatureGuideProviderLike
  with DropInProviderLike
  with UserFeedBackProviderLike
  with ABFeatureProviderLike {
  implicit val materializer: Materializer
  implicit val ec: ExecutionContext
  implicit val system: ActorSystem
  implicit val timeout: Timeout

  val config: Configuration = new Configuration(ConfigFactory.load)
  val journalType: StreamingJournalLike = StreamingJournal.forConfig(config)
  val env: AppEnvironment = AppEnvironment(config.getOptional[String]("env").getOrElse("other"))

  val db: Tables
  val airportConfig: AirportConfig
  val params: DrtParameters

  def getRoles(config: Configuration, headers: Headers, session: Session): Set[Role] =
    if (params.isSuperUserMode) {
      system.log.debug(s"Using Super User Roles")
      Roles.availableRoles
    } else userRolesFromHeader(headers)


  val now: () => SDateLike

  val paxFeedSourceOrder: List[FeedSource] = if (params.usePassengerPredictions) List(
    ScenarioSimulationSource,
    LiveFeedSource,
    ApiFeedSource,
    MlFeedSource,
    ForecastFeedSource,
    HistoricApiFeedSource,
    AclFeedSource,
  ) else List(
    ScenarioSimulationSource,
    LiveFeedSource,
    ApiFeedSource,
    ForecastFeedSource,
    HistoricApiFeedSource,
    AclFeedSource,
  )

  val manifestLookupService: ManifestLookupLike

  val manifestLookups: ManifestLookupsLike

  val flightLookups: FlightLookupsLike

  val minuteLookups: MinuteLookupsLike

  val actorService: ActorsServiceLike

  val persistentActors: PersistentStateActors

  lazy val applicationService: ApplicationService = ApplicationService(
    journalType = journalType,
    airportConfig = airportConfig,
    now = now,
    params = params,
    config = config,
    db = db,
    feedService = feedService,
    manifestLookups = manifestLookups,
    manifestLookupService = manifestLookupService,
    minuteLookups = minuteLookups,
    actorService = actorService,
    persistentStateActors = persistentActors
  )

  lazy val feedService: FeedService = FeedService(journalType,
    airportConfig,
    now,
    params,
    config,
    paxFeedSourceOrder,
    flightLookups)

  def run(): Unit

}
