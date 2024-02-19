package uk.gov.homeoffice.drt.service

import actors.CrunchManagerActor.{AddQueueCrunchSubscriber, AddRecalculateArrivalsSubscriber}
import actors.DrtStaticParameters.{startOfTheMonth, time48HoursAgo}
import actors.PartitionedPortStateActor.GetFlights
import actors._
import actors.daily.{PassengersActor, RequestAndTerminateActor}
import actors.persistent._
import actors.persistent.staffing.{FixedPointsActor, GetFeedStatuses, ShiftsActor, StaffMovementsActor}
import actors.routing.FlightsRouterActor
import akka.actor.{ActorRef, ActorSystem, Props, typed}
import akka.pattern.{StatusReply, ask}
import akka.stream.scaladsl.{Flow, Sink, Source, SourceQueueWithComplete}
import akka.stream.{Materializer, OverflowStrategy, UniqueKillSwitch}
import akka.util.Timeout
import akka.{Done, NotUsed}
import drt.server.feeds.Feed.FeedTick
import drt.server.feeds.FeedPoller.{AdhocCheck, Enable}
import drt.server.feeds._
import drt.server.feeds.api.{ApiFeedImpl, DbManifestArrivalKeys, DbManifestProcessor}
import drt.shared.CrunchApi.{CrunchMinute, MillisSinceEpoch, MinutesContainer, StaffMinute}
import drt.shared._
import manifests.ManifestLookupLike
import manifests.queues.SplitsCalculator
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.parsing.VoyageManifestParser
import play.api.Configuration
import providers.{FlightsProvider, ManifestsProvider, MinutesProvider}
import queueus._
import services.PcpArrival.pcpFrom
import services.arrivals.{ArrivalsAdjustments, ArrivalsAdjustmentsLike}
import services.crunch.CrunchManager.queueDaysToReCrunch
import services.crunch.CrunchSystem.paxTypeQueueAllocator
import services.crunch.desklimits.{PortDeskLimits, TerminalDeskLimitsLike}
import services.crunch.deskrecs._
import services.crunch.staffing.RunnableStaffing
import services.crunch.{CrunchProps, CrunchSystem}
import services.graphstages.FlightFilter
import services.liveviews.PassengersLiveView
import services.metrics.ApiValidityReporter
import services.prediction.ArrivalPredictions
import services.TryCrunchWholePax
import services.staffing.StaffMinutesChecker
import services.{OptimiserWithFlexibleProcessors, PaxDeltas}
import slickdb.Tables
import uk.gov.homeoffice.drt.actor.PredictionModelActor.{TerminalCarrier, TerminalOrigin}
import uk.gov.homeoffice.drt.actor.commands.Commands.{AddUpdatesSubscriber, GetState}
import uk.gov.homeoffice.drt.actor.commands.{CrunchRequest, ProcessingRequest}
import uk.gov.homeoffice.drt.actor.state.ArrivalsState
import uk.gov.homeoffice.drt.actor.{ConfigActor, PredictionModelActor, WalkTimeProvider}
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.crunchsystem.{PersistentStateActors, ReadRouteUpdateActorsLike}
import uk.gov.homeoffice.drt.db.AggregateDb
import uk.gov.homeoffice.drt.egates.{EgateBank, EgateBanksUpdate, EgateBanksUpdates, PortEgateBanksUpdates}
import uk.gov.homeoffice.drt.feeds.FeedSourceStatuses
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports._
import uk.gov.homeoffice.drt.ports.config.slas.{SlaConfigs, SlasUpdate}
import uk.gov.homeoffice.drt.prediction.arrival.{OffScheduleModelAndFeatures, PaxCapModelAndFeatures, ToChoxModelAndFeatures, WalkTimeModelAndFeatures}
import uk.gov.homeoffice.drt.prediction.persistence.Flight
import uk.gov.homeoffice.drt.redlist.RedListUpdates
import uk.gov.homeoffice.drt.services.Slas
import uk.gov.homeoffice.drt.time.MilliTimes.oneSecondMillis
import uk.gov.homeoffice.drt.time._

