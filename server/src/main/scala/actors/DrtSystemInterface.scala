package actors

import actors.CrunchManagerActor.AddQueueCrunchSubscriber
import actors.DrtStaticParameters.expireAfterMillis
import actors.PartitionedPortStateActor.{GetFlights, GetStateForDateRange, PointInTimeQuery}
import actors.daily.PassengersActor
import actors.persistent._
import actors.persistent.arrivals.CirriumLiveArrivalsActor
import actors.persistent.staffing._
import actors.routing.FlightsRouterActor
import actors.supervised.RestartOnStop
import akka.NotUsed
import akka.actor.typed.scaladsl.adapter.ClassicActorSystemOps
import akka.actor.{ActorRef, ActorSystem, Props, Scheduler, typed}
import akka.pattern.ask
import akka.stream.scaladsl.{Flow, Sink, Source, SourceQueueWithComplete}
import akka.stream.{Materializer, OverflowStrategy, UniqueKillSwitch}
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import controllers.{FeatureGuideProviderLike, UserRoleProviderLike}
import drt.chroma.chromafetcher.ChromaFetcher.ChromaLiveFlight
import drt.chroma.chromafetcher.{ChromaFetcher, ChromaFlightMarshallers}
import drt.chroma.{ChromaFeedType, ChromaLive}
import drt.http.ProdSendAndReceive
import drt.server.feeds.Feed.FeedTick
import drt.server.feeds._
import drt.server.feeds.acl.AclFeed
import drt.server.feeds.bhx.{BHXClient, BHXFeed}
import drt.server.feeds.chroma.ChromaLiveFeed
import drt.server.feeds.cirium.CiriumFeed
import drt.server.feeds.common.{ManualUploadArrivalFeed, ProdHttpClient}
import drt.server.feeds.edi.{EdiClient, EdiFeed}
import drt.server.feeds.gla.{GlaFeed, ProdGlaFeedRequester}
import drt.server.feeds.lcy.{LCYClient, LCYFeed}
import drt.server.feeds.legacy.bhx.BHXForecastFeedLegacy
import drt.server.feeds.lgw.{LGWAzureClient, LGWFeed, LgwForecastFeed}
import drt.server.feeds.lhr.LHRFlightFeed
import drt.server.feeds.lhr.sftp.LhrSftpLiveContentProvider
import drt.server.feeds.ltn.{LtnFeedRequester, LtnLiveFeed}
import drt.server.feeds.mag.{MagFeed, ProdFeedRequester}
import drt.shared.CrunchApi.{MillisSinceEpoch, MinutesContainer}
import drt.shared.FlightsApi.Flights
import drt.shared._
import manifests.ManifestLookupLike
import manifests.queues.SplitsCalculator
import org.joda.time.DateTimeZone
import play.api.Configuration
import queueus._
import services.PcpArrival.pcpFrom
import services._
import services.arrivals.{ArrivalsAdjustments, ArrivalsAdjustmentsLike}
import services.crunch.CrunchManager.queueDaysToReCrunch
import services.crunch.CrunchSystem.paxTypeQueueAllocator
import services.crunch.desklimits.{PortDeskLimits, TerminalDeskLimitsLike}
import services.crunch.deskrecs.RunnableOptimisation.ProcessingRequest
import services.crunch.deskrecs._
import services.crunch.staffing.RunnableStaffing
import services.crunch.{CrunchProps, CrunchSystem}
import services.graphstages.FlightFilter
import services.prediction.ArrivalPredictions
import services.staffing.StaffMinutesChecker
import uk.gov.homeoffice.drt.AppEnvironment
import uk.gov.homeoffice.drt.AppEnvironment.AppEnvironment
import uk.gov.homeoffice.drt.actor.PredictionModelActor.{TerminalCarrier, TerminalOrigin}
import uk.gov.homeoffice.drt.actor.{PredictionModelActor, WalkTimeProvider}
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.egates.{EgateBank, EgateBanksUpdate, EgateBanksUpdates, PortEgateBanksUpdates}
import uk.gov.homeoffice.drt.feeds.FeedSourceStatuses
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports._
import uk.gov.homeoffice.drt.prediction.arrival.{OffScheduleModelAndFeatures, PaxCapModelAndFeatures, ToChoxModelAndFeatures, WalkTimeModelAndFeatures}
import uk.gov.homeoffice.drt.prediction.persistence.Flight
import uk.gov.homeoffice.drt.redlist.RedListUpdates
import uk.gov.homeoffice.drt.time.MilliTimes.oneSecondMillis
import uk.gov.homeoffice.drt.time.{MilliDate => _, _}

import scala.collection.SortedSet
import scala.collection.immutable.SortedMap
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.language.postfixOps


trait DrtSystemInterface extends UserRoleProviderLike with FeatureGuideProviderLike {
  implicit val materializer: Materializer
  implicit val ec: ExecutionContext
  implicit val system: ActorSystem
  implicit val timeout: Timeout

  val now: () => SDateLike = () => SDate.now()

  val manifestLookupService: ManifestLookupLike

