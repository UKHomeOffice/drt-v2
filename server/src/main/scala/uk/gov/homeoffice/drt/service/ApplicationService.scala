package uk.gov.homeoffice.drt.service

import actors.CrunchManagerActor.{AddQueueCrunchSubscriber, AddRecalculateArrivalsSubscriber}
import actors._
import actors.daily.PassengersActor
import actors.persistent._
import actors.routing.FlightsRouterActor.{AddHistoricPaxRequestActor, AddHistoricSplitsRequestActor}
import akka.actor.{ActorRef, ActorSystem, Props, typed}
import akka.pattern.{StatusReply, ask}
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.stream.{CompletionStrategy, Materializer, OverflowStrategy, UniqueKillSwitch}
import akka.util.Timeout
import akka.{Done, NotUsed}
import drt.server.feeds.Feed.FeedTick
import drt.server.feeds.FeedPoller.{AdhocCheck, Enable}
import drt.server.feeds._
import drt.server.feeds.api.{ApiFeedImpl, DbManifestArrivalKeys, DbManifestProcessor}
import drt.shared.CrunchApi.{CrunchMinute, MillisSinceEpoch, MinutesContainer, StaffMinute}
import drt.shared.FlightsApi.PaxForArrivals
import drt.shared._
import manifests.passengers.{BestAvailableManifest, ManifestLike, ManifestPaxCount}
import manifests.queues.SplitsCalculator
import manifests.{ManifestLookupLike, UniqueArrivalKey}
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.parsing.VoyageManifestParser
import play.api.Configuration
import providers.{FlightsProvider, ManifestsProvider, MinutesProvider}
import queueus._
import services.PcpArrival.pcpFrom
import services.arrivals.MergeArrivals.FeedArrivalSet
import services.arrivals.{ArrivalsAdjustments, MergeArrivals, RunnableHistoricPax, RunnableHistoricSplits}
import services.crunch.CrunchSystem.paxTypeQueueAllocator
import services.crunch.desklimits.flexed.FlexedTerminalDeskLimitsFromAvailableStaff
import services.crunch.desklimits.{PortDeskLimits, TerminalDeskLimitsLike}
import services.crunch.deskrecs._
import services.crunch.staffing.RunnableStaffing
import services.crunch.{CrunchProps, CrunchSystem}
import services.graphstages.FlightFilter
import services.liveviews.PassengersLiveView
import services.metrics.ApiValidityReporter
import services.prediction.ArrivalPredictions
import services.staffing.StaffMinutesChecker
import services.{OptimiserWithFlexibleProcessors, PaxDeltas, TryCrunchWholePax}
import slickdb.Tables
import uk.gov.homeoffice.drt.actor.PredictionModelActor.{TerminalCarrier, TerminalOrigin}
import uk.gov.homeoffice.drt.actor.commands.Commands.{AddUpdatesSubscriber, GetState}
import uk.gov.homeoffice.drt.actor.commands.{CrunchRequest, MergeArrivalsRequest, ProcessingRequest}
import uk.gov.homeoffice.drt.actor.serialisation.{ConfigDeserialiser, ConfigSerialiser, EmptyConfig}
import uk.gov.homeoffice.drt.actor.{ConfigActor, PredictionModelActor, WalkTimeProvider}
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.crunchsystem.{ActorsServiceLike, PersistentStateActors}
import uk.gov.homeoffice.drt.db.AggregateDb
import uk.gov.homeoffice.drt.egates.{EgateBank, EgateBanksUpdate, EgateBanksUpdates, PortEgateBanksUpdates}
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports._
import uk.gov.homeoffice.drt.ports.config.slas.SlaConfigs
import uk.gov.homeoffice.drt.prediction.arrival.{OffScheduleModelAndFeatures, PaxCapModelAndFeaturesV2, ToChoxModelAndFeatures, WalkTimeModelAndFeatures}
import uk.gov.homeoffice.drt.redlist.RedListUpdates
import uk.gov.homeoffice.drt.services.Slas
import uk.gov.homeoffice.drt.time.MilliTimes.oneSecondMillis
import uk.gov.homeoffice.drt.time._

