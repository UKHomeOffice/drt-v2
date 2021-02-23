package actors

import actors.PartitionedPortStateActor.{flightUpdatesProps, queueUpdatesProps, staffUpdatesProps}
import actors.daily.{FlightUpdatesSupervisor, QueueUpdatesSupervisor, StaffUpdatesSupervisor}
import actors.queues.{ApiFeedState, CrunchQueueActor, DeploymentQueueActor, ManifestRouterActor}
import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem, Cancellable, Props}
import akka.stream.scaladsl.{Source, SourceQueueWithComplete}
import akka.stream.{ActorMaterializer, OverflowStrategy}
import drt.server.feeds.api.S3ApiProvider
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.Terminals._
import drt.shared._
import drt.shared.api.Arrival
import manifests.ManifestLookup
import manifests.passengers.S3ManifestPoller
import play.api.Configuration
import play.api.mvc.{Headers, Session}
import server.feeds.ManifestsFeedResponse
import services._
import services.crunch.CrunchSystem
import services.crunch.deskrecs.RunnableOptimisation.CrunchRequest
import slickdb.{ArrivalTable, Tables, VoyageManifestPassengerInfoTable}
import uk.gov.homeoffice.drt.auth.Roles
import uk.gov.homeoffice.drt.auth.Roles.Role

import scala.collection.immutable.SortedMap
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success}


object PostgresTables extends {
  val profile = slick.jdbc.PostgresProfile
} with Tables

case class SubscribeResponseQueue(subscriber: SourceQueueWithComplete[ManifestsFeedResponse])