  val config: Configuration = new Configuration(ConfigFactory.load)

  val env: AppEnvironment = AppEnvironment(config.getOptional[String]("env").getOrElse("other"))
  val airportConfig: AirportConfig
  val params: DrtParameters
  val journalType: StreamingJournalLike = StreamingJournal.forConfig(config)

  private val walkTimeProvider: (Terminal, String, String) => Option[Int] = WalkTimeProvider(params.gateWalkTimesFilePath, params.standWalkTimesFilePath)

  private val minBackoffSeconds = config.get[Int]("persistence.on-stop-backoff.minimum-seconds")
  private val maxBackoffSeconds = config.get[Int]("persistence.on-stop-backoff.maximum-seconds")
  val restartOnStop: RestartOnStop = RestartOnStop(minBackoffSeconds seconds, maxBackoffSeconds seconds)

  private val defaultEgates: Map[Terminal, EgateBanksUpdates] = airportConfig.eGateBankSizes.view.mapValues { banks =>
    val effectiveFrom = SDate("2020-01-01T00:00").millisSinceEpoch
    EgateBanksUpdates(List(EgateBanksUpdate(effectiveFrom, EgateBank.fromAirportConfig(banks))))
  }.toMap

  val alertsActor: ActorRef = restartOnStop.actorOf(Props(new AlertsActor(now)), "alerts-actor")
  val redListUpdatesActor: ActorRef = restartOnStop.actorOf(Props(new RedListUpdatesActor(now)), "red-list-updates-actor")
  val egateBanksUpdatesActor: ActorRef = restartOnStop.actorOf(Props(new EgateBanksUpdatesActor(now,
    defaultEgates,
    airportConfig.crunchOffsetMinutes,
    airportConfig.minutesToCrunch,
    params.forecastMaxDays)), "egate-banks-updates-actor")
  val liveBaseArrivalsActor: ActorRef = restartOnStop.actorOf(Props(new CirriumLiveArrivalsActor(now, expireAfterMillis)), name = "live-base-arrivals-actor")
  val arrivalsImportActor: ActorRef = system.actorOf(Props(new ArrivalsImportActor()), name = "arrivals-import-actor")
  val persistentCrunchQueueActor: ActorRef
  val persistentDeskRecsQueueActor: ActorRef
  val persistentDeploymentQueueActor: ActorRef
  val persistentStaffingUpdateQueueActor: ActorRef

  val minuteLookups: MinuteLookupsLike

  val fcstBaseActor: typed.ActorRef[FeedPoller.Command] = system.spawn(FeedPoller(), "arrival-feed-forecast-base")
  val fcstActor: typed.ActorRef[FeedPoller.Command] = system.spawn(FeedPoller(), "arrival-feed-forecast")
  val liveBaseActor: typed.ActorRef[FeedPoller.Command] = system.spawn(FeedPoller(), "arrival-feed-live-base")
  val liveActor: typed.ActorRef[FeedPoller.Command] = system.spawn(FeedPoller(), "arrival-feed-live")
  val crunchManagerActor: ActorRef = system.actorOf(Props(new CrunchManagerActor), name = "crunch-manager-actor")

  val portStateActor: ActorRef
  val shiftsActor: ActorRef
  val fixedPointsActor: ActorRef
  val staffMovementsActor: ActorRef
  val forecastBaseArrivalsActor: ActorRef
  val forecastArrivalsActor: ActorRef
  val liveArrivalsActor: ActorRef
  val manifestsRouterActor: ActorRef

  val flightsRouterActor: ActorRef
  val queueLoadsRouterActor: ActorRef
  val queuesRouterActor: ActorRef
  val staffRouterActor: ActorRef
  val queueUpdates: ActorRef
  val staffUpdates: ActorRef
  val flightUpdates: ActorRef

  val forecastPaxNos: (LocalDate, SDateLike) => Future[Map[Terminal, Double]] = (date: LocalDate, atTime: SDateLike) =>
    paxForDay(date, Option(atTime))

  val actualPaxNos: LocalDate => Future[Map[Terminal, Double]] = (date: LocalDate) =>
    paxForDay(date, None)