import javax.inject.Singleton
import scala.collection.SortedSet
import scala.collection.immutable.SortedMap
import scala.concurrent.duration.{DurationInt, DurationLong, FiniteDuration}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}

@Singleton
case class ApplicationService(journalType: StreamingJournalLike,
                              airportConfig: AirportConfig,
                              now: () => SDateLike,
                              params: DrtParameters,
                              config: Configuration,
                              db: Tables,
                              feedService: FeedService,
                              manifestLookups: ManifestLookupsLike,
                              manifestLookupService: ManifestLookupLike,
                              minuteLookups: MinuteLookupsLike,
                              actorService: ActorsServiceLike,
                              persistentStateActors: PersistentStateActors,
                              requestAndTerminateActor: ActorRef,
                              splitsCalculator: SplitsCalculator,
                             )
                             (implicit system: ActorSystem, ec: ExecutionContext, mat: Materializer, timeout: Timeout) {
  val log: Logger = LoggerFactory.getLogger(getClass)
  private val walkTimeProvider: (Terminal, String, String) => Option[Int] = WalkTimeProvider(params.gateWalkTimesFilePath, params.standWalkTimesFilePath)

  val maxDaysToConsider: Int = 14
  val passengersActorProvider: () => ActorRef = () => system.actorOf(Props(new PassengersActor(maxDaysToConsider, aclPaxAdjustmentDays, now)))

  private val aclPaxAdjustmentDays: Int = config.get[Int]("acl.adjustment.number-of-days-in-average")

  val optimiser: TryCrunchWholePax = OptimiserWithFlexibleProcessors.crunchWholePax

  private val crunchRequestProvider: LocalDate => CrunchRequest =
    date => CrunchRequest(date, airportConfig.crunchOffsetMinutes, airportConfig.minutesToCrunch)

  val slasActor: ActorRef = system.actorOf(Props(new ConfigActor[Map[Queue, Int], SlaConfigs]("slas", now, crunchRequestProvider, maxDaysToConsider)(
    emptyProvider = new EmptyConfig[Map[Queue, Int], SlaConfigs] {
      override def empty: SlaConfigs = SlaConfigs(SortedMap(SDate("2014-09-01T00:00").millisSinceEpoch -> airportConfig.slaByQueue))
    },
    serialiser = ConfigSerialiser.slaConfigsSerialiser,
    deserialiser = ConfigDeserialiser.slaConfigsDeserialiser,
  )))

  val portDeskRecs: PortDesksAndWaitsProviderLike =
    PortDesksAndWaitsProvider(airportConfig, optimiser, FlightFilter.forPortConfig(airportConfig), feedService.paxFeedSourceOrder, Slas.slaProvider(slasActor))

  val paxTypeQueueAllocation: PaxTypeQueueAllocation = paxTypeQueueAllocator(airportConfig)

  private def walkTimeProviderWithFallback(arrival: Arrival): MillisSinceEpoch = {
    val defaultWalkTimeMillis = airportConfig.defaultWalkTimeMillis.getOrElse(arrival.Terminal, 300000L)
    walkTimeProvider(arrival.Terminal, arrival.Gate.getOrElse(""), arrival.Stand.getOrElse(""))
      .map(_.toLong * oneSecondMillis)
      .getOrElse(defaultWalkTimeMillis)
  }

  private val pcpArrivalTimeCalculator: Arrival => MilliDate =
    pcpFrom(airportConfig.firstPaxOffMillis, walkTimeProviderWithFallback)

  val setPcpTimes: Seq[Arrival] => Future[Seq[Arrival]] = arrivals =>
    Future.successful(arrivals.map(a => a.copy(PcpTime = Option(pcpArrivalTimeCalculator(a).millisSinceEpoch))))

  val manifestsRouterActorReadOnly: ActorRef =
    system.actorOf(
      Props(new ManifestRouterActor(manifestLookups.manifestsByDayLookup, manifestLookups.updateManifests)),
      name = "voyage-manifests-router-actor-read-only")

  val manifestsProvider: (UtcDate, UtcDate) => Source[(UtcDate, VoyageManifestParser.VoyageManifests), NotUsed] =
    ManifestsProvider(manifestsRouterActorReadOnly)

  private lazy val updateLivePaxView = PassengersLiveView.updateLiveView(airportConfig.portCode, now, db)
  lazy val populateLivePaxViewForDate: UtcDate => Future[StatusReply[Done]] =
    PassengersLiveView.populatePaxForDate(minuteLookups.queueMinutesRouterActor, updateLivePaxView)

  def initialState[A](askableActor: ActorRef): Option[A] = Await.result(initialStateFuture[A](askableActor), 2.minutes)

  private def initialStateFuture[A](askableActor: ActorRef): Future[Option[A]] = {
    val actorPath = askableActor.actorRef.path
    feedService.queryActorWithRetry[A](askableActor, GetState)
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

  private val defaultEgates: Map[Terminal, EgateBanksUpdates] = airportConfig.eGateBankSizes.view.mapValues { banks =>
    val effectiveFrom = SDate("2020-01-01T00:00").millisSinceEpoch
    EgateBanksUpdates(List(EgateBanksUpdate(effectiveFrom, EgateBank.fromAirportConfig(banks))))
  }.toMap

  val alertsActor: ActorRef = system.actorOf(Props(new AlertsActor(now)), "alerts-actor")
  val redListUpdatesActor: ActorRef = system.actorOf(Props(new RedListUpdatesActor(now)), "red-list-updates-actor")
  val egateBanksUpdatesActor: ActorRef = system.actorOf(Props(new EgateBanksUpdatesActor(now,
    defaultEgates,
    airportConfig.crunchOffsetMinutes,
    airportConfig.minutesToCrunch,
    params.forecastMaxDays)), "egate-banks-updates-actor")

  lazy val flightsProvider: FlightsProvider = FlightsProvider(actorService.flightsRouterActor)

  lazy val terminalFlightsProvider: Terminal => (UtcDate, UtcDate) => Source[(UtcDate, Seq[ApiFlightWithSplits]), NotUsed] =
    flightsProvider.singleTerminal
  lazy val crunchMinutesProvider: Terminal => (UtcDate, UtcDate) => Source[(UtcDate, Seq[CrunchMinute]), NotUsed] =
    MinutesProvider.singleTerminal(actorService.queuesRouterActor)
  lazy val staffMinutesProvider: Terminal => (UtcDate, UtcDate) => Source[(UtcDate, Seq[StaffMinute]), NotUsed] =
    MinutesProvider.singleTerminal(actorService.staffRouterActor)

  private def startQueuedRequestProcessingGraph[A](minutesProducer: Flow[ProcessingRequest, A, NotUsed],
                                                   persistentQueueActor: ActorRef,
                                                   initialQueue: SortedSet[ProcessingRequest],
                                                   sinkActor: ActorRef,
                                                   graphName: String,
                                                   processingRequest: MillisSinceEpoch => ProcessingRequest,
                                                  ): (ActorRef, UniqueKillSwitch) = {
    val graphSource = new SortedActorRefSource(persistentQueueActor, processingRequest, initialQueue, graphName)
    QueuedRequestProcessing.createGraph(graphSource, sinkActor, minutesProducer, graphName).run()
  }

  private def enabledPredictionModelNamesWithUpperThresholds = Map(
    OffScheduleModelAndFeatures.targetName -> 45,
    ToChoxModelAndFeatures.targetName -> 20,
    WalkTimeModelAndFeatures.targetName -> 30 * 60,
    PaxCapModelAndFeaturesV2.targetName -> 100,
  )

  private val egatesProvider: () => Future[PortEgateBanksUpdates] = () => egateBanksUpdatesActor.ask(GetState).mapTo[PortEgateBanksUpdates]

  val crunchManagerActor: ActorRef = system.actorOf(Props(new CrunchManagerActor), name = "crunch-manager-actor")
  private val refetchApiData: Boolean = config.get[Boolean]("crunch.manifests.refetch-live-api")

  val addArrivalPredictions: ArrivalsDiff => Future[ArrivalsDiff] =
    ArrivalPredictions(
      (a: Arrival) => Iterable(
        TerminalOrigin(a.Terminal.toString, a.Origin.iata),
        TerminalCarrier(a.Terminal.toString, a.CarrierCode.code),
        PredictionModelActor.Terminal(a.Terminal.toString),
      ),
      feedService.flightModelPersistence.getModels(enabledPredictionModelNamesWithUpperThresholds.keys.toSeq),
      enabledPredictionModelNamesWithUpperThresholds,
      minimumImprovementPctThreshold = 15
    ).addPredictions

  val startUpdateGraphs: (
    PersistentStateActors,
      SortedSet[ProcessingRequest],
      SortedSet[ProcessingRequest],
      SortedSet[ProcessingRequest],
      SortedSet[ProcessingRequest],
      SortedSet[ProcessingRequest],
    ) => () => (ActorRef, ActorRef, ActorRef, ActorRef, UniqueKillSwitch, UniqueKillSwitch, UniqueKillSwitch, UniqueKillSwitch) =
    (actors, mergeArrivalsQueue, crunchQueue, deskRecsQueue, deploymentQueue, staffQueue) => () => {
      val staffToDeskLimits = PortDeskLimits.flexedByAvailableStaff(airportConfig, terminalEgatesProvider) _

      implicit val timeout: Timeout = new Timeout(10.seconds)

      //      val manifestCacheLookup = RouteHistoricManifestActor.manifestCacheLookup(airportConfig.portCode, now, system, timeout, ec)
      //      val manifestCacheStore = RouteHistoricManifestActor.manifestCacheStore(airportConfig.portCode, now, system, timeout, ec)

      if (config.getOptional[Boolean]("feature-flags.populate-historic-pax").getOrElse(false))
        PassengersLiveView.populateHistoricPax(populateLivePaxViewForDate)

      val crunchRequest: MillisSinceEpoch => CrunchRequest =
        (millis: MillisSinceEpoch) => CrunchRequest(millis, airportConfig.crunchOffsetMinutes, airportConfig.minutesToCrunch)

      val mergeArrivalRequest: MillisSinceEpoch => MergeArrivalsRequest =
        (millis: MillisSinceEpoch) => MergeArrivalsRequest(SDate(millis).toUtcDate)

      val historicSplitsActor = RunnableHistoricSplits(
        airportConfig.portCode,
        actorService.flightsRouterActor,
        splitsCalculator.splitsForManifest,
        manifestLookupService.maybeBestAvailableManifest)

      val historicPaxActor = RunnableHistoricPax(airportConfig.portCode, actorService.flightsRouterActor, manifestLookupService.maybeHistoricManifestPax)

      val crunchRequestQueueActor: ActorRef = startPaxLoads(actors.crunchQueueActor, crunchQueue, crunchRequest)

      val (mergeArrivalsRequestQueueActor: ActorRef, mergeArrivalsKillSwitch: UniqueKillSwitch) =
        RunnableMergedArrivals.startMergeArrivals(
          portCode = airportConfig.portCode,
          flightsRouterActor = actorService.flightsRouterActor,
          aggregatedArrivalsActor = actors.aggregatedArrivalsActor,
          mergeArrivalsQueueActor = actors.mergeArrivalsQueueActor,
          feedArrivalsForDate = ProdFeedService.arrivalFeedProvidersInOrder(feedService.activeFeedActorsWithPrimary),
          mergeArrivalsQueue = mergeArrivalsQueue,
          mergeArrivalRequest = mergeArrivalRequest,
          setPcpTimes = setPcpTimes,
          addArrivalPredictions = addArrivalPredictions,
        )

      val (deskRecsRequestQueueActor: ActorRef, deskRecsKillSwitch: UniqueKillSwitch) = startDeskRecs(actors.deskRecsQueueActor, deskRecsQueue, crunchRequest)

      val (deploymentRequestQueueActor: ActorRef, deploymentsKillSwitch: UniqueKillSwitch) = startDeployments(actors.deploymentQueueActor, deploymentQueue, staffToDeskLimits, crunchRequest)

      val (staffingUpdateRequestQueue: ActorRef, staffingUpdateKillSwitch: UniqueKillSwitch) = startStaffing(actors.staffingQueueActor, staffQueue, crunchRequest)

      actorService.liveShiftsReadActor ! AddUpdatesSubscriber(staffingUpdateRequestQueue)
      actorService.liveFixedPointsReadActor ! AddUpdatesSubscriber(staffingUpdateRequestQueue)
      actorService.liveStaffMovementsReadActor ! AddUpdatesSubscriber(staffingUpdateRequestQueue)

      val delayUntilTomorrow = (SDate.now().getLocalNextMidnight.millisSinceEpoch - SDate.now().millisSinceEpoch) + MilliTimes.oneHourMillis
      log.info(s"Scheduling next day staff calculations to begin at ${delayUntilTomorrow / 1000}s -> ${SDate.now().addMillis(delayUntilTomorrow).toISOString}")

      val staffChecker = StaffMinutesChecker(now, staffingUpdateRequestQueue, params.forecastMaxDays, airportConfig)

      staffChecker.calculateForecastStaffMinutes()
      system.scheduler.scheduleAtFixedRate(delayUntilTomorrow.millis, 1.day)(() => staffChecker.calculateForecastStaffMinutes())

      egateBanksUpdatesActor ! AddUpdatesSubscriber(crunchRequestQueueActor)
      crunchManagerActor ! AddQueueCrunchSubscriber(crunchRequestQueueActor)
      crunchManagerActor ! AddRecalculateArrivalsSubscriber(mergeArrivalsRequestQueueActor)
      actorService.flightsRouterActor ! AddHistoricSplitsRequestActor(historicSplitsActor)
      actorService.flightsRouterActor ! AddHistoricPaxRequestActor(historicPaxActor)

      feedService.forecastBaseFeedArrivalsActor ! AddUpdatesSubscriber(mergeArrivalsRequestQueueActor)
      feedService.forecastFeedArrivalsActor ! AddUpdatesSubscriber(mergeArrivalsRequestQueueActor)
      feedService.liveBaseFeedArrivalsActor ! AddUpdatesSubscriber(mergeArrivalsRequestQueueActor)
      feedService.liveFeedArrivalsActor ! AddUpdatesSubscriber(mergeArrivalsRequestQueueActor)

      (mergeArrivalsRequestQueueActor, crunchRequestQueueActor, deskRecsRequestQueueActor, deploymentRequestQueueActor,
        mergeArrivalsKillSwitch, deskRecsKillSwitch, deploymentsKillSwitch, staffingUpdateKillSwitch)
    }

  private def startPaxLoads(crunchQueueActor: ActorRef, crunchQueue: SortedSet[ProcessingRequest], crunchRequest: MillisSinceEpoch => CrunchRequest) = {
    val passengerLoadsFlow: Flow[ProcessingRequest, MinutesContainer[CrunchApi.PassengersMinute, TQM], NotUsed] =
      DynamicRunnablePassengerLoads.crunchRequestsToQueueMinutes(
        arrivalsProvider = OptimisationProviders.flightsWithSplitsProvider(actorService.portStateActor),
        portDesksAndWaitsProvider = portDeskRecs,
        redListUpdatesProvider = () => redListUpdatesActor.ask(GetState).mapTo[RedListUpdates],
        DynamicQueueStatusProvider(airportConfig, egatesProvider),
        airportConfig.queuesByTerminal,
        updateLiveView = updateLivePaxView,
        paxFeedSourceOrder = feedService.paxFeedSourceOrder,
        terminalSplits = splitsCalculator.terminalSplits,
      )

    val (crunchRequestQueueActor, _: UniqueKillSwitch) =
      startQueuedRequestProcessingGraph(
        passengerLoadsFlow,
        crunchQueueActor,
        crunchQueue,
        minuteLookups.queueLoadsMinutesActor,
        "passenger-loads",
        crunchRequest,
      )
    crunchRequestQueueActor
  }

  private def startStaffing(staffingQueueActor: ActorRef, staffQueue: SortedSet[ProcessingRequest], crunchRequest: MillisSinceEpoch => CrunchRequest) = {
    val shiftsProvider = (r: ProcessingRequest) => actorService.liveShiftsReadActor.ask(r).mapTo[ShiftAssignments]
    val fixedPointsProvider = (r: ProcessingRequest) => actorService.liveFixedPointsReadActor.ask(r).mapTo[FixedPointAssignments]
    val movementsProvider = (r: ProcessingRequest) => actorService.liveStaffMovementsReadActor.ask(r).mapTo[StaffMovements]

    val staffMinutesFlow = RunnableStaffing.staffMinutesFlow(shiftsProvider, fixedPointsProvider, movementsProvider, now)

    val (staffingUpdateRequestQueue, staffingUpdateKillSwitch) =
      startQueuedRequestProcessingGraph(
        staffMinutesFlow,
        staffingQueueActor,
        staffQueue,
        minuteLookups.staffMinutesRouterActor,
        "staffing",
        crunchRequest,
      )
    (staffingUpdateRequestQueue, staffingUpdateKillSwitch)
  }

  private def startDeployments(deploymentQueueActor: ActorRef, deploymentQueue: SortedSet[ProcessingRequest], staffToDeskLimits: Map[Terminal, List[Int]] => Map[Terminal, FlexedTerminalDeskLimitsFromAvailableStaff], crunchRequest: MillisSinceEpoch => CrunchRequest) = {
    val deploymentsFlow = DynamicRunnableDeployments.crunchRequestsToDeployments(
      loadsProvider = OptimisationProviders.passengersProvider(minuteLookups.queueLoadsMinutesActor),
      staffProvider = OptimisationProviders.staffMinutesProvider(minuteLookups.staffMinutesRouterActor, airportConfig.terminals),
      staffToDeskLimits = staffToDeskLimits,
      loadsToQueueMinutes = portDeskRecs.loadsToSimulations
    )

    val (deploymentRequestQueueActor, deploymentsKillSwitch) =
      startQueuedRequestProcessingGraph(
        deploymentsFlow,
        deploymentQueueActor,
        deploymentQueue,
        minuteLookups.queueMinutesRouterActor,
        "deployments",
        crunchRequest,
      )
    (deploymentRequestQueueActor, deploymentsKillSwitch)
  }

  private def startDeskRecs(deskRecsQueueActor: ActorRef, deskRecsQueue: SortedSet[ProcessingRequest], crunchRequest: MillisSinceEpoch => CrunchRequest) = {
    val deskRecsFlow = DynamicRunnableDeskRecs.crunchRequestsToDeskRecs(
      loadsProvider = OptimisationProviders.passengersProvider(minuteLookups.queueLoadsMinutesActor),
      maxDesksProviders = deskLimitsProviders,
      loadsToQueueMinutes = portDeskRecs.loadsToDesks,
    )

    val (deskRecsRequestQueueActor, deskRecsKillSwitch) =
      startQueuedRequestProcessingGraph(
        deskRecsFlow,
        deskRecsQueueActor,
        deskRecsQueue,
        minuteLookups.queueMinutesRouterActor,
        "desk-recs",
        crunchRequest,
      )
    (deskRecsRequestQueueActor, deskRecsKillSwitch)
  }

  def setSubscribers(crunchInputs: CrunchSystem[typed.ActorRef[FeedTick]]): Unit = {
    actorService.flightsRouterActor ! AddUpdatesSubscriber(crunchInputs.crunchRequestQueueActor)
    actorService.queueLoadsRouterActor ! AddUpdatesSubscriber(crunchInputs.deskRecsRequestQueueActor)
    actorService.queueLoadsRouterActor ! AddUpdatesSubscriber(crunchInputs.deploymentRequestQueueActor)
    actorService.staffRouterActor ! AddUpdatesSubscriber(crunchInputs.deploymentRequestQueueActor)
    slasActor ! AddUpdatesSubscriber(crunchInputs.deskRecsRequestQueueActor)
  }

  val terminalEgatesProvider: Terminal => Future[EgateBanksUpdates] = EgateBanksUpdatesActor.terminalEgatesProvider(egateBanksUpdatesActor)

  val deskLimitsProviders: Map[Terminal, TerminalDeskLimitsLike] = if (config.get[Boolean]("crunch.flex-desks")) {
    PortDeskLimits.flexed(airportConfig, terminalEgatesProvider)
  }
  else
    PortDeskLimits.fixed(airportConfig, terminalEgatesProvider)

  def startCrunchSystem(actors: PersistentStateActors,
                        startUpdateGraphs: () => (ActorRef, ActorRef, ActorRef, ActorRef,
                          UniqueKillSwitch, UniqueKillSwitch, UniqueKillSwitch, UniqueKillSwitch),
                       ): CrunchSystem[typed.ActorRef[FeedTick]] = {
    CrunchSystem(CrunchProps(
      airportConfig = airportConfig,
      portStateActor = actorService.portStateActor,
      maxDaysToCrunch = params.forecastMaxDays,
      expireAfterMillis = DrtStaticParameters.expireAfterMillis,
      now = now,
      crunchActors = actors,
      feedActors = feedService.feedActors,
      updateFeedStatus = feedService.updateFeedStatus,
      arrivalsForecastBaseFeed = feedService.baseArrivalsSource(feedService.maybeAclFeed),
      arrivalsForecastFeed = feedService.forecastArrivalsSource(airportConfig.portCode),
      arrivalsLiveBaseFeed = feedService.liveBaseArrivalsSource(airportConfig.portCode),
      arrivalsLiveFeed = feedService.liveArrivalsSource(airportConfig.portCode),
      passengerAdjustments = PaxDeltas.applyAdjustmentsToArrivals(passengersActorProvider, aclPaxAdjustmentDays),
      optimiser = optimiser,
      startDeskRecs = startUpdateGraphs,
      setPcpTimes = setPcpTimes,
      system = system,
    ))
  }

  val persistManifests: ManifestsFeedResponse => Future[Done] = ManifestPersistence.processManifestFeedResponse(
    persistentStateActors.manifestsRouterActor,
    actorService.flightsRouterActor,
    splitsCalculator.splitsForManifest,
  )

  def run(): Unit = {
    val actors = persistentStateActors

    val futurePortStates =
      for {
        mergeArrivalsQueue <- actors.mergeArrivalsQueueActor.ask(GetState).mapTo[SortedSet[ProcessingRequest]]
        crunchQueue <- actors.crunchQueueActor.ask(GetState).mapTo[SortedSet[ProcessingRequest]]
        deskRecsQueue <- actors.deskRecsQueueActor.ask(GetState).mapTo[SortedSet[ProcessingRequest]]
        deploymentQueue <- actors.deploymentQueueActor.ask(GetState).mapTo[SortedSet[ProcessingRequest]]
        staffingUpdateQueue <- actors.staffingQueueActor.ask(GetState).mapTo[SortedSet[ProcessingRequest]]
      } yield (mergeArrivalsQueue, crunchQueue, deskRecsQueue, deploymentQueue, staffingUpdateQueue)

    futurePortStates.onComplete {
      case Success((mergeArrivalsQueue, crunchQueue, deskRecsQueue, deploymentQueue, staffingUpdateQueue)) =>
        system.log.info(s"Successfully restored initial state for App")

        val crunchInputs: CrunchSystem[typed.ActorRef[Feed.FeedTick]] = startCrunchSystem(
          actors,
          startUpdateGraphs = startUpdateGraphs(actors, mergeArrivalsQueue, crunchQueue, deskRecsQueue, deploymentQueue, staffingUpdateQueue),
        )

        feedService.fcstBaseFeedPollingActor ! Enable(crunchInputs.forecastBaseArrivalsResponse)
        feedService.fcstFeedPollingActor ! Enable(crunchInputs.forecastArrivalsResponse)
        feedService.liveBaseFeedPollingActor ! Enable(crunchInputs.liveBaseArrivalsResponse)
        feedService.liveFeedPollingActor ! Enable(crunchInputs.liveArrivalsResponse)

        if (!airportConfig.aclDisabled) {
          feedService.aclLastCheckedAt().map { lastSuccess =>
            val twelveHoursAgo = SDate.now().addHours(-12).millisSinceEpoch
            if (lastSuccess < twelveHoursAgo) {
              val minutesToNextCheck = (Math.random() * 90).toInt.minutes
              log.info(s"Last ACL check was more than 12 hours ago. Will check in ${minutesToNextCheck.toMinutes} minutes")
              system.scheduler.scheduleOnce(minutesToNextCheck) {
                feedService.fcstBaseFeedPollingActor ! AdhocCheck
              }
            }
          }
        }
        val lastProcessedLiveApiMarker: Option[MillisSinceEpoch] =
          if (refetchApiData) None
          else initialState[ApiFeedState](persistentStateActors.manifestsRouterActor).map(_.lastProcessedMarker)
        system.log.info(s"Providing last processed API marker: ${lastProcessedLiveApiMarker.map(SDate(_).toISOString).getOrElse("None")}")

        val arrivalKeysProvider = DbManifestArrivalKeys(AggregateDb, airportConfig.portCode)
        val manifestProcessor = DbManifestProcessor(AggregateDb, airportConfig.portCode, persistManifests)
        val processFilesAfter = lastProcessedLiveApiMarker.getOrElse(SDate.now().addHours(-12).millisSinceEpoch)

        system.scheduler.scheduleOnce(20.seconds) {
          log.info(s"Importing live manifests processed after ${SDate(processFilesAfter).toISOString}")
          ApiFeedImpl(arrivalKeysProvider, manifestProcessor, 1.second)
            .startProcessingFrom(processFilesAfter)
            .runWith(Sink.ignore)
        }

        setSubscribers(crunchInputs)

        system.scheduler.scheduleAtFixedRate(0.millis, 1.minute)(ApiValidityReporter(feedService.flightLookups.flightsRouterActor))

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
  }
}

object RunnableMergedArrivals {
  def startMergeArrivals(portCode: PortCode,
                         flightsRouterActor: ActorRef,
                         aggregatedArrivalsActor: ActorRef,
                         mergeArrivalsQueueActor: ActorRef,
                         feedArrivalsForDate: Seq[DateLike => Future[FeedArrivalSet]],
                         mergeArrivalsQueue: SortedSet[ProcessingRequest],
                         mergeArrivalRequest: MillisSinceEpoch => MergeArrivalsRequest,
                         setPcpTimes: Seq[Arrival] => Future[Seq[Arrival]],
                         addArrivalPredictions: ArrivalsDiff => Future[ArrivalsDiff],
                        )
                        (implicit ec: ExecutionContext, mat: Materializer, timeout: Timeout): (ActorRef, UniqueKillSwitch) = {
    val existingMergedArrivals: UtcDate => Future[Set[UniqueArrival]] =
      (date: UtcDate) =>
        FlightsProvider(flightsRouterActor)
          .allTerminalsDateRange(date, date).map(_._2.map(_.unique).toSet)
          .runWith(Sink.fold(Set[UniqueArrival]())(_ ++ _))
          .map(_.filter(u => SDate(u.scheduled).toUtcDate == date))

    val merger = MergeArrivals(
      existingMergedArrivals,
      feedArrivalsForDate,
      ArrivalsAdjustments.adjustmentsForPort(portCode),
    )

    val mergeArrivalsFlow = MergeArrivals.processingRequestToArrivalsDiff(
      mergeArrivalsForDate = merger,
      setPcpTimes = setPcpTimes,
      addArrivalPredictions = addArrivalPredictions,
      updateAggregatedArrivals = aggregatedArrivalsActor ! _,
    )

    val (mergeArrivalsRequestQueueActor, mergeArrivalsKillSwitch: UniqueKillSwitch) =
      startQueuedRequestProcessingGraph(
        mergeArrivalsFlow,
        mergeArrivalsQueueActor,
        mergeArrivalsQueue,
        flightsRouterActor,
        "arrivals",
        mergeArrivalRequest,
      )
    (mergeArrivalsRequestQueueActor, mergeArrivalsKillSwitch)
  }

  private def startQueuedRequestProcessingGraph[A](minutesProducer: Flow[ProcessingRequest, A, NotUsed],
                                                   persistentQueueActor: ActorRef,
                                                   initialQueue: SortedSet[ProcessingRequest],
                                                   sinkActor: ActorRef,
                                                   graphName: String,
                                                   processingRequest: MillisSinceEpoch => ProcessingRequest,
                                                  )
                                                  (implicit materializer: Materializer): (ActorRef, UniqueKillSwitch) = {
    val graphSource = new SortedActorRefSource(persistentQueueActor, processingRequest, initialQueue, graphName)
    QueuedRequestProcessing.createGraph(graphSource, sinkActor, minutesProducer, graphName).run()
  }

}
