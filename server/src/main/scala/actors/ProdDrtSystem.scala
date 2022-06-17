package actors

import actors.PartitionedPortStateActor.{flightUpdatesProps, queueUpdatesProps, staffUpdatesProps}
import actors.daily.{FlightUpdatesSupervisor, QueueUpdatesSupervisor, StaffUpdatesSupervisor}
import actors.persistent.RedListUpdatesActor.AddSubscriber
import actors.persistent.arrivals.{AclForecastArrivalsActor, ArrivalsState, PortForecastArrivalsActor, PortLiveArrivalsActor}
import actors.persistent.staffing._
import actors.persistent._
import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem, Props, typed}
import akka.pattern.ask
import akka.stream.scaladsl.{Sink, Source, SourceQueueWithComplete}
import akka.stream.{Materializer, OverflowStrategy}
import akka.util.Timeout
import drt.server.feeds.Feed
import drt.server.feeds.FeedPoller.{AdhocCheck, Enable}
import drt.server.feeds.api.{ApiFeedImpl, DbManifestArrivalKeys, DbManifestProcessor}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared._
import drt.shared.coachTime.CoachWalkTime
import manifests.ManifestLookup
import play.api.Configuration
import play.api.mvc.{Headers, Session}
import server.feeds.ManifestsFeedResponse
import services.SDate
import services.crunch.CrunchSystem
import services.crunch.deskrecs.RunnableOptimisation.ProcessingRequest
import services.metrics.ApiValidityReporter
import slick.dbio.{DBIOAction, NoStream}
import slickdb.{ArrivalTable, Tables}
import uk.gov.homeoffice.drt.arrivals.{Arrival, UniqueArrival}
import uk.gov.homeoffice.drt.auth.Roles
import uk.gov.homeoffice.drt.auth.Roles.Role
import uk.gov.homeoffice.drt.ports.AirportConfig
import uk.gov.homeoffice.drt.time.MilliTimes

import scala.collection.SortedSet
import scala.collection.immutable.SortedMap
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}


object PostgresTables extends Tables {
  override val profile = slick.jdbc.PostgresProfile
  val db: profile.backend.Database = profile.api.Database.forConfig("aggregated-db")