  val paxFeedSourceOrder: List[FeedSource] = if (params.usePassengerPredictions) List(
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

  private def paxForDay(date: LocalDate, maybeAtTime: Option[SDateLike]): Future[Map[Terminal, Double]] = {
    val start = SDate(date)
    val end = start.addDays(1).addMinutes(-1)
    val rangeRequest = GetStateForDateRange(start.millisSinceEpoch, end.millisSinceEpoch)
    val request = maybeAtTime match {
      case Some(atTime) => PointInTimeQuery(atTime.millisSinceEpoch, rangeRequest)
      case None => rangeRequest
    }

    flightsRouterActor.ask(request)
      .mapTo[Source[(UtcDate, FlightsWithSplits), NotUsed]]
      .flatMap { source =>
        source.mapConcat {
          case (_, flights) =>
            flights.flights
              .filter { case (_, ApiFlightWithSplits(apiFlight, _, _)) =>
                SDate(apiFlight.bestArrivalTime(airportConfig.useTimePredictions)).toLocalDate == date
              }
              .values
              .groupBy(fws => fws.apiFlight.Terminal)
              .map {
                case (terminal, flights) =>
                  val paxNos = flights.map(fws => fws.apiFlight.bestPcpPaxEstimate(paxFeedSourceOrder).getOrElse(0)).sum
                  (terminal, paxNos.toDouble)
              }
        }.runWith(Sink.seq)
      }
      .map(_.toMap)
  }

  val forecastArrivals: (LocalDate, SDateLike) => Future[Map[Terminal, Seq[Arrival]]] = (date: LocalDate, atTime: SDateLike) =>
    arrivalsForDay(date, Option(atTime))

  val actualArrivals: LocalDate => Future[Map[Terminal, Seq[Arrival]]] = (date: LocalDate) =>
    arrivalsForDay(date, None)

  private def arrivalsForDay(date: LocalDate, maybeAtTime: Option[SDateLike]): Future[Map[Terminal, Seq[Arrival]]] = {
    val start = SDate(date)
    val end = start.addDays(1).addMinutes(-1)
    println(s"\nLooking at arrivals for $date at ${maybeAtTime.map(_.toISOString).getOrElse("-")} - request range: ${start.toISOString} -> ${end.toISOString}\n")
    val rangeRequest = GetStateForDateRange(start.millisSinceEpoch, end.millisSinceEpoch)
    val request = maybeAtTime match {
      case Some(atTime) => PointInTimeQuery(atTime.millisSinceEpoch, rangeRequest)
      case None => rangeRequest
    }

    flightsRouterActor.ask(request)
      .mapTo[Source[(UtcDate, FlightsWithSplits), NotUsed]]
      .flatMap { source =>
        source.mapConcat {
          case (utcDate, flights) =>
            println(s"\nGot ${flights.flights.size} flights for $utcDate\n")
            flights.flights
              .filter { case (_, ApiFlightWithSplits(apiFlight, _, _)) =>
                !apiFlight.Origin.isDomesticOrCta && SDate(apiFlight.Scheduled).toLocalDate == date
              }
              .values
              .groupBy(fws => fws.apiFlight.Terminal)
              .map {
                case (terminal, flights) => (terminal, flights.map(fws => fws.apiFlight).toSeq)
              }
        }.runWith(Sink.seq)
      }
      .map(_.toMap)
  }

  lazy private val feedActors: Map[FeedSource, ActorRef] = Map(
    LiveFeedSource -> liveArrivalsActor,
    LiveBaseFeedSource -> liveBaseArrivalsActor,
    ForecastFeedSource -> forecastArrivalsActor,
    AclFeedSource -> forecastBaseArrivalsActor,
    ApiFeedSource -> manifestsRouterActor
  )

  lazy val feedActorsForPort: Map[FeedSource, ActorRef] = feedActors.filter {
    case (feedSource: FeedSource, _) => isValidFeedSource(feedSource)
  }

  private val maybeAclFeed: Option[AclFeed] =
    if (params.aclDisabled) None
    else
      for {
        host <- params.aclHost
        username <- params.aclUsername
        keyPath <- params.aclKeyPath
      } yield AclFeed(host, username, keyPath, airportConfig.portCode, AclFeed.aclToPortMapping(airportConfig.portCode))

  val maxDaysToConsider: Int = 14
  val passengersActorProvider: () => ActorRef = () => system.actorOf(Props(new PassengersActor(maxDaysToConsider, aclPaxAdjustmentDays, now)))

  val aggregatedArrivalsActor: ActorRef

  val aclPaxAdjustmentDays: Int = config.get[Int]("acl.adjustment.number-of-days-in-average")

  val optimiser: TryCrunchWholePax = OptimiserWithFlexibleProcessors.crunchWholePax

  private val egatesProvider: () => Future[PortEgateBanksUpdates] = () => egateBanksUpdatesActor.ask(GetState).mapTo[PortEgateBanksUpdates]

  val portDeskRecs: PortDesksAndWaitsProviderLike = PortDesksAndWaitsProvider(airportConfig, optimiser, FlightFilter.forPortConfig(airportConfig), egatesProvider, paxFeedSourceOrder)

  val terminalEgatesProvider: Terminal => Future[EgateBanksUpdates] =
    terminal => egatesProvider().map(_.updatesByTerminal.getOrElse(terminal, throw new Exception(s"No egates found for terminal $terminal")))

  val deskLimitsProviders: Map[Terminal, TerminalDeskLimitsLike] = if (config.get[Boolean]("crunch.flex-desks")) {
    PortDeskLimits.flexed(airportConfig, terminalEgatesProvider)
  }
  else
    PortDeskLimits.fixed(airportConfig, terminalEgatesProvider)

  val paxTypeQueueAllocation: PaxTypeQueueAllocation = paxTypeQueueAllocator(airportConfig)

  val splitAdjustments: QueueAdjustments = if (params.adjustEGateUseByUnder12s)
    ChildEGateAdjustments(airportConfig.assumedAdultsPerChild)
  else
    AdjustmentsNoop

  def run(): Unit

  private def walkTimeProviderWithFallback(arrival: Arrival): MillisSinceEpoch = {
    val defaultWalkTimeMillis = airportConfig.defaultWalkTimeMillis.getOrElse(arrival.Terminal, 300000L)
    walkTimeProvider(arrival.Terminal, arrival.Gate.getOrElse(""), arrival.Stand.getOrElse(""))
      .map(_.toLong * oneSecondMillis)
      .getOrElse(defaultWalkTimeMillis)
  }

  private val pcpArrivalTimeCalculator: Arrival => MilliDate =
    pcpFrom(airportConfig.firstPaxOffMillis, walkTimeProviderWithFallback, airportConfig.useTimePredictions)

  val setPcpTimes: ArrivalsDiff => Future[ArrivalsDiff] = diff =>
    Future.successful {
      val updates = SortedMap[UniqueArrival, Arrival]() ++ diff.toUpdate.view.mapValues(arrival => arrival.copy(PcpTime = Option(pcpArrivalTimeCalculator(arrival).millisSinceEpoch)))
      diff.copy(toUpdate = updates)
    }

  def isValidFeedSource(fs: FeedSource): Boolean = airportConfig.feedSources.contains(fs)

  val startDeskRecs: (SortedSet[ProcessingRequest], SortedSet[ProcessingRequest], SortedSet[ProcessingRequest], SortedSet[ProcessingRequest]) =>
    () => (ActorRef, ActorRef, ActorRef, UniqueKillSwitch, UniqueKillSwitch, UniqueKillSwitch) =
    (crunchQueue, deskRecsQueue, deploymentQueue, staffQueue) => () => {
      val staffToDeskLimits = PortDeskLimits.flexedByAvailableStaff(airportConfig, terminalEgatesProvider) _

      implicit val timeout: Timeout = new Timeout(10.seconds)

      val splitsCalculator = SplitsCalculator(paxTypeQueueAllocation, airportConfig.terminalPaxSplits, splitAdjustments)

      val manifestCacheLookup = RouteHistoricManifestActor.manifestCacheLookup(airportConfig.portCode, now, system, timeout, ec)
      val manifestCacheStore = RouteHistoricManifestActor.manifestCacheStore(airportConfig.portCode, now, system, timeout, ec)
      val passengerLoadsProducer = DynamicRunnablePassengerLoads.crunchRequestsToQueueMinutes(
        arrivalsProvider = OptimisationProviders.flightsWithSplitsProvider(portStateActor),
        liveManifestsProvider = OptimisationProviders.liveManifestsProvider(manifestsRouterActor),
        historicManifestsProvider = OptimisationProviders.historicManifestsProvider(airportConfig.portCode, manifestLookupService, manifestCacheLookup, manifestCacheStore),
        historicManifestsPaxProvider = OptimisationProviders.historicManifestsPaxProvider(airportConfig.portCode, manifestLookupService),
        splitsCalculator = splitsCalculator,
        splitsSink = portStateActor,
        portDesksAndWaitsProvider = portDeskRecs,
        redListUpdatesProvider = () => redListUpdatesActor.ask(GetState).mapTo[RedListUpdates],
        DynamicQueueStatusProvider(airportConfig, egatesProvider),
        airportConfig.queuesByTerminal,
      )

      val (crunchRequestQueueActor, _: UniqueKillSwitch) =
        startOptimisationGraph(passengerLoadsProducer, persistentCrunchQueueActor, crunchQueue, minuteLookups.queueLoadsMinutesActor, "passenger-loads")

      val deskRecsProducer = DynamicRunnableDeskRecs.crunchRequestsToDeskRecs(
        loadsProvider = OptimisationProviders.passengersProvider(minuteLookups.queueLoadsMinutesActor),
        maxDesksProviders = deskLimitsProviders,
        loadsToQueueMinutes = portDeskRecs.loadsToDesks,
      )

      val (deskRecsRequestQueueActor, deskRecsKillSwitch) =
        startOptimisationGraph(deskRecsProducer, persistentDeskRecsQueueActor, deskRecsQueue, minuteLookups.queueMinutesRouterActor, "desk-recs")

      val deploymentsProducer = DynamicRunnableDeployments.crunchRequestsToDeployments(
        loadsProvider = OptimisationProviders.passengersProvider(minuteLookups.queueLoadsMinutesActor),
        staffProvider = OptimisationProviders.staffMinutesProvider(minuteLookups.staffMinutesRouterActor, airportConfig.terminals),
        staffToDeskLimits = staffToDeskLimits,
        loadsToQueueMinutes = portDeskRecs.loadsToSimulations
      )

      val (deploymentRequestQueueActor, deploymentsKillSwitch) =
        startOptimisationGraph(deploymentsProducer, persistentDeploymentQueueActor, deploymentQueue, minuteLookups.queueMinutesRouterActor, "deployments")

      val shiftsProvider = (r: ProcessingRequest) => shiftsActor.ask(r).mapTo[ShiftAssignments]
      val fixedPointsProvider = (r: ProcessingRequest) => fixedPointsActor.ask(r).mapTo[FixedPointAssignments]
      val movementsProvider = (r: ProcessingRequest) => staffMovementsActor.ask(r).mapTo[StaffMovements]

      val staffMinutesProducer = RunnableStaffing.staffMinutesFlow(shiftsProvider, fixedPointsProvider, movementsProvider, now)
      val (staffingUpdateRequestQueue, staffingUpdateKillSwitch) =
        startOptimisationGraph(staffMinutesProducer, persistentStaffingUpdateQueueActor, staffQueue, minuteLookups.staffMinutesRouterActor, "staffing")
      shiftsActor ! staffingUpdateRequestQueue
      fixedPointsActor ! staffingUpdateRequestQueue
      staffMovementsActor ! staffingUpdateRequestQueue

      val delayUntilTomorrow = (SDate.now().getLocalNextMidnight.millisSinceEpoch - SDate.now().millisSinceEpoch) + MilliTimes.oneHourMillis
      log.info(s"Scheduling next day staff calculations to begin at ${delayUntilTomorrow / 1000}s -> ${SDate.now().addMillis(delayUntilTomorrow).toISOString}")

      val staffChecker = StaffMinutesChecker(now, staffingUpdateRequestQueue, params.forecastMaxDays, airportConfig)

      staffChecker.calculateForecastStaffMinutes()
      system.scheduler.scheduleAtFixedRate(delayUntilTomorrow.millis, 1.day)(() => staffChecker.calculateForecastStaffMinutes())

      egateBanksUpdatesActor ! AddUpdatesSubscriber(crunchRequestQueueActor)

      crunchManagerActor ! AddQueueCrunchSubscriber(crunchRequestQueueActor)

      if (params.recrunchOnStart)
        queueDaysToReCrunch(crunchRequestQueueActor, portDeskRecs.crunchOffsetMinutes, params.forecastMaxDays, now)

      (crunchRequestQueueActor, deskRecsRequestQueueActor, deploymentRequestQueueActor, deskRecsKillSwitch, deploymentsKillSwitch, staffingUpdateKillSwitch)
    }

  private def startOptimisationGraph[A, B <: WithTimeAccessor](minutesProducer: Flow[ProcessingRequest, MinutesContainer[A, B], NotUsed],
                                                               persistentQueueActor: ActorRef,
                                                               initialQueue: SortedSet[ProcessingRequest],
                                                               sinkActor: ActorRef,
                                                               graphName: String,
                                                              ): (ActorRef, UniqueKillSwitch) = {
    val graphSource = new SortedActorRefSource(persistentQueueActor, airportConfig.crunchOffsetMinutes, airportConfig.minutesToCrunch, initialQueue, graphName)
    val (requestQueueActor, deskRecsKillSwitch) =
      RunnableOptimisation.createGraph(graphSource, sinkActor, minutesProducer, graphName).run()
    (requestQueueActor, deskRecsKillSwitch)
  }

  private def enabledPredictionModelNames: Seq[String] = Seq(
    OffScheduleModelAndFeatures.targetName,
    ToChoxModelAndFeatures.targetName,
    WalkTimeModelAndFeatures.targetName,
    PaxCapModelAndFeatures.targetName,
  )

  def startCrunchSystem(initialPortState: Option[PortState],
                        initialForecastBaseArrivals: Option[SortedMap[UniqueArrival, Arrival]],
                        initialForecastArrivals: Option[SortedMap[UniqueArrival, Arrival]],
                        initialLiveBaseArrivals: Option[SortedMap[UniqueArrival, Arrival]],
                        initialLiveArrivals: Option[SortedMap[UniqueArrival, Arrival]],
                        refreshArrivalsOnStart: Boolean,
                        startDeskRecs: () => (ActorRef, ActorRef, ActorRef, UniqueKillSwitch, UniqueKillSwitch, UniqueKillSwitch),
                       ): CrunchSystem[typed.ActorRef[FeedTick]] = {
    val voyageManifestsLiveSource: Source[ManifestsFeedResponse, SourceQueueWithComplete[ManifestsFeedResponse]] = Source.queue[ManifestsFeedResponse](1, OverflowStrategy.backpressure)
    val flushArrivalsSource: Source[Boolean, SourceQueueWithComplete[Boolean]] = Source.queue[Boolean](100, OverflowStrategy.backpressure)
    val arrivalAdjustments: ArrivalsAdjustmentsLike = ArrivalsAdjustments.adjustmentsForPort(airportConfig.portCode)
    val addArrivalPredictions: ArrivalsDiff => Future[ArrivalsDiff] = if (airportConfig.useTimePredictions) {
      log.info(s"Flight predictions enabled")
      ArrivalPredictions(
        (a: Arrival) => Iterable(
          TerminalOrigin(a.Terminal.toString, a.Origin.iata),
          TerminalCarrier(a.Terminal.toString, a.CarrierCode.code),
          PredictionModelActor.Terminal(a.Terminal.toString),
        ),
        Flight().getModels(enabledPredictionModelNames),
        Map(
          OffScheduleModelAndFeatures.targetName -> 45,
          ToChoxModelAndFeatures.targetName -> 20,
          WalkTimeModelAndFeatures.targetName -> 30 * 60,
          PaxCapModelAndFeatures.targetName -> 100,
        ),
        15
      ).addPredictions
    } else {
      log.info(s"Touchdown predictions disabled. Using noop lookup")
      diff => Future.successful(diff)
    }

    CrunchSystem(CrunchProps(
      airportConfig = airportConfig,
      portStateActor = portStateActor,
      flightsActor = flightsRouterActor,
      maxDaysToCrunch = params.forecastMaxDays,
      expireAfterMillis = DrtStaticParameters.expireAfterMillis,
      actors = Map(
        "shifts" -> shiftsActor,
        "fixed-points" -> fixedPointsActor,
        "staff-movements" -> staffMovementsActor,
        "forecast-base-arrivals" -> forecastBaseArrivalsActor,
        "forecast-arrivals" -> forecastArrivalsActor,
        "live-base-arrivals" -> liveBaseArrivalsActor,
        "live-arrivals" -> liveArrivalsActor,
        "aggregated-arrivals" -> aggregatedArrivalsActor,
        "crunch-queue" -> persistentCrunchQueueActor,
        "deployment-queue" -> persistentDeploymentQueueActor,
      ),
      useNationalityBasedProcessingTimes = params.useNationalityBasedProcessingTimes,
      manifestsLiveSource = voyageManifestsLiveSource,
      voyageManifestsActor = manifestsRouterActor,
      initialPortState = initialPortState,
      initialForecastBaseArrivals = initialForecastBaseArrivals.getOrElse(SortedMap()),
      initialForecastArrivals = initialForecastArrivals.getOrElse(SortedMap()),
      initialLiveBaseArrivals = initialLiveBaseArrivals.getOrElse(SortedMap()),
      initialLiveArrivals = initialLiveArrivals.getOrElse(SortedMap()),
      arrivalsForecastBaseFeed = baseArrivalsSource(maybeAclFeed),
      arrivalsForecastFeed = forecastArrivalsSource(airportConfig.portCode),
      arrivalsLiveBaseFeed = liveBaseArrivalsSource(airportConfig.portCode),
      arrivalsLiveFeed = liveArrivalsSource(airportConfig.portCode),
      passengerAdjustments = PaxDeltas.applyAdjustmentsToArrivals(passengersActorProvider, aclPaxAdjustmentDays),
      initialShifts = initialState[ShiftAssignments](shiftsActor).getOrElse(ShiftAssignments(Seq())),
      initialFixedPoints = initialState[FixedPointAssignments](fixedPointsActor).getOrElse(FixedPointAssignments(Seq())),
      initialStaffMovements = initialState[StaffMovements](staffMovementsActor).map(_.movements).getOrElse(Seq[StaffMovement]()),
      refreshArrivalsOnStart = refreshArrivalsOnStart,
      optimiser = optimiser,
      startDeskRecs = startDeskRecs,
      arrivalsAdjustments = arrivalAdjustments,
      flushArrivalsSource = flushArrivalsSource,
      addArrivalPredictions = addArrivalPredictions,
      setPcpTimes = setPcpTimes,
      flushArrivalsOnStart = params.flushArrivalsOnStart,
    ))
  }

  private def arrivalsNoOp: Feed[typed.ActorRef[FeedTick]] = {
    Feed(Feed.actorRefSource
      .map { _ =>
        system.log.info(s"No op arrivals feed")
        ArrivalsFeedSuccess(Flights(Seq()), SDate.now())
      }, 100.days, 100.days)
  }

  private def baseArrivalsSource(maybeAclFeed: Option[AclFeed]): Feed[typed.ActorRef[FeedTick]] = maybeAclFeed match {
    case None => arrivalsNoOp
    case Some(aclFeed) =>
      val initialDelay =
        if (config.get[Boolean]("acl.check-on-startup")) 10.seconds
        else AclFeed.delayUntilNextAclCheck(now(), 18) + (Math.random() * 60).minutes
      val frequency = 1.day

      log.info(s"Checking ACL every ${frequency.toHours} hours. Initial delay: ${initialDelay.toMinutes} minutes")

      Feed(Feed.actorRefSource.map { _ =>
        system.log.info(s"Requesting ACL feed")
        aclFeed.requestArrivals
      }, initialDelay, frequency)
  }

  def liveBaseArrivalsSource(portCode: PortCode): Feed[typed.ActorRef[FeedTick]] = {
    if (config.get[Boolean]("feature-flags.use-cirium-feed")) {
      log.info(s"Using Cirium Live Base Feed")
      Feed(CiriumFeed(config.get[String]("feeds.cirium.host"), portCode).source(Feed.actorRefSource), 5.seconds, 30.seconds)
    }
    else {
      log.info(s"Using Noop Base Live Feed")
      arrivalsNoOp
    }
  }

  def liveArrivalsSource(portCode: PortCode): Feed[typed.ActorRef[FeedTick]] =
    portCode.iata match {
      case "LHR" =>
        val host = config.get[String]("feeds.lhr.sftp.live.host")
        val username = config.get[String]("feeds.lhr.sftp.live.username")
        val password = config.get[String]("feeds.lhr.sftp.live.password")
        val contentProvider = () => LhrSftpLiveContentProvider(host, username, password).latestContent
        Feed(LHRFlightFeed(contentProvider, Feed.actorRefSource), 5.seconds, 1.minute)
      case "LGW" =>
        val lgwNamespace = params.maybeLGWNamespace.getOrElse(throw new Exception("Missing LGW Azure Namespace parameter"))
        val lgwSasToKey = params.maybeLGWSASToKey.getOrElse(throw new Exception("Missing LGW SAS Key for To Queue"))
        val lgwServiceBusUri = params.maybeLGWServiceBusUri.getOrElse(throw new Exception("Missing LGW Service Bus Uri"))
        val azureClient = LGWAzureClient(LGWFeed.serviceBusClient(lgwNamespace, lgwSasToKey, lgwServiceBusUri))
        Feed(LGWFeed(azureClient)(system).source(Feed.actorRefSource), 5.seconds, 100.milliseconds)
      case "BHX" if params.bhxIataEndPointUrl.nonEmpty =>
        Feed(BHXFeed(BHXClient(params.bhxIataUsername, params.bhxIataEndPointUrl), Feed.actorRefSource), 5.seconds, 80.seconds)
      case "LCY" if params.lcyLiveEndPointUrl.nonEmpty =>
        Feed(LCYFeed(LCYClient(ProdHttpClient(), params.lcyLiveUsername, params.lcyLiveEndPointUrl, params.lcyLiveUsername, params.lcyLivePassword), Feed.actorRefSource), 5.seconds, 80.seconds)
      case "LTN" =>
        val url = params.maybeLtnLiveFeedUrl.getOrElse(throw new Exception("Missing live feed url"))
        val username = params.maybeLtnLiveFeedUsername.getOrElse(throw new Exception("Missing live feed username"))
        val password = params.maybeLtnLiveFeedPassword.getOrElse(throw new Exception("Missing live feed password"))
        val token = params.maybeLtnLiveFeedToken.getOrElse(throw new Exception("Missing live feed token"))
        val timeZone = params.maybeLtnLiveFeedTimeZone match {
          case Some(tz) => DateTimeZone.forID(tz)
          case None => DateTimeZone.UTC
        }
        val requester = LtnFeedRequester(url, token, username, password)
        Feed(LtnLiveFeed(requester, timeZone).source(Feed.actorRefSource), 5.seconds, 30 seconds)
      case "MAN" | "STN" | "EMA" =>
        if (config.get[Boolean]("feeds.mag.use-legacy")) {
          log.info(s"Using legacy MAG live feed")
          Feed(createLiveChromaFlightFeed(ChromaLive).chromaVanillaFlights(Feed.actorRefSource), 5.seconds, 30 seconds)
        } else {
          log.info(s"Using new MAG live feed")
          val maybeFeed = for {
            privateKey <- config.getOptional[String]("feeds.mag.private-key")
            claimIss <- config.getOptional[String]("feeds.mag.claim.iss")
            claimRole <- config.getOptional[String]("feeds.mag.claim.role")
            claimSub <- config.getOptional[String]("feeds.mag.claim.sub")
          } yield {
            MagFeed(privateKey, claimIss, claimRole, claimSub, now, airportConfig.portCode, ProdFeedRequester).source(Feed.actorRefSource)
          }
          maybeFeed
            .map(f => Feed(f, 5.seconds, 30.seconds))
            .getOrElse({
              log.error(s"No feed credentials supplied. Live feed can't be set up")
              arrivalsNoOp
            })
        }
      case "GLA" =>
        val liveUrl = params.maybeGlaLiveUrl.getOrElse(throw new Exception("Missing GLA Live Feed Url"))
        val livePassword = params.maybeGlaLivePassword.getOrElse(throw new Exception("Missing GLA Live Feed Password"))
        val liveToken = params.maybeGlaLiveToken.getOrElse(throw new Exception("Missing GLA Live Feed Token"))
        val liveUsername = params.maybeGlaLiveUsername.getOrElse(throw new Exception("Missing GLA Live Feed Username"))
        Feed(GlaFeed(liveUrl, liveToken, livePassword, liveUsername, ProdGlaFeedRequester).source(Feed.actorRefSource), 5.seconds, 60.seconds)
      case "PIK" | "HUY" | "INV" | "NQY" | "NWI" | "SEN" =>
        Feed(CiriumFeed(config.get[String]("feeds.cirium.host"), portCode).source(Feed.actorRefSource), 5.seconds, 30 seconds)
      case "EDI" =>
        Feed(EdiFeed(EdiClient(config.get[String]("feeds.edi.endPointUrl"), config.get[String]("feeds.edi.subscriberId"), ProdHttpClient())).ediLiveFeedSource(Feed.actorRefSource), 5.seconds, 1.minute)
      case _ =>
        arrivalsNoOp
    }

  def forecastArrivalsSource(portCode: PortCode): Feed[typed.ActorRef[FeedTick]] =
    portCode match {
      case PortCode("LGW") =>
        val interval = system.settings.config.getString("feeds.lgw.forecast.interval-minutes").toInt.minutes
        val initialDelay = system.settings.config.getString("feeds.lgw.forecast.initial-delay-seconds").toInt.seconds
        Feed(LgwForecastFeed(), initialDelay, interval)
      case PortCode("LHR") | PortCode("STN") =>
        Feed(createArrivalFeed(Feed.actorRefSource), 5.seconds, 5.seconds)
      case PortCode("BHX") =>
        Feed(BHXForecastFeedLegacy(params.maybeBhxSoapEndPointUrl.getOrElse(throw new Exception("Missing BHX feed URL")), Feed.actorRefSource), 5.seconds, 30.seconds)
      case PortCode("EDI") =>
        Feed(EdiFeed(EdiClient(config.get[String]("feeds.edi.endPointUrl"), config.get[String]("feeds.edi.subscriberId"), ProdHttpClient())).ediForecastFeedSource(Feed.actorRefSource), 5.seconds, 10.minutes)
      case _ => system.log.info(s"No Forecast Feed defined.")
        arrivalsNoOp
    }

  private def createLiveChromaFlightFeed(feedType: ChromaFeedType): ChromaLiveFeed = {
    ChromaLiveFeed(new ChromaFetcher[ChromaLiveFlight](feedType, ChromaFlightMarshallers.live) with ProdSendAndReceive)
  }

  private def createArrivalFeed(source: Source[FeedTick, typed.ActorRef[FeedTick]]): Source[ArrivalsFeedResponse, typed.ActorRef[FeedTick]] = {
    implicit val timeout: Timeout = new Timeout(10 seconds)
    val arrivalFeed = ManualUploadArrivalFeed(arrivalsImportActor)
    source.mapAsync(1)(_ => arrivalFeed.requestFeed)
  }

  def initialState[A](askableActor: ActorRef): Option[A] = Await.result(initialStateFuture[A](askableActor), 2 minutes)

  def initialFlightsPortState(actor: ActorRef, forecastMaxDays: Int): Future[Option[PortState]] = {
    val from = now().getLocalLastMidnight.addDays(-1)
    val to = from.addDays(forecastMaxDays)
    val request = GetFlights(from.millisSinceEpoch, to.millisSinceEpoch)
    FlightsRouterActor.runAndCombine(actor
      .ask(request)(new Timeout(15 seconds)).mapTo[Source[(UtcDate, FlightsWithSplits), NotUsed]])
      .map { fws =>
        Option(PortState(fws.flights.values, Iterable(), Iterable()))
      }
  }

  def initialStateFuture[A](askableActor: ActorRef): Future[Option[A]] = {
    val actorPath = askableActor.actorRef.path
    queryActorWithRetry[A](askableActor, GetState)
      .map {
        case Some(state) if state.isInstanceOf[A] =>
          log.debug(s"Got initial state (Some(${state.getClass})) from $actorPath")
          Option(state)
        case None =>
          log.warn(s"Got no state (None) from $actorPath")
          None
      }
      .recoverWith {
        case t =>
          log.error(s"Failed to get response from $askableActor", t)
          Future(None)
      }
  }

  private def queryActorWithRetry[A](actor: ActorRef, toAsk: Any): Future[Option[A]] = {
    val future = actor.ask(toAsk)(new Timeout(2 minutes)).map {
      case Some(state: A) if state.isInstanceOf[A] => Option(state)
      case state: A if !state.isInstanceOf[Option[A]] => Option(state)
      case _ => None
    }

    implicit val scheduler: Scheduler = system.scheduler
    Retry.retry(future, RetryDelays.fibonacci, 3, 5 seconds)
  }

  def getFeedStatus: Future[Seq[FeedSourceStatuses]] = Source(feedActorsForPort)
    .mapAsync(1) {
      case (_, actor) => queryActorWithRetry[FeedSourceStatuses](actor, GetFeedStatuses)
    }
    .collect { case Some(fs) => fs }
    .withAttributes(StreamSupervision.resumeStrategyWithLog("getFeedStatus"))
    .runWith(Sink.seq)

  def setSubscribers(crunchInputs: CrunchSystem[typed.ActorRef[Feed.FeedTick]]): Unit = {
    flightsRouterActor ! AddUpdatesSubscriber(crunchInputs.crunchRequestActor)
    manifestsRouterActor ! AddUpdatesSubscriber(crunchInputs.crunchRequestActor)
    queueLoadsRouterActor ! AddUpdatesSubscriber(crunchInputs.deskRecsRequestActor)
    queueLoadsRouterActor ! AddUpdatesSubscriber(crunchInputs.deploymentRequestActor)
    staffRouterActor ! AddUpdatesSubscriber(crunchInputs.deploymentRequestActor)
  }

}
