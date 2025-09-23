package uk.gov.homeoffice.drt.crunchsystem

import actors._
import com.google.inject.Inject
import manifests.{ManifestLookup, ManifestLookupLike}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.apache.pekko.util.Timeout
import slickdb._
import uk.gov.homeoffice.drt.db._
import uk.gov.homeoffice.drt.db.dao._
import uk.gov.homeoffice.drt.db.tables.{UserTable, UserTableLike}
import uk.gov.homeoffice.drt.ports.AirportConfig
import uk.gov.homeoffice.drt.service.staffing.{ShiftMetaInfoService, ShiftMetaInfoServiceImpl, ShiftsService, ShiftsServiceImpl}
import uk.gov.homeoffice.drt.service.{ActorsServiceService, FeedService, ProdFeedService, QueueConfig}
import uk.gov.homeoffice.drt.time.{MilliTimes, SDateLike}

import javax.inject.Singleton
import scala.concurrent.ExecutionContext

@Singleton
case class ProdDrtSystem @Inject()(airportConfig: AirportConfig, params: DrtParameters, now: () => SDateLike)
                                  (implicit val materializer: Materializer,
                                   val ec: ExecutionContext,
                                   val system: ActorSystem,
                                   val timeout: Timeout) extends DrtSystemInterface {

  lazy override val aggregatedDb: AggregatedDbTables = AggregatedDbTables(config.get[String]("database-type"))

  lazy override val akkaDb: AkkaDbTables = AkkaDb

  override val minuteLookups: MinuteLookupsLike = MinuteLookups(
    now = now,
    expireAfterMillis = MilliTimes.oneDayMillis,
    queuesForDateAndTerminal = QueueConfig.queuesForDateAndTerminal(airportConfig.queuesByTerminal),
    updateLiveView = update15MinuteQueueSlotsLiveView,
    terminalsForDateRange = QueueConfig.terminalsForDateRange(airportConfig.queuesByTerminal)
  )

  override val flightLookups: FlightLookupsLike = FlightLookups(
    system = system,
    now = now,
    terminalsForDateRange = QueueConfig.terminalsForDateRange(airportConfig.queuesByTerminal),
    removalMessageCutOff = params.maybeRemovalCutOffSeconds,
    paxFeedSourceOrder = paxFeedSourceOrder,
    terminalSplits = splitsCalculator.terminalSplits,
    updateLiveView = updateFlightsLiveView,
  )

  override val manifestLookupService: ManifestLookupLike = ManifestLookup(aggregatedDb)

  override val manifestLookups: ManifestLookups = ManifestLookups(system, airportConfig.terminalsForDate)

  override val userService: UserTableLike = UserTable(aggregatedDb)

  override val featureGuideService: FeatureGuideTableLike = FeatureGuideTable(aggregatedDb)

  override val featureGuideViewService: FeatureGuideViewLike = FeatureGuideViewTable(aggregatedDb)

  override val dropInService: DropInTableLike = DropInTable(aggregatedDb)

  override val dropInRegistrationService: DropInsRegistrationTableLike = DropInsRegistrationTable(aggregatedDb)

  override val userFeedbackService: IUserFeedbackDao = UserFeedbackDao(aggregatedDb)

  override val abFeatureService: IABFeatureDao = ABFeatureDao(aggregatedDb)

  override val shiftsService: ShiftsService = ShiftsServiceImpl(StaffShiftsDao(aggregatedDb))

  override val shiftMetaInfoService: ShiftMetaInfoService = ShiftMetaInfoServiceImpl(ShiftMetaInfoDao(aggregatedDb))

  lazy override val actorService: ActorsServiceLike = ActorsServiceService(
    journalType = StreamingJournal.forConfig(config),
    airportConfig = airportConfig,
    now = now,
    forecastMaxDays = params.forecastMaxDays,
    flightLookups = flightLookups,
    minuteLookups = minuteLookups,
  )

  lazy val feedService: FeedService = ProdFeedService(
    journalType = journalType,
    airportConfig = airportConfig,
    now = now,
    params = params,
    config = config,
    paxFeedSourceOrder = paxFeedSourceOrder,
    flightLookups = flightLookups,
    manifestLookups = manifestLookups,
    requestAndTerminateActor = actorService.requestAndTerminateActor,
    params.forecastMaxDays,
    params.legacyFeedArrivalsBeforeDate,
  )


  lazy val persistentActors: PersistentStateActors = ProdPersistentStateActors(
    system,
    now,
    manifestLookups,
    airportConfig.terminalsForDate,
  )

  override def run(): Unit = applicationService.run()

}
