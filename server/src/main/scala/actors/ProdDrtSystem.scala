package actors

import actors.PartitionedPortStateActor.{flightUpdatesProps, queueUpdatesProps, staffUpdatesProps}
import actors.daily.{FlightUpdatesSupervisor, QueueUpdatesSupervisor, StaffUpdatesSupervisor}
import actors.persistent.RedListUpdatesActor.AddSubscriber
import actors.persistent.arrivals.{AclForecastArrivalsActor, ArrivalsState, PortForecastArrivalsActor, PortLiveArrivalsActor}
import actors.persistent.staffing.{FixedPointsActor, GetFeedStatuses, ShiftsActor, StaffMovementsActor}
import actors.persistent.{ApiFeedState, ManifestRouterActor}
import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem, Props, typed}
import akka.pattern.ask
import akka.stream.scaladsl.{Source, SourceQueueWithComplete}
import akka.stream.{Materializer, OverflowStrategy}
import akka.util.Timeout
import drt.server.feeds.Feed
import drt.server.feeds.FeedPoller.{AdhocCheck, Enable}
import drt.server.feeds.api.S3ApiProvider
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared._
import drt.shared.api.Arrival
import drt.shared.coachTime.CoachWalkTime
import manifests.ManifestLookup
import manifests.passengers.S3ManifestPoller
import play.api.Configuration
import play.api.mvc.{Headers, Session}
import server.feeds.ManifestsFeedResponse
import services.SDate
import services.crunch.CrunchSystem
import services.crunch.deskrecs.RunnableOptimisation.CrunchRequest
import slickdb.{ArrivalTable, Tables, VoyageManifestPassengerInfoTable}
import uk.gov.homeoffice.drt.auth.Roles
import uk.gov.homeoffice.drt.auth.Roles.Role
import uk.gov.homeoffice.drt.ports.AirportConfig

import scala.collection.immutable.SortedMap
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}


object PostgresTables extends {
  val profile = slick.jdbc.PostgresProfile
} with Tables

case class SubscribeResponseQueue(subscriber: SourceQueueWithComplete[ManifestsFeedResponse])