  override def run[R](action: DBIOAction[R, NoStream, Nothing]): Future[R] = db.run[R](action)
}

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

  val forecastMaxMillis: () => MillisSinceEpoch = () => now().addDays(params.forecastMaxDays).millisSinceEpoch

  override val forecastBaseArrivalsActor: ActorRef = restartOnStop.actorOf(Props(new AclForecastArrivalsActor(params.snapshotMegaBytesBaseArrivals, now, expireAfterMillis)), name = "base-arrivals-actor")
  override val forecastArrivalsActor: ActorRef = restartOnStop.actorOf(Props(new PortForecastArrivalsActor(params.snapshotMegaBytesFcstArrivals, now, expireAfterMillis)), name = "forecast-arrivals-actor")
  override val liveArrivalsActor: ActorRef = restartOnStop.actorOf(Props(new PortLiveArrivalsActor(params.snapshotMegaBytesLiveArrivals, now, expireAfterMillis)), name = "live-arrivals-actor")

  val manifestLookups: ManifestLookups = ManifestLookups(system)

  override val aggregatedArrivalsActor: ActorRef = system.actorOf(Props(new AggregatedArrivalsActor(ArrivalTable(airportConfig.portCode, PostgresTables))), name = "aggregated-arrivals-actor")

  override val manifestsRouterActor: ActorRef = restartOnStop.actorOf(Props(new ManifestRouterActor(manifestLookups.manifestsByDayLookup, manifestLookups.updateManifests)), name = "voyage-manifests-router-actor")

  override val manifestLookupService: ManifestLookup = ManifestLookup(PostgresTables)

  override val minuteLookups: MinuteLookups = MinuteLookups(system, now, MilliTimes.oneDayMillis, airportConfig.queuesByTerminal)

  override val persistentCrunchQueueActor: ActorRef = system.actorOf(Props(new CrunchQueueActor(now = () => SDate.now(), airportConfig.crunchOffsetMinutes, airportConfig.minutesToCrunch)))
  override val persistentDeploymentQueueActor: ActorRef = system.actorOf(Props(new DeploymentQueueActor(now = () => SDate.now(), airportConfig.crunchOffsetMinutes, airportConfig.minutesToCrunch)))
  override val persistentStaffingUpdateQueueActor: ActorRef = system.actorOf(Props(new StaffingUpdateQueueActor(now = () => SDate.now(), airportConfig.crunchOffsetMinutes, airportConfig.minutesToCrunch)))

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

  val lastProcessedLiveApiMarker: Option[MillisSinceEpoch] = if (refetchApiData) None else initialState[ApiFeedState](manifestsRouterActor).map(_.lastProcessedMarker)
  system.log.info(s"Providing last processed API marker: ${lastProcessedLiveApiMarker.map(SDate(_).toISOString()).getOrElse("None")}")

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
        Option[FeedSourceStatuses],
        SortedSet[ProcessingRequest],
        SortedSet[ProcessingRequest],
        SortedSet[ProcessingRequest],
        )] = {
      for {
        lps <- initialFlightsPortState(portStateActor, params.forecastMaxDays)
        ba <- initialStateFuture[ArrivalsState](forecastBaseArrivalsActor).map(_.map(_.arrivals))
        fa <- initialStateFuture[ArrivalsState](forecastArrivalsActor).map(_.map(_.arrivals))
        la <- initialStateFuture[ArrivalsState](liveArrivalsActor).map(_.map(_.arrivals))
        aclStatus <- forecastBaseArrivalsActor.ask(GetFeedStatuses).mapTo[Option[FeedSourceStatuses]]
        crunchQueue <- persistentCrunchQueueActor.ask(GetState).mapTo[SortedSet[ProcessingRequest]]
        deploymentQueue <- persistentDeploymentQueueActor.ask(GetState).mapTo[SortedSet[ProcessingRequest]]
        staffingUpdateQueue <- persistentStaffingUpdateQueueActor.ask(GetState).mapTo[SortedSet[ProcessingRequest]]
      } yield (lps, ba, fa, la, aclStatus, crunchQueue, deploymentQueue, staffingUpdateQueue)
    }

    futurePortStates.onComplete {
      case Success((maybePortState, maybeBaseArrivals, maybeForecastArrivals, maybeLiveArrivals, maybeAclStatus, crunchQueue, deploymentQueue, staffingUpdateQueue)) =>
        system.log.info(s"Successfully restored initial state for App")

        val crunchInputs: CrunchSystem[typed.ActorRef[Feed.FeedTick]] = startCrunchSystem(
          initialPortState = maybePortState,
          initialForecastBaseArrivals = maybeBaseArrivals,
          initialForecastArrivals = maybeForecastArrivals,
          initialLiveBaseArrivals = Option(SortedMap[UniqueArrival, Arrival]()),
          initialLiveArrivals = maybeLiveArrivals,
          refreshArrivalsOnStart = params.refreshArrivalsOnStart,
          startDeskRecs = startDeskRecs(crunchQueue, deploymentQueue, staffingUpdateQueue),
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
            system.scheduler.scheduleOnce(minutesToNextCheck, () => fcstBaseActor ! AdhocCheck)
          }
        }

        val arrivalKeysProvider = DbManifestArrivalKeys(PostgresTables, airportConfig.portCode)
        val manifestProcessor = DbManifestProcessor(PostgresTables, airportConfig.portCode, crunchInputs.manifestsLiveResponse)
        val processFilesAfter = lastProcessedLiveApiMarker.getOrElse(SDate.now().addHours(-12).millisSinceEpoch)
        log.info(s"Importing live manifests processed after ${SDate(processFilesAfter).toISOString()}")
        ApiFeedImpl(arrivalKeysProvider, manifestProcessor, 15.seconds)
          .processFilesAfter(processFilesAfter)
          .runWith(Sink.ignore)

        redListUpdatesActor ! AddSubscriber(crunchInputs.redListUpdates)
        flightsActor ! SetCrunchRequestQueue(crunchInputs.crunchRequestActor)
        manifestsRouterActor ! SetCrunchRequestQueue(crunchInputs.crunchRequestActor)
        queuesActor ! SetCrunchRequestQueue(crunchInputs.deploymentRequestActor)

//        subscribeStaffingActors(crunchInputs)

        system.scheduler.scheduleAtFixedRate(0.millis, 1.minute)(ApiValidityReporter(flightsActor))

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
