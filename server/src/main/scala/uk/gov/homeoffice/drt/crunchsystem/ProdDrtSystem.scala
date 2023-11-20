package uk.gov.homeoffice.drt.crunchsystem

import actors.CrunchManagerActor.AddRecalculateArrivalsSubscriber
import actors.PartitionedPortStateActor.{flightUpdatesProps, queueUpdatesProps, staffUpdatesProps}
import actors._
import actors.daily.{FlightUpdatesSupervisor, QueueUpdatesSupervisor, StaffUpdatesSupervisor}
import actors.persistent.ApiFeedState
import actors.persistent.staffing.GetFeedStatuses
import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem, Props, typed}
import akka.pattern.ask
import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import akka.util.Timeout
import com.google.inject.Inject
import drt.server.feeds.Feed
import drt.server.feeds.FeedPoller.{AdhocCheck, Enable}
import drt.server.feeds.api.{ApiFeedImpl, DbManifestArrivalKeys, DbManifestProcessor}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.{FixedPointAssignments, PortState, ShiftAssignments, StaffMovements}
import manifests.ManifestLookup
import play.api.Configuration
import play.api.mvc.{Headers, Session}
import services.crunch.CrunchSystem
import services.metrics.ApiValidityReporter
import slickdb._
import uk.gov.homeoffice.drt.actor.commands.Commands.GetState
import uk.gov.homeoffice.drt.actor.commands.ProcessingRequest
import uk.gov.homeoffice.drt.actor.state.ArrivalsState
import uk.gov.homeoffice.drt.arrivals.{Arrival, UniqueArrival}
import uk.gov.homeoffice.drt.auth.Roles
import uk.gov.homeoffice.drt.auth.Roles.Role
import uk.gov.homeoffice.drt.feeds.FeedSourceStatuses
import uk.gov.homeoffice.drt.ports.AirportConfig
import uk.gov.homeoffice.drt.time.{MilliTimes, SDate}

import javax.inject.Singleton
import scala.collection.SortedSet
import scala.collection.immutable.SortedMap
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

