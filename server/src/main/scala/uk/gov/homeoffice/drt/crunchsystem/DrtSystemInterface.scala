package uk.gov.homeoffice.drt.crunchsystem

import actors._
import com.typesafe.config.ConfigFactory
import controllers._
import manifests.ManifestLookupLike
import manifests.queues.SplitsCalculator
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.Timeout
import org.apache.pekko.{Done, NotUsed}
import play.api.Configuration
import play.api.mvc.{Headers, Session}
import queueus.{AdjustmentsNoop, ChildEGateAdjustments, QueueAdjustments, TerminalQueueAllocator}
import services.crunch.CrunchSystem.paxTypeQueueAllocator
import services.liveviews.{FlightsLiveView, QueuesLiveView}
import slickdb.AkkaDbTables
import uk.gov.homeoffice.drt.AppEnvironment
import uk.gov.homeoffice.drt.AppEnvironment.AppEnvironment
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, UniqueArrival}
import uk.gov.homeoffice.drt.auth.Roles
import uk.gov.homeoffice.drt.auth.Roles.Role
import uk.gov.homeoffice.drt.db.AggregatedDbTables
import uk.gov.homeoffice.drt.db.dao.{FlightDao, QueueSlotDao}
import uk.gov.homeoffice.drt.models.CrunchMinute
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports._
import uk.gov.homeoffice.drt.routes.UserRoleProviderLike
import uk.gov.homeoffice.drt.service.{ApplicationService, FeedService}
import uk.gov.homeoffice.drt.time._

import scala.concurrent.{ExecutionContext, Future}

trait DrtSystemInterface extends UserRoleProviderLike
  with FeatureGuideProviderLike
  with DropInProviderLike
  with UserFeedBackProviderLike
  with ABFeatureProviderLike
  with ShiftsProviderLike
  with ShiftMetaInfoProviderLike
  with ShiftStaffRollingProviderLike {
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

  private val flightDao = FlightDao()
  private val queueSlotDao = QueueSlotDao()

  lazy val flightsForPcpDateRange: (DateLike, DateLike, Seq[Terminal]) => Source[(UtcDate, Seq[ApiFlightWithSplits]), NotUsed] =
    flightDao.flightsForPcpDateRange(airportConfig.portCode, paxFeedSourceOrder, aggregatedDb.run)

  def getRoles(config: Configuration, headers: Headers, session: Session): Set[Role] = {
    if (params.isSuperUserMode) {
      Roles.availableRoles
    } else userRolesFromHeader(headers)
  }

  val now: () => SDateLike

  val manifestLookupService: ManifestLookupLike

  val manifestLookups: ManifestLookupsLike

  val flightLookups: FlightLookupsLike

  val minuteLookups: MinuteLookupsLike

  val actorService: ActorsServiceLike

  val persistentActors: PersistentStateActors

  val feedService: FeedService

  lazy val update15MinuteQueueSlotsLiveView: (UtcDate, Iterable[CrunchMinute]) => Future[Unit] = {
    val doUpdate = QueuesLiveView.updateQueuesLiveView(queueSlotDao, aggregatedDb, airportConfig.portCode)
    (date, state) => doUpdate(date, state).map(_ => ())
  }

  lazy val splitsCalculator: SplitsCalculator = {
    val queueAdjustments: QueueAdjustments =
      if (params.adjustEGateUseByUnderAge) ChildEGateAdjustments(airportConfig.assumedAdultsPerChild)
      else AdjustmentsNoop

    val paxQueueAllocator = paxTypeQueueAllocator(airportConfig.hasTransfer, TerminalQueueAllocator(airportConfig.terminalPaxTypeQueueAllocation))
    SplitsCalculator(paxQueueAllocator, airportConfig.terminalPaxSplits, queueAdjustments)
  }

  private lazy val updateAndRemoveFlights = FlightsLiveView.updateAndRemove(flightDao, aggregatedDb, airportConfig.portCode)
  lazy val updateCapacityForDate: UtcDate => Future[Done] = FlightsLiveView.updateCapacityForDate(airportConfig, aggregatedDb, feedService)

  lazy val updateFlightsLiveView: (Iterable[ApiFlightWithSplits], Iterable[UniqueArrival]) => Future[Unit] = {
    (updates, removals) =>
      updateAndRemoveFlights(updates, removals)
        .flatMap { _ =>
          val datesAffected = updates.map(f => SDate(f.apiFlight.Scheduled).toUtcDate).toSet ++ removals.map(r => SDate(r.scheduled).toUtcDate).toSet
          Future.sequence(datesAffected.map(updateCapacityForDate)).map(_ => Done)
        }
  }

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
    splitsCalculator = splitsCalculator,
  )

  def run(): Unit

}
