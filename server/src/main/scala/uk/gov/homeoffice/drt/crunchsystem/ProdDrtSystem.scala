package uk.gov.homeoffice.drt.crunchsystem

import actors._
import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.util.Timeout
import com.google.inject.Inject
import drt.shared.CrunchApi.MillisSinceEpoch
import manifests.{ManifestLookup, ManifestLookupLike}
import slickdb._
import uk.gov.homeoffice.drt.db._
import uk.gov.homeoffice.drt.ports.AirportConfig
import uk.gov.homeoffice.drt.service.{ActorsService, ApplicationService}
import uk.gov.homeoffice.drt.time.{MilliTimes, SDateLike}

import javax.inject.Singleton
import scala.concurrent.ExecutionContext

@Singleton
case class ProdDrtSystem @Inject()(airportConfig: AirportConfig, params: DrtParameters, now: () => SDateLike)
                                  (implicit val materializer: Materializer,
                                   val ec: ExecutionContext,
                                   val system: ActorSystem,
                                   val timeout: Timeout) extends DrtSystemInterface {
  val forecastMaxMillis: () => MillisSinceEpoch = () => now().addDays(params.forecastMaxDays).millisSinceEpoch

  override val minuteLookups: MinuteLookupsLike = MinuteLookups(now, MilliTimes.oneDayMillis, airportConfig.queuesByTerminal)

  override val flightLookups: FlightLookupsLike = FlightLookups(
    system,
    now,
    airportConfig.queuesByTerminal,
    params.maybeRemovalCutOffSeconds,
    paxFeedSourceOrder)

  override val manifestLookupService: ManifestLookupLike = ManifestLookup(AggregateDb)

  override val manifestLookupsService = ManifestLookups(system)

  override val userService: UserTableLike = UserTable(AggregateDb)

  override val featureGuideService: FeatureGuideTableLike = FeatureGuideTable(AggregateDb)

  override val featureGuideViewService: FeatureGuideViewLike = FeatureGuideViewTable(AggregateDb)

  override val dropInService: DropInTableLike = DropInTable(AggregateDb)

  override val dropInRegistrationService: DropInsRegistrationTableLike = DropInsRegistrationTable(AggregateDb)

   lazy override val db: Tables = AggregateDb

  override val userFeedbackService: IUserFeedbackDao = UserFeedbackDao(AggregateDb.db)

  override val abFeatureService: IABFeatureDao = ABFeatureDao(AggregateDb.db)

  lazy override val actorService: ReadRouteUpdateActorsLike = ActorsService(journalType = StreamingJournal.forConfig(config),
    airportConfig = airportConfig,
    now = now,
    params = params,
    flightLookups = flightLookups,
    minuteLookups = minuteLookups)

  val persistentActors = ProdPersistentStateActors(
    system,
    now,
    airportConfig.minutesToCrunch,
    airportConfig.crunchOffsetMinutes,
    manifestLookupsService,
    airportConfig.portCode,
    feedService.paxFeedSourceOrder)

   val applicationService: ApplicationService = uk.gov.homeoffice.drt.service.ApplicationService(
    journalType = journalType,
    airportConfig = airportConfig,
    now = now,
    params = params,
    config = config,
    db = db,
    feedService = feedService,
    manifestLookups = manifestLookupsService,
    manifestLookupService = manifestLookupService,
    minuteLookups = minuteLookups,
    readActorService = actorService,
    persistentStateActors = persistentActors
  )
  override def run(): Unit = {
    applicationService.run()
  }

}