case class ProdDrtSystem(airportConfig: AirportConfig)
                        (implicit val materializer: Materializer,
                         val ec: ExecutionContext,
                         val system: ActorSystem,
                         val timeout: Timeout) extends DrtSystemInterface {

  import DrtStaticParameters._

  val maxBufferSize: Int = config.get[Int]("crunch.manifests.max-buffer-size")
  val minSecondsBetweenBatches: Int = config.get[Int]("crunch.manifests.min-seconds-between-batches")
  val refetchApiData: Boolean = config.get[Boolean]("crunch.manifests.refetch-live-api")

  val aggregateArrivalsDbConfigKey = "aggregated-db"

  val forecastMaxMillis: () => MillisSinceEpoch = () => now().addDays(params.forecastMaxDays).millisSinceEpoch

  override val forecastBaseArrivalsActor: ActorRef = restartOnStop.actorOf(Props(new AclForecastArrivalsActor(params.snapshotMegaBytesBaseArrivals, now, expireAfterMillis)), name = "base-arrivals-actor")
  override val forecastArrivalsActor: ActorRef = restartOnStop.actorOf(Props(new PortForecastArrivalsActor(params.snapshotMegaBytesFcstArrivals, now, expireAfterMillis)), name = "forecast-arrivals-actor")
  override val liveArrivalsActor: ActorRef = restartOnStop.actorOf(Props(new PortLiveArrivalsActor(params.snapshotMegaBytesLiveArrivals, now, expireAfterMillis)), name = "live-arrivals-actor")

  val manifestLookups: ManifestLookups = ManifestLookups(system)

  override val aggregatedArrivalsActor: ActorRef = system.actorOf(Props(new AggregatedArrivalsActor(ArrivalTable(airportConfig.portCode, PostgresTables))), name = "aggregated-arrivals-actor")

  override val manifestsRouterActor: ActorRef = restartOnStop.actorOf(Props(new ManifestRouterActor(manifestLookups.manifestsByDayLookup, manifestLookups.updateManifests)), name = "voyage-manifests-router-actor")

  override val manifestLookupService: ManifestLookup = ManifestLookup(VoyageManifestPassengerInfoTable(PostgresTables))

  override val minuteLookups: MinuteLookups = MinuteLookups(system, now, MilliTimes.oneDayMillis, airportConfig.queuesByTerminal)

  val flightLookups: FlightLookups = FlightLookups(
    system,
    now,
    airportConfig.queuesByTerminal,
    params.maybeRemovalCutOffSeconds
  )
  override val flightsActor: ActorRef = flightLookups.flightsActor
  override val queuesActor: ActorRef = minuteLookups.queueMinutesActor
  override val staffActor: ActorRef = minuteLookups.staffMinutesActor
  override val queueUpdates: ActorRef = system.actorOf(Props(new QueueUpdatesSupervisor(now, airportConfig.queuesByTerminal.keys.toList, queueUpdatesProps(now, journalType))), "updates-supervisor-queues")
  override val staffUpdates: ActorRef = system.actorOf(Props(new StaffUpdatesSupervisor(now, airportConfig.queuesByTerminal.keys.toList, staffUpdatesProps(now, journalType))), "updates-supervisor-staff")
  override val flightUpdates: ActorRef = system.actorOf(Props(new FlightUpdatesSupervisor(now, airportConfig.queuesByTerminal.keys.toList, flightUpdatesProps(now, journalType))), "updates-supervisor-flights")

  override val portStateActor: ActorRef = system.actorOf(Props(new PartitionedPortStateActor(
    flightsActor,
    queuesActor,
    staffActor,
    queueUpdates,
    staffUpdates,
    flightUpdates,
    now,
    airportConfig.queuesByTerminal,
    journalType)))

  val manifestsArrivalRequestSource: Source[List[Arrival], SourceQueueWithComplete[List[Arrival]]] = Source.queue[List[Arrival]](100, OverflowStrategy.backpressure)

  override val shiftsActor: ActorRef = restartOnStop.actorOf(Props(new ShiftsActor(now, timeBeforeThisMonth(now))), "staff-shifts")
  override val fixedPointsActor: ActorRef = restartOnStop.actorOf(Props(new FixedPointsActor(now)), "staff-fixed-points")
  override val staffMovementsActor: ActorRef = restartOnStop.actorOf(Props(new StaffMovementsActor(now, time48HoursAgo(now))), "staff-movements")

  val s3ApiProvider: S3ApiProvider = S3ApiProvider(params.awSCredentials, params.dqZipBucketName)
  val initialManifestsState: Option[ApiFeedState] = if (refetchApiData) None else initialState[ApiFeedState](manifestsRouterActor)
  system.log.info(s"Providing latest API Zip Filename from storage: ${initialManifestsState.map(_.latestZipFilename).getOrElse("None")}")
  val latestZipFileName: String = S3ApiProvider.latestUnexpiredDqZipFilename(initialManifestsState.map(_.latestZipFilename), now, expireAfterMillis)

  system.log.info(s"useNationalityBasedProcessingTimes: ${params.useNationalityBasedProcessingTimes}")

  def getRoles(config: Configuration, headers: Headers, session: Session): Set[Role] =
    if (params.isSuperUserMode) {
      system.log.debug(s"Using Super User Roles")
      Roles.availableRoles
    } else userRolesFromHeader(headers)

  def run(): Unit = {
    val futurePortStates: Future[
      (Option[PortState],
        Option[SortedMap[UniqueArrival, Arrival]],
        Option[SortedMap[UniqueArrival, Arrival]],
        Option[SortedMap[UniqueArrival, Arrival]],
        Option[mutable.SortedSet[CrunchRequest]],
        Option[mutable.SortedSet[CrunchRequest]],
        Option[FeedSourceStatuses],
        )] = {
      val maybeLivePortState = initialFlightsPortState(portStateActor, params.forecastMaxDays)
      val maybeInitialBaseArrivals = initialStateFuture[ArrivalsState](forecastBaseArrivalsActor).map(_.map(_.arrivals))
      val maybeInitialFcstArrivals = initialStateFuture[ArrivalsState](forecastArrivalsActor).map(_.map(_.arrivals))
      val maybeInitialLiveArrivals = initialStateFuture[ArrivalsState](liveArrivalsActor).map(_.map(_.arrivals))
      val maybeInitialCrunchQueue = initialStateFuture[mutable.SortedSet[CrunchRequest]](persistentCrunchQueueActor)
      val maybeInitialDeploymentQueue = initialStateFuture[mutable.SortedSet[CrunchRequest]](persistentDeploymentQueueActor)
      val aclFeedStatus = forecastBaseArrivalsActor.ask(GetFeedStatuses).mapTo[Option[FeedSourceStatuses]]
      for {
        lps <- maybeLivePortState
        ba <- maybeInitialBaseArrivals
        fa <- maybeInitialFcstArrivals
        la <- maybeInitialLiveArrivals
        cq <- maybeInitialCrunchQueue
        dq <- maybeInitialDeploymentQueue
        aclStatus <- aclFeedStatus
      } yield (lps, ba, fa, la, cq, dq, aclStatus)
    }

    futurePortStates.onComplete {
      case Success((maybePortState, maybeBaseArrivals, maybeForecastArrivals, maybeLiveArrivals, maybeCrunchQueue, maybeDeploymentQueue, maybeAclStatus)) =>
        system.log.info(s"Successfully restored initial state for App")

        val crunchInputs: CrunchSystem[typed.ActorRef[Feed.FeedTick]] = startCrunchSystem(
          initialPortState = maybePortState,
          initialForecastBaseArrivals = maybeBaseArrivals,
          initialForecastArrivals = maybeForecastArrivals,
          initialLiveBaseArrivals = Option(SortedMap[UniqueArrival, Arrival]()),
          initialLiveArrivals = maybeLiveArrivals,
          refreshArrivalsOnStart = params.refreshArrivalsOnStart,
          refreshManifestsOnStart = params.refreshManifestsOnStart,
          startDeskRecs = startDeskRecs)

        fcstBaseActor ! Enable(crunchInputs.forecastBaseArrivalsResponse)
        fcstActor ! Enable(crunchInputs.forecastArrivalsResponse)
        liveBaseActor ! Enable(crunchInputs.liveBaseArrivalsResponse)
        liveActor ! Enable(crunchInputs.liveArrivalsResponse)

        for {
          aclStatus <- maybeAclStatus
          lastSuccess <- aclStatus.feedStatuses.lastSuccessAt
        } yield {
          val twelveHoursAgo = SDate.now().addMinutes(-12).millisSinceEpoch
          if (lastSuccess < twelveHoursAgo) {
            log.info(s"Last ACL check was more than 12 hours ago. Will check now")
            fcstBaseActor ! AdhocCheck
          }
        }

        new S3ManifestPoller(crunchInputs.manifestsLiveResponse, airportConfig.portCode, latestZipFileName, s3ApiProvider).startPollingForManifests()

        redListUpdatesActor ! AddSubscriber(crunchInputs.redListUpdates)
        flightsActor ! SetCrunchRequestQueue(crunchInputs.crunchRequestActor)
        manifestsRouterActor ! SetCrunchRequestQueue(crunchInputs.crunchRequestActor)
        queuesActor ! SetCrunchRequestQueue(crunchInputs.deploymentRequestActor)

        subscribeStaffingActors(crunchInputs)
        startScheduledFeedImports(crunchInputs)

      case Failure(error) =>
        system.log.error(error, s"Failed to restore initial state for App")
        System.exit(1)
    }

    val staffingStates: Future[NotUsed] = {
      val maybeShifts = initialStateFuture[ShiftAssignments](shiftsActor)
      val maybeFixedPoints = initialStateFuture[FixedPointAssignments](fixedPointsActor)
      val maybeMovements = initialStateFuture[StaffMovements](staffMovementsActor)
      for {
        _ <- maybeShifts
        _ <- maybeFixedPoints
        _ <- maybeMovements
      } yield NotUsed
    }

    staffingStates.onComplete {
      case Success(NotUsed) =>
        system.log.info(s"Successfully restored initial staffing states for App")

      case Failure(error) =>
        system.log.error(error, s"Failed to restore initial staffing state for App")
        System.exit(1)
    }
  }

  val coachWalkTime: CoachWalkTime = CoachWalkTime(airportConfig.portCode)
}

case class SetCrunchRequestQueue(source: ActorRef)