@Singleton
case class ProdDrtSystem @Inject()(airportConfig: AirportConfig, params: DrtParameters)
                                  (implicit val materializer: Materializer,
                                   val ec: ExecutionContext,
                                   val system: ActorSystem,
                                   val timeout: Timeout) extends DrtSystemInterface {
  private val refetchApiData: Boolean = config.get[Boolean]("crunch.manifests.refetch-live-api")

  val forecastMaxMillis: () => MillisSinceEpoch = () => now().addDays(params.forecastMaxDays).millisSinceEpoch

  override val manifestLookupService: ManifestLookup = ManifestLookup(PostgresTables)

  override val userService: UserTableLike = UserTable(PostgresTables)

  override val featureGuideService: FeatureGuideTableLike = FeatureGuideTable(PostgresTables)

  override val featureGuideViewService: FeatureGuideViewLike = FeatureGuideViewTable(PostgresTables)

  override val dropInService: DropInTableLike = DropInTable(PostgresTables)

  override val dropInRegistrationService: DropInsRegistrationTableLike = DropInsRegistrationTable(PostgresTables)

  override val minuteLookups: MinuteLookups = MinuteLookups(now, MilliTimes.oneDayMillis, airportConfig.queuesByTerminal)

  val flightLookups: FlightLookups = FlightLookups(
    system,
    now,
    airportConfig.queuesByTerminal,
    params.maybeRemovalCutOffSeconds,
    paxFeedSourceOrder,
  )

  override val flightsRouterActor: ActorRef = flightLookups.flightsRouterActor
  override val queueLoadsRouterActor: ActorRef = minuteLookups.queueLoadsMinutesActor
  override val queuesRouterActor: ActorRef = minuteLookups.queueMinutesRouterActor
  override val staffRouterActor: ActorRef = minuteLookups.staffMinutesRouterActor
  override val queueUpdates: ActorRef =
    system.actorOf(Props(new QueueUpdatesSupervisor(
      now,
      airportConfig.queuesByTerminal.keys.toList,
      queueUpdatesProps(now, journalType))), "updates-supervisor-queues")
  override val staffUpdates: ActorRef =
    system.actorOf(Props(new StaffUpdatesSupervisor(
      now,
      airportConfig.queuesByTerminal.keys.toList,
      staffUpdatesProps(now, journalType))), "updates-supervisor-staff")
  override val flightUpdates: ActorRef =
    system.actorOf(Props(new FlightUpdatesSupervisor(
      now,
      airportConfig.queuesByTerminal.keys.toList,
      flightUpdatesProps(now, journalType))), "updates-supervisor-flights")

  override val portStateActor: ActorRef = system.actorOf(Props(new PartitionedPortStateActor(
    flightsRouterActor = flightsRouterActor,
    queuesRouterActor = queuesRouterActor,
    staffRouterActor = staffRouterActor,
    queueUpdatesActor = queueUpdates,
    staffUpdatesActor = staffUpdates,
    flightUpdatesActor = flightUpdates,
    now = now,
    queues = airportConfig.queuesByTerminal,
    journalType = journalType)))

  def getRoles(config: Configuration, headers: Headers, session: Session): Set[Role] =
    if (params.isSuperUserMode) {
      system.log.debug(s"Using Super User Roles")
      Roles.availableRoles
    } else userRolesFromHeader(headers)

  def run(): Unit = {
    val actors = ProdPersistentStateActors(
      system,
      now,
      airportConfig.minutesToCrunch,
      airportConfig.crunchOffsetMinutes,
      params.forecastMaxDays,
      manifestLookups,
      airportConfig.portCode,
      paxFeedSourceOrder)

    val futurePortStates: Future[
      (Option[PortState],
        Option[SortedMap[UniqueArrival, Arrival]],
        Option[SortedMap[UniqueArrival, Arrival]],
        Option[SortedMap[UniqueArrival, Arrival]],
        Option[FeedSourceStatuses],
        SortedSet[ProcessingRequest],
        SortedSet[ProcessingRequest],
        SortedSet[ProcessingRequest],
        SortedSet[ProcessingRequest],
        )] = {
      for {
        lps <- initialFlightsPortState(portStateActor, params.forecastMaxDays)
        ba <- initialStateFuture[ArrivalsState](actors.forecastBaseArrivalsActor).map(_.map(_.arrivals))
        fa <- initialStateFuture[ArrivalsState](actors.forecastArrivalsActor).map(_.map(_.arrivals))
        la <- initialStateFuture[ArrivalsState](actors.liveArrivalsActor).map(_.map(_.arrivals))
        aclStatus <- actors.forecastBaseArrivalsActor.ask(GetFeedStatuses).mapTo[Option[FeedSourceStatuses]]
        crunchQueue <- actors.crunchQueueActor.ask(GetState).mapTo[SortedSet[ProcessingRequest]]
        deskRecsQueue <- actors.deskRecsQueueActor.ask(GetState).mapTo[SortedSet[ProcessingRequest]]
        deploymentQueue <- actors.deploymentQueueActor.ask(GetState).mapTo[SortedSet[ProcessingRequest]]
        staffingUpdateQueue <- actors.staffingQueueActor.ask(GetState).mapTo[SortedSet[ProcessingRequest]]
      } yield (lps, ba, fa, la, aclStatus, crunchQueue, deskRecsQueue, deploymentQueue, staffingUpdateQueue)
    }

    futurePortStates.onComplete {
      case Success((maybePortState, maybeBaseArrivals, maybeForecastArrivals, maybeLiveArrivals, maybeAclStatus, crunchQueue, deskRecsQueue, deploymentQueue, staffingUpdateQueue)) =>
        system.log.info(s"Successfully restored initial state for App")

        val crunchInputs: CrunchSystem[typed.ActorRef[Feed.FeedTick]] = startCrunchSystem(
          actors,
          initialPortState = maybePortState,
          initialForecastBaseArrivals = maybeBaseArrivals,
          initialForecastArrivals = maybeForecastArrivals,
          initialLiveBaseArrivals = Option(SortedMap[UniqueArrival, Arrival]()),
          initialLiveArrivals = maybeLiveArrivals,
          refreshArrivalsOnStart = params.refreshArrivalsOnStart,
          startDeskRecs = startDeskRecs(actors, crunchQueue, deskRecsQueue, deploymentQueue, staffingUpdateQueue),
        )

        fcstBaseActor ! Enable(crunchInputs.forecastBaseArrivalsResponse)
        fcstActor ! Enable(crunchInputs.forecastArrivalsResponse)
        liveBaseActor ! Enable(crunchInputs.liveBaseArrivalsResponse)
        liveActor ! Enable(crunchInputs.liveArrivalsResponse)

        for {
          aclStatus <- maybeAclStatus
          lastSuccess <- aclStatus.feedStatuses.lastSuccessAt
        } yield {
          val twelveHoursAgo = SDate.now().addHours(-12).millisSinceEpoch
          if (lastSuccess < twelveHoursAgo) {
            val minutesToNextCheck = (Math.random() * 90).toInt.minutes
            log.info(s"Last ACL check was more than 12 hours ago. Will check in ${minutesToNextCheck.toMinutes} minutes")
            system.scheduler.scheduleOnce(minutesToNextCheck) {
              fcstBaseActor ! AdhocCheck
            }
          }
        }
        val lastProcessedLiveApiMarker: Option[MillisSinceEpoch] =
          if (refetchApiData) None
          else initialState[ApiFeedState](actors.manifestsRouterActor).map(_.lastProcessedMarker)
        system.log.info(s"Providing last processed API marker: ${lastProcessedLiveApiMarker.map(SDate(_).toISOString).getOrElse("None")}")

        val arrivalKeysProvider = DbManifestArrivalKeys(PostgresTables, airportConfig.portCode)
        val manifestProcessor = DbManifestProcessor(PostgresTables, airportConfig.portCode, crunchInputs.manifestsLiveResponseSource)
        val processFilesAfter = lastProcessedLiveApiMarker.getOrElse(SDate.now().addHours(-12).millisSinceEpoch)
        log.info(s"Importing live manifests processed after ${SDate(processFilesAfter).toISOString}")
        ApiFeedImpl(arrivalKeysProvider, manifestProcessor, 1.second)
          .processFilesAfter(processFilesAfter)
          .runWith(Sink.ignore)

        crunchManagerActor ! AddRecalculateArrivalsSubscriber(crunchInputs.flushArrivalsSource)

        setSubscribers(crunchInputs, actors.manifestsRouterActor)

        system.scheduler.scheduleAtFixedRate(0.millis, 1.minute)(ApiValidityReporter(flightsRouterActor))

      case Failure(error) =>
        system.log.error(error, s"Failed to restore initial state for App. Beginning actor system shutdown")
        system.terminate().onComplete {
          case Success(_) =>
            log.info("Actor system successfully shut down. Exiting")
            System.exit(1)
          case Failure(exception) =>
            log.warn("Failed to shut down actor system", exception)
            System.exit(1)
        }
    }

    val staffingStates: Future[NotUsed] = {
      val maybeShifts = initialStateFuture[ShiftAssignments](actors.shiftsActor)
      val maybeFixedPoints = initialStateFuture[FixedPointAssignments](actors.fixedPointsActor)
      val maybeMovements = initialStateFuture[StaffMovements](actors.staffMovementsActor)
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
}