case class ProdDrtSystem(config: Configuration, airportConfig: AirportConfig)
                        (implicit val materializer: ActorMaterializer,
                         val ec: ExecutionContext,
                         val system: ActorSystem) extends DrtSystemInterface {

  import DrtStaticParameters._

  val maxBufferSize: Int = config.get[Int]("crunch.manifests.max-buffer-size")
  val minSecondsBetweenBatches: Int = config.get[Int]("crunch.manifests.min-seconds-between-batches")
  val refetchApiData: Boolean = config.get[Boolean]("crunch.manifests.refetch-live-api")

  val aggregateArrivalsDbConfigKey = "aggregated-db"

  val forecastMaxMillis: () => MillisSinceEpoch = () => now().addDays(params.forecastMaxDays).millisSinceEpoch

  override val baseArrivalsActor: ActorRef = system.actorOf(Props(new ForecastBaseArrivalsActor(params.snapshotMegaBytesBaseArrivals, now, expireAfterMillis)), name = "base-arrivals-actor")

  override val forecastArrivalsActor: ActorRef = system.actorOf(Props(new ForecastPortArrivalsActor(params.snapshotMegaBytesFcstArrivals, now, expireAfterMillis)), name = "forecast-arrivals-actor")
  override val liveArrivalsActor: ActorRef = system.actorOf(Props(new LiveArrivalsActor(params.snapshotMegaBytesLiveArrivals, now, expireAfterMillis)), name = "live-arrivals-actor")

  val manifestLookups: ManifestLookups = ManifestLookups(system)

  override val aggregatedArrivalsActor: ActorRef = system.actorOf(Props(new AggregatedArrivalsActor(ArrivalTable(airportConfig.portCode, PostgresTables))), name = "aggregated-arrivals-actor")

  override val crunchQueueActor: ActorRef = system.actorOf(Props(new CrunchQueueActor(now, airportConfig.crunchOffsetMinutes, airportConfig.minutesToCrunch)), name = "crunch-queue-actor")
  override val deploymentQueueActor: ActorRef = system.actorOf(Props(new DeploymentQueueActor(now, airportConfig.crunchOffsetMinutes, airportConfig.minutesToCrunch)), name = "staff-queue-actor")
  override val manifestsRouterActor: ActorRef = system.actorOf(Props(new ManifestRouterActor(manifestLookups.manifestsByDayLookup, manifestLookups.updateManifests, crunchQueueActor)), name = "voyage-manifests-router-actor")

  override val manifestLookupService: ManifestLookup = ManifestLookup(VoyageManifestPassengerInfoTable(PostgresTables))

  override val minuteLookups: MinuteLookups = MinuteLookups(system, now, MilliTimes.oneDayMillis, airportConfig.queuesByTerminal, deploymentQueueActor)

  val flightLookups: FlightLookups = FlightLookups(
    system,
    now,
    airportConfig.queuesByTerminal,
    crunchQueueActor
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

  override val shiftsActor: ActorRef = system.actorOf(Props(new ShiftsActor(now, timeBeforeThisMonth(now))))
  override val fixedPointsActor: ActorRef = system.actorOf(Props(new FixedPointsActor(now)))
  override val staffMovementsActor: ActorRef = system.actorOf(Props(new StaffMovementsActor(now, time48HoursAgo(now))))

  override val alertsActor: ActorRef = system.actorOf(Props(new AlertsActor(now)))

  val s3ApiProvider: S3ApiProvider = S3ApiProvider(params.awSCredentials, params.dqZipBucketName)
  val initialManifestsState: Option[ApiFeedState] = if (refetchApiData) None else initialState[ApiFeedState](manifestsRouterActor)
  log.info(s"Providing latest API Zip Filename from storage: ${initialManifestsState.map(_.latestZipFilename).getOrElse("None")}")
  val latestZipFileName: String = S3ApiProvider.latestUnexpiredDqZipFilename(initialManifestsState.map(_.latestZipFilename), now, expireAfterMillis)

  system.log.info(s"useNationalityBasedProcessingTimes: ${params.useNationalityBasedProcessingTimes}")

  def getRoles(config: Configuration, headers: Headers, session: Session): Set[Role] =
    if (params.isSuperUserMode) {
      system.log.debug(s"Using Super User Roles")
      Roles.availableRoles
    } else userRolesFromHeader(headers)

  def run(): Unit = {
    val futurePortStates: Future[(Option[PortState], Option[SortedMap[UniqueArrival, Arrival]], Option[SortedMap[UniqueArrival, Arrival]], Option[SortedMap[UniqueArrival, Arrival]])] = {
      val maybeLivePortState = initialFlightsPortState(portStateActor, params.forecastMaxDays)
      val maybeInitialBaseArrivals = initialStateFuture[ArrivalsState](baseArrivalsActor).map(_.map(_.arrivals))
      val maybeInitialFcstArrivals = initialStateFuture[ArrivalsState](forecastArrivalsActor).map(_.map(_.arrivals))
      val maybeInitialLiveArrivals = initialStateFuture[ArrivalsState](liveArrivalsActor).map(_.map(_.arrivals))
      for {
        lps <- maybeLivePortState
        ba <- maybeInitialBaseArrivals
        fa <- maybeInitialFcstArrivals
        la <- maybeInitialLiveArrivals
      } yield (lps, ba, fa, la)
    }

    futurePortStates.onComplete {
      case Success((maybePortState, maybeBaseArrivals, maybeForecastArrivals, maybeLiveArrivals)) =>
        system.log.info(s"Successfully restored initial state for App")

        val crunchInputs: CrunchSystem[Cancellable] = startCrunchSystem(
          maybePortState,
          maybeBaseArrivals,
          maybeForecastArrivals,
          Option(SortedMap[UniqueArrival, Arrival]()),
          maybeLiveArrivals,
          params.refreshArrivalsOnStart,
          params.refreshManifestsOnStart,
          startDeskRecs)

        new S3ManifestPoller(crunchInputs.manifestsLiveResponse, airportConfig.portCode, latestZipFileName, s3ApiProvider).startPollingForManifests()

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
        if (params.snapshotStaffOnStart) {
          log.info(s"Snapshotting staff as requested by feature flag")
          shiftsActor ! SaveSnapshot
          fixedPointsActor ! SaveSnapshot
          staffMovementsActor ! SaveSnapshot
        }

      case Failure(error) =>
        system.log.error(error, s"Failed to restore initial staffing state for App")
        System.exit(1)
    }
  }
}

case class SetCrunchRequestQueue(source: SourceQueueWithComplete[CrunchRequest])

object ArrivalGenerator {
  def arrival(iata: String = "",
              icao: String = "",
              schDt: String = "",
              actPax: Option[Int] = None,
              maxPax: Option[Int] = None,
              terminal: Terminal = T1,
              origin: PortCode = PortCode(""),
              operator: Option[Operator] = None,
              status: String = "",
              estDt: String = "",
              actDt: String = "",
              estChoxDt: String = "",
              actChoxDt: String = "",
              pcpDt: String = "",
              gate: Option[String] = None,
              stand: Option[String] = None,
              tranPax: Option[Int] = None,
              runwayId: Option[String] = None,
              baggageReclaimId: Option[String] = None,
              airportId: PortCode = PortCode(""),
              feedSources: Set[FeedSource] = Set()
             ): Arrival = {
    val pcpTime = if (pcpDt.nonEmpty) Option(SDate(pcpDt).millisSinceEpoch) else if (schDt.nonEmpty) Option(SDate(schDt).millisSinceEpoch) else None

    Arrival(
      rawICAO = icao,
      rawIATA = iata,
      ActPax = actPax,
      Terminal = terminal,
      Origin = origin,
      Operator = operator,
      Status = ArrivalStatus(status),
      Estimated = if (estDt.nonEmpty) Option(SDate.parseString(estDt).millisSinceEpoch) else None,
      Actual = if (actDt.nonEmpty) Option(SDate.parseString(actDt).millisSinceEpoch) else None,
      EstimatedChox = if (estChoxDt.nonEmpty) Option(SDate.parseString(estChoxDt).millisSinceEpoch) else None,
      ActualChox = if (actChoxDt.nonEmpty) Option(SDate.parseString(actChoxDt).millisSinceEpoch) else None,
      Gate = gate,
      Stand = stand,
      MaxPax = maxPax,
      TranPax = tranPax,
      RunwayID = runwayId,
      BaggageReclaimId = baggageReclaimId,
      AirportID = airportId,
      PcpTime = pcpTime,
      Scheduled = if (schDt.nonEmpty) SDate(schDt).millisSinceEpoch else 0,
      FeedSources = feedSources
    )
  }

  def arrivals(now: () => SDateLike, terminalNames: Iterable[Terminal]): Iterable[Arrival] = {
    val today = now().toISODateOnly
    val arrivals = for {
      terminal <- terminalNames
    } yield {
      (1 to 100).map { _ =>
        def rand(max: Int): Int = (Math.random() * max).floor.toInt

        ArrivalGenerator.arrival(iata = s"BA${rand(1000)}", terminal = terminal, schDt = s"${today}T${rand(24)}:${rand(60)}", actPax = Option(rand(450)))
      }
    }

    arrivals.flatten
  }
}