import javax.inject.Singleton
import scala.collection.SortedSet
import scala.collection.immutable.SortedMap
import scala.concurrent.duration.{DurationInt, DurationLong}
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
                              readActorService: ReadRouteUpdateActorsLike,
                              persistentStateActors: PersistentStateActors)
                             (implicit system: ActorSystem, ec: ExecutionContext, mat: Materializer, timeout: Timeout) {
  val log: Logger = LoggerFactory.getLogger(getClass)
  private val walkTimeProvider: (Terminal, String, String) => Option[Int] = WalkTimeProvider(params.gateWalkTimesFilePath, params.standWalkTimesFilePath)

  val maxDaysToConsider: Int = 14
  val passengersActorProvider: () => ActorRef = () => system.actorOf(Props(new PassengersActor(maxDaysToConsider, aclPaxAdjustmentDays, now)))

  private val aclPaxAdjustmentDays: Int = config.get[Int]("acl.adjustment.number-of-days-in-average")

  val optimiser: TryCrunchWholePax = OptimiserWithFlexibleProcessors.crunchWholePax


  private val crunchRequestProvider: LocalDate => CrunchRequest =
    date => CrunchRequest(date, airportConfig.crunchOffsetMinutes, airportConfig.minutesToCrunch)

  val slasActor: ActorRef = system.actorOf(Props(new ConfigActor[Map[Queue, Int], SlaConfigs]("slas", now, crunchRequestProvider, maxDaysToConsider)))

  ensureDefaultSlaConfig()

  val portDeskRecs: PortDesksAndWaitsProviderLike =
    PortDesksAndWaitsProvider(airportConfig, optimiser, FlightFilter.forPortConfig(airportConfig), feedService.paxFeedSourceOrder, Slas.slaProvider(slasActor))

  val paxTypeQueueAllocation: PaxTypeQueueAllocation = paxTypeQueueAllocator(airportConfig)

  val splitAdjustments: QueueAdjustments = if (params.adjustEGateUseByUnder12s)
    ChildEGateAdjustments(airportConfig.assumedAdultsPerChild)
  else
    AdjustmentsNoop


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
      val updates = SortedMap[UniqueArrival, Arrival]() ++
        diff.toUpdate.view.mapValues(arrival => arrival.copy(PcpTime = Option(pcpArrivalTimeCalculator(arrival).millisSinceEpoch)))
      diff.copy(toUpdate = updates)
    }


  //  val manifestLookups: ManifestLookups = ManifestLookups(system)

  private val manifestsRouterActorReadOnly: ActorRef =
    system.actorOf(
      Props(new ManifestRouterActor(manifestLookups.manifestsByDayLookup, manifestLookups.updateManifests)),
      name = "voyage-manifests-router-actor-read-only")

  val manifestsProvider: (UtcDate, UtcDate) => Source[(UtcDate, VoyageManifestParser.VoyageManifests), NotUsed] =
    ManifestsProvider(manifestsRouterActorReadOnly)

  private lazy val updateLivePaxView = PassengersLiveView.updateLiveView(airportConfig.portCode, now, db)
  lazy val populateLivePaxViewForDate: UtcDate => Future[StatusReply[Done]] =
    PassengersLiveView.populatePaxForDate(readActorService.minuteLookups.queueMinutesRouterActor, updateLivePaxView)


  private def ensureDefaultSlaConfig(): Unit =
    slasActor.ask(GetState).mapTo[SlaConfigs].foreach { slasUpdate =>
      if (slasUpdate.configs.isEmpty) {
        log.info(s"No SLAs. Adding defaults from airport config")
        slasActor ! ConfigActor.SetUpdate(SlasUpdate(SDate("2014-09-01T00:00").millisSinceEpoch, airportConfig.slaByQueue, None))
      } else {
        log.info("SLAs: " + slasUpdate)
      }
    }


  def initialState[A](askableActor: ActorRef): Option[A] = Await.result(initialStateFuture[A](askableActor), 2.minutes)

  def initialFlightsPortState(actor: ActorRef, forecastMaxDays: Int): Future[Option[PortState]] = {
    val from = now().getLocalLastMidnight.addDays(-1)
    val to = from.addDays(forecastMaxDays)
    val request = GetFlights(from.millisSinceEpoch, to.millisSinceEpoch)
    FlightsRouterActor.runAndCombine(actor
      .ask(request)(new Timeout(15.seconds)).mapTo[Source[(UtcDate, FlightsWithSplits), NotUsed]])
      .map { fws =>
        Option(PortState(fws.flights.values, Iterable(), Iterable()))
      }
  }

  def initialStateFuture[A](askableActor: ActorRef): Future[Option[A]] = {
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

  val requestAndTerminateActor: ActorRef = system.actorOf(Props(new RequestAndTerminateActor()), "request-and-terminate-actor")

  val shiftsSequentialWritesActor: ActorRef = system.actorOf(ShiftsActor.sequentialWritesProps(
    now, startOfTheMonth(now), requestAndTerminateActor, system), "shifts-sequential-writes-actor")
  val fixedPointsSequentialWritesActor: ActorRef = system.actorOf(FixedPointsActor.sequentialWritesProps(
    now, requestAndTerminateActor, system), "fixed-points-sequential-writes-actor")
  val staffMovementsSequentialWritesActor: ActorRef = system.actorOf(StaffMovementsActor.sequentialWritesProps(
    now, time48HoursAgo(now), requestAndTerminateActor, system), "staff-movements-sequential-writes-actor")

  lazy val flightsProvider: FlightsProvider = FlightsProvider(readActorService.flightsRouterActor)

  lazy val terminalFlightsProvider: Terminal => (UtcDate, UtcDate) => Source[(UtcDate, Seq[ApiFlightWithSplits]), NotUsed] =
    flightsProvider.singleTerminal
  lazy val crunchMinutesProvider: Terminal => (UtcDate, UtcDate) => Source[(UtcDate, Seq[CrunchMinute]), NotUsed] =
    MinutesProvider.singleTerminal(readActorService.queuesRouterActor)
  lazy val staffMinutesProvider: Terminal => (UtcDate, UtcDate) => Source[(UtcDate, Seq[StaffMinute]), NotUsed] =
    MinutesProvider.singleTerminal(readActorService.staffRouterActor)

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

  private val egatesProvider: () => Future[PortEgateBanksUpdates] = () => egateBanksUpdatesActor.ask(GetState).mapTo[PortEgateBanksUpdates]

  val crunchManagerActor: ActorRef = system.actorOf(Props(new CrunchManagerActor), name = "crunch-manager-actor")
  private val refetchApiData: Boolean = config.get[Boolean]("crunch.manifests.refetch-live-api")

  val startUpdateGraphs: (
    PersistentStateActors,
      SortedSet[ProcessingRequest],
      SortedSet[ProcessingRequest],
      SortedSet[ProcessingRequest],
      SortedSet[ProcessingRequest]
    ) => () => (ActorRef, ActorRef, ActorRef, UniqueKillSwitch, UniqueKillSwitch, UniqueKillSwitch) =
    (actors, crunchQueue, deskRecsQueue, deploymentQueue, staffQueue) => () => {
      val staffToDeskLimits = PortDeskLimits.flexedByAvailableStaff(airportConfig, terminalEgatesProvider) _

      implicit val timeout: Timeout = new Timeout(10.seconds)

      val splitsCalculator = SplitsCalculator(paxTypeQueueAllocation, airportConfig.terminalPaxSplits, splitAdjustments)
      val manifestCacheLookup = RouteHistoricManifestActor.manifestCacheLookup(airportConfig.portCode, now, system, timeout, ec)
      val manifestCacheStore = RouteHistoricManifestActor.manifestCacheStore(airportConfig.portCode, now, system, timeout, ec)

      if (config.getOptional[Boolean]("feature-flags.populate-historic-pax").getOrElse(false))
        PassengersLiveView.populateHistoricPax(populateLivePaxViewForDate)

      val passengerLoadsFlow = DynamicRunnablePassengerLoads.crunchRequestsToQueueMinutes(
        arrivalsProvider = OptimisationProviders.flightsWithSplitsProvider(readActorService.portStateActor),
        liveManifestsProvider = OptimisationProviders.liveManifestsProvider(manifestsProvider),
        historicManifestsProvider =
          OptimisationProviders.historicManifestsProvider(airportConfig.portCode, manifestLookupService, manifestCacheLookup, manifestCacheStore),
        historicManifestsPaxProvider = OptimisationProviders.historicManifestsPaxProvider(airportConfig.portCode, manifestLookupService),
        splitsCalculator = splitsCalculator,
        splitsSink = readActorService.portStateActor,
        portDesksAndWaitsProvider = portDeskRecs,
        redListUpdatesProvider = () => redListUpdatesActor.ask(GetState).mapTo[RedListUpdates],
        DynamicQueueStatusProvider(airportConfig, egatesProvider),
        airportConfig.queuesByTerminal,
        updateLiveView = updateLivePaxView,
        paxFeedSourceOrder = feedService.paxFeedSourceOrder,
      )

      val (crunchRequestQueueActor, _: UniqueKillSwitch) =
        startOptimisationGraph(passengerLoadsFlow,
          actors.crunchQueueActor,
          crunchQueue,
          readActorService.minuteLookups.queueLoadsMinutesActor,
          "passenger-loads")

      val deskRecsFlow = DynamicRunnableDeskRecs.crunchRequestsToDeskRecs(
        loadsProvider = OptimisationProviders.passengersProvider(readActorService.minuteLookups.queueLoadsMinutesActor),
        maxDesksProviders = deskLimitsProviders,
        loadsToQueueMinutes = portDeskRecs.loadsToDesks,
      )

      val (deskRecsRequestQueueActor, deskRecsKillSwitch) =
        startOptimisationGraph(deskRecsFlow, actors.deskRecsQueueActor, deskRecsQueue, readActorService.minuteLookups.queueMinutesRouterActor, "desk-recs")

      val deploymentsFlow = DynamicRunnableDeployments.crunchRequestsToDeployments(
        loadsProvider = OptimisationProviders.passengersProvider(readActorService.minuteLookups.queueLoadsMinutesActor),
        staffProvider = OptimisationProviders.staffMinutesProvider(readActorService.minuteLookups.staffMinutesRouterActor, airportConfig.terminals),
        staffToDeskLimits = staffToDeskLimits,
        loadsToQueueMinutes = portDeskRecs.loadsToSimulations
      )

      val (deploymentRequestQueueActor, deploymentsKillSwitch) = startOptimisationGraph(deploymentsFlow,
        actors.deploymentQueueActor,
        deploymentQueue,
        readActorService.minuteLookups.queueMinutesRouterActor,
        "deployments")

      val shiftsProvider = (r: ProcessingRequest) => readActorService.liveShiftsReadActor.ask(r).mapTo[ShiftAssignments]
      val fixedPointsProvider = (r: ProcessingRequest) => readActorService.liveFixedPointsReadActor.ask(r).mapTo[FixedPointAssignments]
      val movementsProvider = (r: ProcessingRequest) => readActorService.liveStaffMovementsReadActor.ask(r).mapTo[StaffMovements]

      val staffMinutesFlow = RunnableStaffing.staffMinutesFlow(shiftsProvider, fixedPointsProvider, movementsProvider, now)

      val (staffingUpdateRequestQueue, staffingUpdateKillSwitch) =
        startOptimisationGraph(staffMinutesFlow, actors.staffingQueueActor, staffQueue, readActorService.minuteLookups.staffMinutesRouterActor, "staffing")

      readActorService.liveShiftsReadActor ! AddUpdatesSubscriber(staffingUpdateRequestQueue)
      readActorService.liveFixedPointsReadActor ! AddUpdatesSubscriber(staffingUpdateRequestQueue)
      readActorService.liveStaffMovementsReadActor ! AddUpdatesSubscriber(staffingUpdateRequestQueue)

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


  def setSubscribers(crunchInputs: CrunchSystem[typed.ActorRef[FeedTick]], manifestsRouterActor: ActorRef): Unit = {
    readActorService.flightsRouterActor ! AddUpdatesSubscriber(crunchInputs.crunchRequestActor)
    manifestsRouterActor ! AddUpdatesSubscriber(crunchInputs.crunchRequestActor)
    readActorService.queueLoadsRouterActor ! AddUpdatesSubscriber(crunchInputs.deskRecsRequestActor)
    readActorService.queueLoadsRouterActor ! AddUpdatesSubscriber(crunchInputs.deploymentRequestActor)
    readActorService.staffRouterActor ! AddUpdatesSubscriber(crunchInputs.deploymentRequestActor)
    slasActor ! AddUpdatesSubscriber(crunchInputs.deskRecsRequestActor)
  }

  val terminalEgatesProvider: Terminal => Future[EgateBanksUpdates] = EgateBanksUpdatesActor.terminalEgatesProvider(egateBanksUpdatesActor)

  val deskLimitsProviders: Map[Terminal, TerminalDeskLimitsLike] = if (config.get[Boolean]("crunch.flex-desks")) {
    PortDeskLimits.flexed(airportConfig, terminalEgatesProvider)
  }
  else
    PortDeskLimits.fixed(airportConfig, terminalEgatesProvider)

  def startCrunchSystem(actors: PersistentStateActors,
                        initialPortState: Option[PortState],
                        initialForecastBaseArrivals: Option[SortedMap[UniqueArrival, Arrival]],
                        initialForecastArrivals: Option[SortedMap[UniqueArrival, Arrival]],
                        initialLiveBaseArrivals: Option[SortedMap[UniqueArrival, Arrival]],
                        initialLiveArrivals: Option[SortedMap[UniqueArrival, Arrival]],
                        refreshArrivalsOnStart: Boolean,
                        startUpdateGraphs: () => (ActorRef, ActorRef, ActorRef, UniqueKillSwitch, UniqueKillSwitch, UniqueKillSwitch),
                       ): CrunchSystem[typed.ActorRef[FeedTick]] = {
    val voyageManifestsLiveSource: Source[ManifestsFeedResponse, SourceQueueWithComplete[ManifestsFeedResponse]] =
      Source.queue[ManifestsFeedResponse](1, OverflowStrategy.backpressure)
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
      portStateActor = readActorService.portStateActor,
      maxDaysToCrunch = params.forecastMaxDays,
      expireAfterMillis = DrtStaticParameters.expireAfterMillis,
      now = now,
      manifestsLiveSource = voyageManifestsLiveSource,
      crunchActors = actors,
      initialPortState = initialPortState,
      initialForecastBaseArrivals = initialForecastBaseArrivals.getOrElse(SortedMap()),
      initialForecastArrivals = initialForecastArrivals.getOrElse(SortedMap()),
      initialLiveBaseArrivals = initialLiveBaseArrivals.getOrElse(SortedMap()),
      initialLiveArrivals = initialLiveArrivals.getOrElse(SortedMap()),
      arrivalsForecastBaseFeed = feedService.baseArrivalsSource(feedService.maybeAclFeed),
      arrivalsForecastFeed = feedService.forecastArrivalsSource(airportConfig.portCode),
      arrivalsLiveBaseFeed = feedService.liveBaseArrivalsSource(airportConfig.portCode),
      arrivalsLiveFeed = feedService.liveArrivalsSource(airportConfig.portCode),
      passengerAdjustments = PaxDeltas.applyAdjustmentsToArrivals(passengersActorProvider, aclPaxAdjustmentDays),
      refreshArrivalsOnStart = refreshArrivalsOnStart,
      optimiser = optimiser,
      startDeskRecs = startUpdateGraphs,
      arrivalsAdjustments = arrivalAdjustments,
      flushArrivalsSource = flushArrivalsSource,
      addArrivalPredictions = addArrivalPredictions,
      setPcpTimes = setPcpTimes,
      flushArrivalsOnStart = params.flushArrivalsOnStart,
      system = system,
    ))
  }

  def run(): Unit = {
    val actors = persistentStateActors // This is a var so we can set it in the future

    val futurePortStates: Future[(
      Option[PortState],
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
        lps <- initialFlightsPortState(readActorService.portStateActor, params.forecastMaxDays)
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
      case Success((maybePortState,
      maybeBaseArrivals,
      maybeForecastArrivals, maybeLiveArrivals, maybeAclStatus, crunchQueue, deskRecsQueue, deploymentQueue, staffingUpdateQueue)) =>
        system.log.info(s"Successfully restored initial state for App")

        val crunchInputs: CrunchSystem[typed.ActorRef[Feed.FeedTick]] = startCrunchSystem(
          actors,
          initialPortState = maybePortState,
          initialForecastBaseArrivals = maybeBaseArrivals,
          initialForecastArrivals = maybeForecastArrivals,
          initialLiveBaseArrivals = Option(SortedMap[UniqueArrival, Arrival]()),
          initialLiveArrivals = maybeLiveArrivals,
          refreshArrivalsOnStart = params.refreshArrivalsOnStart,
          startUpdateGraphs = startUpdateGraphs(actors, crunchQueue, deskRecsQueue, deploymentQueue, staffingUpdateQueue),
        )

        feedService.fcstBaseActor ! Enable(crunchInputs.forecastBaseArrivalsResponse)
        feedService.fcstActor ! Enable(crunchInputs.forecastArrivalsResponse)
        feedService.liveBaseActor ! Enable(crunchInputs.liveBaseArrivalsResponse)
        feedService.liveActor ! Enable(crunchInputs.liveArrivalsResponse)

        for {
          aclStatus <- maybeAclStatus
          lastSuccess <- aclStatus.feedStatuses.lastSuccessAt
        } yield {
          val twelveHoursAgo = SDate.now().addHours(-12).millisSinceEpoch
          if (lastSuccess < twelveHoursAgo) {
            val minutesToNextCheck = (Math.random() * 90).toInt.minutes
            log.info(s"Last ACL check was more than 12 hours ago. Will check in ${minutesToNextCheck.toMinutes} minutes")
            system.scheduler.scheduleOnce(minutesToNextCheck) {
              feedService.fcstBaseActor ! AdhocCheck
            }
          }
        }
        val lastProcessedLiveApiMarker: Option[MillisSinceEpoch] =
          if (refetchApiData) None
          else initialState[ApiFeedState](actors.manifestsRouterActor).map(_.lastProcessedMarker)
        system.log.info(s"Providing last processed API marker: ${lastProcessedLiveApiMarker.map(SDate(_).toISOString).getOrElse("None")}")

        val arrivalKeysProvider = DbManifestArrivalKeys(AggregateDb, airportConfig.portCode)
        val manifestProcessor = DbManifestProcessor(AggregateDb, airportConfig.portCode, crunchInputs.manifestsLiveResponseSource)
        val processFilesAfter = lastProcessedLiveApiMarker.getOrElse(SDate.now().addHours(-12).millisSinceEpoch)
        log.info(s"Importing live manifests processed after ${SDate(processFilesAfter).toISOString}")
        ApiFeedImpl(arrivalKeysProvider, manifestProcessor, 1.second)
          .processFilesAfter(processFilesAfter)
          .runWith(Sink.ignore)

        crunchManagerActor ! AddRecalculateArrivalsSubscriber(crunchInputs.flushArrivalsSource)

        setSubscribers(crunchInputs, actors.manifestsRouterActor)

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
