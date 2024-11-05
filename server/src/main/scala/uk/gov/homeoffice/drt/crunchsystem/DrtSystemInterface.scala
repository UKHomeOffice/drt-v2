package uk.gov.homeoffice.drt.crunchsystem

import actors._
import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import controllers.{ABFeatureProviderLike, DropInProviderLike, FeatureGuideProviderLike, UserFeedBackProviderLike}
import drt.shared.CrunchApi.CrunchMinutes
import manifests.ManifestLookupLike
import manifests.queues.SplitsCalculator
import play.api.Configuration
import play.api.mvc.{Headers, Session}
import queueus.{AdjustmentsNoop, ChildEGateAdjustments, QueueAdjustments}
import slickdb.{AggregatedDbTables, AkkaDbTables}
import uk.gov.homeoffice.drt.AppEnvironment
import uk.gov.homeoffice.drt.AppEnvironment.AppEnvironment
import uk.gov.homeoffice.drt.arrivals.ApiFlightWithSplits
import uk.gov.homeoffice.drt.auth.Roles
import uk.gov.homeoffice.drt.auth.Roles.Role
import uk.gov.homeoffice.drt.db.dao.{FlightDao, QueueSlotDao}
import uk.gov.homeoffice.drt.model.CrunchMinute
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
  implicit val airportConfig: AirportConfig

  val config: Configuration = new Configuration(ConfigFactory.load)
  val journalType: StreamingJournalLike = StreamingJournal.forConfig(config)
  val env: AppEnvironment = AppEnvironment(config.getOptional[String]("env").getOrElse("other"))

  val aggregatedDb: AggregatedDbTables
  val akkaDb: AkkaDbTables
  val params: DrtParameters

  private val flightDao = FlightDao(airportConfig.portCode)

  private val queueSlotDao = QueueSlotDao(airportConfig.portCode)

  val updateFlightsLiveView: Iterable[ApiFlightWithSplits] => Unit =
    flights => aggregatedDb.run(flightDao.insertOrUpdate(flights))
  val update15MinuteQueueSlotsLiveView: (UtcDate, Iterable[CrunchMinute]) => Unit =
    (date, crunchMinutes) => {
      val slotSizeMinutes = 15
      val summaries = CrunchMinutes.groupByMinutes(slotSizeMinutes, crunchMinutes.toSeq, date)(d => SDate(d).millisSinceEpoch)
      aggregatedDb.run(queueSlotDao.insertOrUpdate(summaries, slotSizeMinutes))
    }

  def getRoles(config: Configuration, headers: Headers, session: Session): Set[Role] = {
    if (params.isSuperUserMode) {
      system.log.debug(s"Using Super User Roles")
      Roles.availableRoles
    } else userRolesFromHeader(headers)
  }


  val now: () => SDateLike

  implicit val paxFeedSourceOrder: List[FeedSource] = if (params.usePassengerPredictions) List(
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

  val feedService: FeedService

  lazy val queueAdjustments: QueueAdjustments =
    if (params.adjustEGateUseByUnder12s) ChildEGateAdjustments(airportConfig.assumedAdultsPerChild) else AdjustmentsNoop
  lazy val splitsCalculator: SplitsCalculator = SplitsCalculator(airportConfig, queueAdjustments)

  lazy val applicationService: ApplicationService = ApplicationService(
    journalType = journalType,
    now = now,
    params = params,
    config = config,
    aggregatedDb = aggregatedDb,
    akkaDb = akkaDb,
    feedService = feedService,
    manifestLookups = manifestLookups,
    manifestLookupService = manifestLookupService,
    minuteLookups = minuteLookups,
    actorService = actorService,
    persistentStateActors = persistentActors,
    requestAndTerminateActor = actorService.requestAndTerminateActor,
    splitsCalculator,
  )
  def run(): Unit

}
