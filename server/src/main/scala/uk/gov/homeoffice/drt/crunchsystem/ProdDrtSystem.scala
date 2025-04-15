package uk.gov.homeoffice.drt.crunchsystem

import actors._
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpMethods, HttpRequest}
import akka.stream.Materializer
import akka.util.Timeout
import com.google.inject.Inject
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import manifests.{ManifestLookup, ManifestLookupLike}
import slickdb._
import uk.gov.homeoffice.drt.db._
import uk.gov.homeoffice.drt.db.dao._
import uk.gov.homeoffice.drt.ports.AirportConfig
import uk.gov.homeoffice.drt.service.staffing.{ShiftsService, ShiftsServiceImpl}
import uk.gov.homeoffice.drt.service.{ActorsServiceService, FeedService, ProdFeedService}
import uk.gov.homeoffice.drt.time.{MilliTimes, SDateLike}

import javax.inject.Singleton
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.CollectionHasAsScala

@Singleton
case class ProdDrtSystem @Inject()(airportConfig: AirportConfig, params: DrtParameters, now: () => SDateLike)
                                  (implicit val materializer: Materializer,
                                   val ec: ExecutionContext,
                                   val system: ActorSystem,
                                   val timeout: Timeout) extends DrtSystemInterface {

  lazy override val aggregatedDb: AggregatedDbTables = AggregateDb

  lazy override val akkaDb: AkkaDbTables = AkkaDb

  override val minuteLookups: MinuteLookupsLike = MinuteLookups(
    now,
    MilliTimes.oneDayMillis,
    airportConfig.queuesByTerminal,
    update15MinuteQueueSlotsLiveView
  )

  override val flightLookups: FlightLookupsLike = FlightLookups(
    system,
    now,
    airportConfig.queuesByTerminal,
    params.maybeRemovalCutOffSeconds,
    paxFeedSourceOrder,
    splitsCalculator.terminalSplits,
    updateFlightsLiveView,
  )

  override val manifestLookupService: ManifestLookupLike = ManifestLookup(AggregateDb)

  override val manifestLookups: ManifestLookups = ManifestLookups(system, airportConfig.terminals)

  override val userService: UserTableLike = UserTable(AggregateDb)

  override val featureGuideService: FeatureGuideTableLike = FeatureGuideTable(AggregateDb)

  override val featureGuideViewService: FeatureGuideViewLike = FeatureGuideViewTable(AggregateDb)

  override val dropInService: DropInTableLike = DropInTable(AggregateDb)

  override val dropInRegistrationService: DropInsRegistrationTableLike = DropInsRegistrationTable(AggregateDb)

  override val userFeedbackService: IUserFeedbackDao = UserFeedbackDao(AggregateDb)

  override val abFeatureService: IABFeatureDao = ABFeatureDao(AggregateDb)

  override val shiftsService: ShiftsService = ShiftsServiceImpl(StaffShiftsDao(AggregateDb))

  override var isReady = false

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
    airportConfig.terminals,
  )

  override def run(): Unit = {
    def tryLock(): Unit =
      akkaDb
        .run(akkaDb.tryAcquireAdvisoryLock(123456))
        .map {
          case true =>
            log.info("Acquired lock on database")
            isReady = true
            applicationService.run()

          case false =>
            log.info("Failed to acquire lock on database. Requesting lock release & retrying...")

            val client = new KubernetesClientBuilder().build()
            val namespace = client.getNamespace
            val myPodName = sys.env("MY_POD_NAME")

            val pods = client.pods()
              .inNamespace(namespace)
              .withLabel("name", airportConfig.portCode.iata.toLowerCase)
              .list()
              .getItems
              .asScala
              .filterNot(_.getMetadata.getName == myPodName)

            Future.sequence(pods.map(requestLockRelease))
              .onComplete { _ =>
                log.info("Requested lock release from other pods")
                system.scheduler.scheduleOnce(500.millis)(tryLock())
              }
        }

    tryLock()
  }

  private def requestLockRelease(pod: Pod) = {
    val url = s"http://${pod.getStatus.getPodIP}:8080/release-lock"
    log.info(s"Requesting lock release from pod ${pod.getMetadata.getName} at $url")

    Http().singleRequest(HttpRequest(method = HttpMethods.POST, uri = url))
  }

  override def releaseLock(): Future[Boolean] = {
    akkaDb
      .run(akkaDb.releaseAdvisoryLock(123456))
      .map {
        case true =>
          log.info("Released lock on database")
          isReady = false
          true
        case false =>
          log.info("Failed to release lock on database")
          false
      }
  }
}
