package actors

import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem, Cancellable, Props}
import akka.pattern.AskableActorRef
import akka.stream.scaladsl.{Source, SourceQueueWithComplete}
import akka.stream.{Materializer, OverflowStrategy}
import drt.auth.{Role, Roles}
import drt.server.feeds.api.S3ApiProvider
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.MilliTimes._
import drt.shared.Terminals._
import drt.shared._
import graphs.SinkToSourceBridge
import manifests.ManifestLookup
import manifests.actors.RegisteredArrivals
import manifests.passengers.{BestAvailableManifest, S3ManifestPoller}
import play.api.Configuration
import play.api.mvc.{Headers, Session}
import server.feeds.ManifestsFeedResponse
import services._
import services.crunch.CrunchSystem
import slickdb.{ArrivalTable, Tables, VoyageManifestPassengerInfoTable}

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success}


object PostgresTables extends {
  val profile = slick.jdbc.PostgresProfile
} with Tables

case class SubscribeResponseQueue(subscriber: SourceQueueWithComplete[ManifestsFeedResponse])

case class DrtSystem(config: Configuration, airportConfig: AirportConfig)
                    (implicit val materializer: Materializer,
                     val ec: ExecutionContext,
                     val system: ActorSystem) extends DrtSystemInterface {

  import DrtStaticParameters._

  val maxBufferSize: Int = config.get[Int]("crunch.manifests.max-buffer-size")
  val minSecondsBetweenBatches: Int = config.get[Int]("crunch.manifests.min-seconds-between-batches")

  val aggregateArrivalsDbConfigKey = "aggregated-db"

  val forecastMaxMillis: () => MillisSinceEpoch = () => now().addDays(params.forecastMaxDays).millisSinceEpoch
  val liveCrunchStateProps: Props = CrunchStateActor.props(Option(airportConfig.portStateSnapshotInterval), params.snapshotMegaBytesLivePortState, "crunch-state", airportConfig.queuesByTerminal, now, expireAfterMillis, purgeOldLiveSnapshots, forecastMaxMillis)
  val forecastCrunchStateProps: Props = CrunchStateActor.props(Option(100), params.snapshotMegaBytesFcstPortState, "forecast-crunch-state", airportConfig.queuesByTerminal, now, expireAfterMillis, purgeOldForecastSnapshots, forecastMaxMillis)

  override val aggregatedArrivalsActor: ActorRef = system.actorOf(Props(classOf[AggregatedArrivalsActor], ArrivalTable(airportConfig.portCode, PostgresTables)), name = "aggregated-arrivals-actor")

  override val liveCrunchStateActor: AskableActorRef = system.actorOf(liveCrunchStateProps, name = "crunch-live-state-actor")
  override val forecastCrunchStateActor: AskableActorRef = system.actorOf(forecastCrunchStateProps, name = "crunch-forecast-state-actor")

  override val portStateActor: ActorRef = PortStateActor(now, liveCrunchStateActor, forecastCrunchStateActor)

  override val lookup: ManifestLookup = ManifestLookup(VoyageManifestPassengerInfoTable(PostgresTables))
  val manifestsArrivalRequestSource: Source[List[Arrival], SourceQueueWithComplete[List[Arrival]]] = Source.queue[List[Arrival]](100, OverflowStrategy.backpressure)

  override val shiftsActor: ActorRef = system.actorOf(Props(classOf[ShiftsActor], now, timeBeforeThisMonth(now)))
  override val fixedPointsActor: ActorRef = system.actorOf(Props(classOf[FixedPointsActor], now))
  override val staffMovementsActor: ActorRef = system.actorOf(Props(classOf[StaffMovementsActor], now, time48HoursAgo(now)))

  override val alertsActor: ActorRef = system.actorOf(Props(classOf[AlertsActor], now))

  val s3ApiProvider: S3ApiProvider = S3ApiProvider(params.awSCredentials, params.dqZipBucketName)
  val initialManifestsState: Option[VoyageManifestState] = initialState[VoyageManifestState](voyageManifestsActor)
  val latestZipFileName: String = S3ApiProvider.latestUnexpiredDqZipFilename(initialManifestsState.map(_.latestZipFilename), now, expireAfterMillis)

  val voyageManifestsLiveSource: Source[ManifestsFeedResponse, SourceQueueWithComplete[ManifestsFeedResponse]] = Source.queue[ManifestsFeedResponse](1, OverflowStrategy.backpressure)
  override val voyageManifestsHistoricSource: Source[ManifestsFeedResponse, SourceQueueWithComplete[ManifestsFeedResponse]] = Source.queue[ManifestsFeedResponse](1, OverflowStrategy.backpressure)

  system.log.info(s"useNationalityBasedProcessingTimes: ${params.useNationalityBasedProcessingTimes}")

  def getRoles(config: Configuration, headers: Headers, session: Session): Set[Role] =
    if (params.isSuperUserMode) {
      system.log.debug(s"Using Super User Roles")
      Roles.availableRoles
    } else userRolesFromHeader(headers)

  def run(): Unit = {
    val futurePortStates: Future[(Option[PortState], Option[PortState], Option[mutable.SortedMap[UniqueArrival, Arrival]], Option[mutable.SortedMap[UniqueArrival, Arrival]], Option[mutable.SortedMap[UniqueArrival, Arrival]], Option[RegisteredArrivals])] = {
      val maybeLivePortState = initialStateFuture[PortState](liveCrunchStateActor)
      val maybeForecastPortState = initialStateFuture[PortState](forecastCrunchStateActor)
      val maybeInitialBaseArrivals = initialStateFuture[ArrivalsState](baseArrivalsActor).map(_.map(_.arrivals))
      val maybeInitialFcstArrivals = initialStateFuture[ArrivalsState](forecastArrivalsActor).map(_.map(_.arrivals))
      val maybeInitialLiveArrivals = initialStateFuture[ArrivalsState](liveArrivalsActor).map(_.map(_.arrivals))
      val maybeInitialRegisteredArrivals = initialStateFuture[RegisteredArrivals](registeredArrivalsActor)
      for {
        lps <- maybeLivePortState
        fps <- maybeForecastPortState
        ba <- maybeInitialBaseArrivals
        fa <- maybeInitialFcstArrivals
        la <- maybeInitialLiveArrivals
        ra <- maybeInitialRegisteredArrivals
      } yield (lps, fps, ba, fa, la, ra)
    }

    futurePortStates.onComplete {
      case Success((maybeLiveState, maybeForecastState, maybeBaseArrivals, maybeForecastArrivals, maybeLiveArrivals, maybeRegisteredArrivals)) =>
        system.log.info(s"Successfully restored initial state for App")
        val initialPortState: Option[PortState] = mergePortStates(maybeForecastState, maybeLiveState)
        initialPortState.foreach(ps => portStateActor ! ps)

        val (manifestRequestsSource, _, manifestRequestsSink) = SinkToSourceBridge[List[Arrival]]

        val (manifestResponsesSource, _, manifestResponsesSink) = SinkToSourceBridge[List[BestAvailableManifest]]

        val crunchInputs: CrunchSystem[Cancellable] = startCrunchSystem(
          initialPortState,
          maybeBaseArrivals,
          maybeForecastArrivals,
          Option(mutable.SortedMap[UniqueArrival, Arrival]()),
          maybeLiveArrivals,
          manifestRequestsSink,
          manifestResponsesSource,
          params.refreshArrivalsOnStart,
          checkRequiredStaffUpdatesOnStartup = true,
          useLegacyDeployments,
          startDeskRecs)

        portStateActor ! SetSimulationActor(crunchInputs.loadsToSimulate)

        if (maybeRegisteredArrivals.isDefined) log.info(s"sending ${maybeRegisteredArrivals.get.arrivals.size} initial registered arrivals to batch stage")
        else log.info(s"sending no registered arrivals to batch stage")

        new S3ManifestPoller(crunchInputs.manifestsLiveResponse, airportConfig.portCode, latestZipFileName, s3ApiProvider).startPollingForManifests()

        if (!params.useLegacyManifests) {
          val initialRegisteredArrivals = if (params.resetRegisteredArrivalOnStart) {
            log.info(s"Resetting registered arrivals for manifest lookups")
            val maybeAllArrivals: Option[mutable.SortedMap[ArrivalKey, Option[Long]]] = initialPortState
              .map { state =>
                val arrivalsByKeySorted = mutable.SortedMap[ArrivalKey, Option[MillisSinceEpoch]]()
                state.flights.values.foreach(fws => arrivalsByKeySorted += (ArrivalKey(fws.apiFlight) -> None))
                log.info(s"Sending ${arrivalsByKeySorted.size} arrivals by key from ${state.flights.size} port state arrivals")
                arrivalsByKeySorted
              }
            Option(RegisteredArrivals(maybeAllArrivals.getOrElse(mutable.SortedMap())))
          } else maybeRegisteredArrivals
          val lookupRefreshDue: MillisSinceEpoch => Boolean = (lastLookupMillis: MillisSinceEpoch) => now().millisSinceEpoch - lastLookupMillis > 15 * oneMinuteMillis
          startManifestsGraph(initialRegisteredArrivals, manifestResponsesSink, manifestRequestsSource, lookupRefreshDue)
        }

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

case class SetSimulationActor(loadsToSimulate: AskableActorRef)

case class SetCrunchActor(millisToCrunchActor: AskableActorRef)

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
