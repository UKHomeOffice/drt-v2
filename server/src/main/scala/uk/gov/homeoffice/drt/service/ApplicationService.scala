package uk.gov.homeoffice.drt.service

import actors.CrunchManagerActor.{AddQueueCrunchSubscriber, AddQueueHistoricPaxLookupSubscriber, AddQueueHistoricSplitsLookupSubscriber, AddRecalculateArrivalsSubscriber}
import actors._
import actors.daily.{PassengersActor, RequestAndTerminate}
import actors.persistent._
import actors.persistent.arrivals.AclForecastArrivalsActor
import actors.routing.FlightsRouterActor.{AddHistoricPaxRequestActor, AddHistoricSplitsRequestActor}
import akka.actor.{ActorRef, ActorSystem, Props, typed}
import akka.pattern.{StatusReply, ask}
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.{Materializer, UniqueKillSwitch}
import akka.util.Timeout
import akka.{Done, NotUsed}
import drt.server.feeds.Feed.FeedTick
import drt.server.feeds.FeedPoller.{AdhocCheck, Enable}
import drt.server.feeds._
import drt.server.feeds.api.{ApiFeedImpl, DbManifestArrivalKeys, DbManifestProcessor}
import drt.shared.CrunchApi.{MillisSinceEpoch, StaffMinute}
import manifests.ManifestLookupLike
import manifests.queues.SplitsCalculator
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.parsing.VoyageManifestParser
import play.api.Configuration
import providers.{FlightsProvider, ManifestsProvider, MinutesProvider}
import queueus._
import services.PcpArrival.pcpFrom
import services.arrivals.{RunnableHistoricPax, RunnableHistoricSplits, RunnableMergedArrivals}
import services.crunch.CrunchSystem.paxTypeQueueAllocator
import services.crunch.desklimits.{PortDeskLimits, TerminalDeskLimitsLike}
import services.crunch.deskrecs._
import services.crunch.staffing.RunnableStaffing
import services.crunch.{CrunchProps, CrunchSystem}
import services.dataretention.DataRetentionHandler
import services.graphstages.FlightFilter
import services.liveviews.PassengersLiveView
import services.metrics.ApiValidityReporter
import services.prediction.ArrivalPredictions
import services.staffing.StaffMinutesChecker
import services.{OptimiserWithFlexibleProcessors, PaxDeltas, TryCrunchWholePax}
import slickdb.dao.ArrivalStatsDao
import slickdb.{AggregatedDbTables, AkkaDbTables}
import uk.gov.homeoffice.drt.actor.PredictionModelActor.{TerminalCarrier, TerminalOrigin}
import uk.gov.homeoffice.drt.actor.commands.Commands.{AddUpdatesSubscriber, GetState}
import uk.gov.homeoffice.drt.actor.commands.TerminalUpdateRequest
import uk.gov.homeoffice.drt.actor.serialisation.{ConfigDeserialiser, ConfigSerialiser, EmptyConfig}
import uk.gov.homeoffice.drt.actor.state.ArrivalsState
import uk.gov.homeoffice.drt.actor.{ConfigActor, PredictionModelActor, WalkTimeProvider}
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.crunchsystem.{ActorsServiceLike, PersistentStateActors}
import uk.gov.homeoffice.drt.db.AggregateDb
import uk.gov.homeoffice.drt.egates.{EgateBank, EgateBanksUpdate, EgateBanksUpdates, PortEgateBanksUpdates}
import uk.gov.homeoffice.drt.model.CrunchMinute
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
                              now: () => SDateLike,
                              params: DrtParameters,
                              config: Configuration,
                              aggregatedDb: AggregatedDbTables,
                              akkaDb: AkkaDbTables,
                              feedService: FeedService,
                              manifestLookups: ManifestLookupsLike,
                              manifestLookupService: ManifestLookupLike,
                              minuteLookups: MinuteLookupsLike,
                              actorService: ActorsServiceLike,
                              persistentStateActors: PersistentStateActors,
                              requestAndTerminateActor: ActorRef,
                              splitsCalculator: SplitsCalculator,
                             )
                             (implicit system: ActorSystem, ec: ExecutionContext, mat: Materializer, timeout: Timeout, airportConfig: AirportConfig) {
  val log: Logger = LoggerFactory.getLogger(getClass)
  private val walkTimeProvider: (Terminal, String, String) => Option[Int] = WalkTimeProvider(params.gateWalkTimesFilePath, params.standWalkTimesFilePath)

  val maxDaysToConsider: Int = 14
  val passengersActorProvider: () => ActorRef = () => system.actorOf(Props(new PassengersActor(maxDaysToConsider, aclPaxAdjustmentDays, now)))

  private val aclPaxAdjustmentDays: Int = config.get[Int]("acl.adjustment.number-of-days-in-average")
  private val refetchApiData: Boolean = config.get[Boolean]("crunch.manifests.refetch-live-api")

  private val optimiser: TryCrunchWholePax = OptimiserWithFlexibleProcessors.crunchWholePax

  private val crunchRequestsProvider: LocalDate => Iterable[TerminalUpdateRequest] =
    (date: LocalDate) => airportConfig.terminals.map(TerminalUpdateRequest(_, date))

  val slasActor: ActorRef = system.actorOf(Props(new ConfigActor[Map[Queue, Int], SlaConfigs]("slas", now, crunchRequestsProvider, maxDaysToConsider)(
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

  private lazy val updateLivePaxView = PassengersLiveView.updateLiveView(airportConfig.portCode, now, aggregatedDb)

  private val flightsForDate: UtcDate => Future[Seq[ApiFlightWithSplits]] =
    (d: UtcDate) => flightsProvider.allTerminalsDateScheduledOrPcp(d).map(_._2).runWith(Sink.fold(Seq.empty[ApiFlightWithSplits])(_ ++ _))

  private val aclArrivalsForDate: UtcDate => Future[ArrivalsState] = (d: UtcDate) => {
    val actor = system.actorOf(Props(new AclForecastArrivalsActor(now, DrtStaticParameters.expireAfterMillis, Option(SDate(d).millisSinceEpoch))))
    requestAndTerminateActor.ask(RequestAndTerminate(actor, GetState)).mapTo[ArrivalsState]
  }
  private lazy val uniqueFlightsForDate = PassengersLiveView.uniqueFlightsForDate(
    flights = flightsForDate,
    baseArrivals = aclArrivalsForDate,
    paxFeedSourceOrder = feedService.paxFeedSourceOrder,
  )
  private lazy val getCapacities = PassengersLiveView.capacityForDate(uniqueFlightsForDate)
  private lazy val persistCapacity = PassengersLiveView.persistCapacityForDate(aggregatedDb, airportConfig.portCode)
  private val updateAndPersistCapacity = PassengersLiveView.updateAndPersistCapacityForDate(getCapacities, persistCapacity)

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
  val egateBanksUpdatesActor: ActorRef = system.actorOf(Props(new EgateBanksUpdatesActor(
    now,
    defaultEgates,
    params.forecastMaxDays)), "egate-banks-updates-actor")

  lazy val flightsProvider: FlightsProvider = FlightsProvider(actorService.flightsRouterActor)

  lazy val terminalCrunchMinutesProvider: Terminal => (UtcDate, UtcDate) => Source[(UtcDate, Seq[CrunchMinute]), NotUsed] =
    MinutesProvider.singleTerminal(actorService.queuesRouterActor)
  lazy val allTerminalsCrunchMinutesProvider: (UtcDate, UtcDate) => Source[(UtcDate, Seq[CrunchMinute]), NotUsed] =
    MinutesProvider.allTerminals(actorService.queuesRouterActor)

  lazy val terminalStaffMinutesProvider: Terminal => (UtcDate, UtcDate) => Source[(UtcDate, Seq[StaffMinute]), NotUsed] =
    MinutesProvider.singleTerminal(actorService.staffRouterActor)

  private def enabledPredictionModelNamesWithUpperThresholds = Map(
    OffScheduleModelAndFeatures.targetName -> 45,
    ToChoxModelAndFeatures.targetName -> 20,
    WalkTimeModelAndFeatures.targetName -> 30 * 60,
    PaxCapModelAndFeaturesV2.targetName -> 100,
  )

  private val egatesProvider: () => Future[PortEgateBanksUpdates] = () => egateBanksUpdatesActor.ask(GetState).mapTo[PortEgateBanksUpdates]

  val crunchManagerActor: ActorRef = system.actorOf(
    CrunchManagerActor.props(flightsProvider.allTerminalsDateRangeScheduledOrPcp),
    name = "crunch-manager-actor")

  val addArrivalPredictions: ArrivalsDiff => Future[ArrivalsDiff] =
    ArrivalPredictions(
      (a: Arrival) => Iterable(
        TerminalOrigin(a.Terminal.toString, a.Origin.iata),
        TerminalCarrier(a.Terminal.toString, a.CarrierCode.code),
        PredictionModelActor.Terminal(a.Terminal.toString),
      ),
      feedService.flightModelPersistence.getModels(enabledPredictionModelNamesWithUpperThresholds.keys.toSeq, None),
      enabledPredictionModelNamesWithUpperThresholds,
      minimumImprovementPctThreshold = 15
    ).addPredictions

  val startUpdateGraphs: (
    PersistentStateActors,
      SortedSet[TerminalUpdateRequest],
      SortedSet[TerminalUpdateRequest],
      SortedSet[TerminalUpdateRequest],
      SortedSet[TerminalUpdateRequest],
      SortedSet[TerminalUpdateRequest],
    ) => () => (ActorRef, ActorRef, ActorRef, ActorRef, Iterable[UniqueKillSwitch]) =
    (actors, mergeArrivalsQueue, crunchQueue, deskRecsQueue, deploymentQueue, staffQueue) => () => {
      val staffToDeskLimits = PortDeskLimits.flexedByAvailableStaff(airportConfig, terminalEgatesProvider) _

      implicit val timeout: Timeout = new Timeout(10.seconds)

      if (config.getOptional[Boolean]("feature-flags.populate-historic-pax").getOrElse(false))
        PassengersLiveView.populateHistoricPax(populateLivePaxViewForDate)

      val (historicSplitsQueueActor, historicSplitsKillSwitch) = RunnableHistoricSplits(
        airportConfig.portCode,
        actorService.flightsRouterActor,
        splitsCalculator.splitsForManifest,
        manifestLookupService.maybeBestAvailableManifest,
      )

      val (historicPaxQueueActor, historicPaxKillSwitch) = RunnableHistoricPax(
        airportConfig.portCode,
        actorService.flightsRouterActor,
        manifestLookupService.maybeHistoricManifestPax,
      )

      val (mergeArrivalsRequestQueueActor: ActorRef, mergeArrivalsKillSwitch: UniqueKillSwitch) = RunnableMergedArrivals(
        portCode = airportConfig.portCode,
        flightsRouterActor = actorService.flightsRouterActor,
        mergeArrivalsQueueActor = actors.mergeArrivalsQueueActor,
        feedArrivalsForDate = ProdFeedService.arrivalFeedProvidersInOrder(feedService.activeFeedActorsWithPrimary),
        mergeArrivalsQueue = mergeArrivalsQueue,
        setPcpTimes = setPcpTimes,
        addArrivalPredictions = addArrivalPredictions,
      )

      val paxLoadsRequestQueueActor: ActorRef = DynamicRunnablePassengerLoads(
        crunchQueueActor = actors.crunchQueueActor,
        crunchQueue = crunchQueue,
        flightsProvider = OptimisationProviders.flightsWithSplitsProvider(actorService.flightsRouterActor),
        deskRecsProvider = portDeskRecs,
        redListUpdatesProvider = () => redListUpdatesActor.ask(GetState).mapTo[RedListUpdates],
        queueStatusProvider = DynamicQueueStatusProvider(airportConfig, egatesProvider),
        updateLivePaxView = updateLivePaxView,
        terminalSplits = splitsCalculator.terminalSplits,
        queueLoadsSinkActor = minuteLookups.queueLoadsMinutesActor,
        queuesByTerminal = airportConfig.queuesByTerminal,
        paxFeedSourceOrder = feedService.paxFeedSourceOrder,
        updateCapacity = updateAndPersistCapacity,
        setUpdatedAtForDay = DrtRunnableGraph.setUpdatedAt(
          airportConfig.portCode,
          aggregatedDb,
          _.paxLoadsUpdatedAt,
          (status, updatedAt) => status.copy(paxLoadsUpdatedAt = Option(updatedAt))),
      )

      val (deskRecsRequestQueueActor: ActorRef, deskRecsKillSwitch: UniqueKillSwitch) = DynamicRunnableDeskRecs(
        deskRecsQueueActor = actors.deskRecsQueueActor,
        deskRecsQueue = deskRecsQueue,
        paxProvider = OptimisationProviders.passengersProvider(minuteLookups.queueLoadsMinutesActor),
        deskLimitsProvider = deskLimitsProviders,
        terminalLoadsToQueueMinutes = portDeskRecs.terminalLoadsToDesks,
        queueMinutesSinkActor = minuteLookups.queueMinutesRouterActor,
        setUpdatedAtForDay = DrtRunnableGraph.setUpdatedAt(
          airportConfig.portCode,
          aggregatedDb,
          _.deskRecommendationsUpdatedAt,
          (status, updatedAt) => status.copy(deskRecommendationsUpdatedAt = Option(updatedAt))),
      )

      val (deploymentRequestQueueActor: ActorRef, deploymentsKillSwitch: UniqueKillSwitch) = DynamicRunnableDeployments(
        deploymentQueueActor = actors.deploymentQueueActor,
        deploymentQueue = deploymentQueue,
        staffToDeskLimits = staffToDeskLimits,
        paxProvider = OptimisationProviders.passengersProvider(minuteLookups.queueLoadsMinutesActor),
        staffMinutesProvider = OptimisationProviders.staffMinutesProvider(minuteLookups.staffMinutesRouterActor),
        loadsToDeployments = portDeskRecs.loadsToSimulations,
        queueMinutesSinkActor = minuteLookups.queueMinutesRouterActor,
        setUpdatedAtForDay = DrtRunnableGraph.setUpdatedAt(
          airportConfig.portCode,
          aggregatedDb,
          _.staffDeploymentsUpdatedAt,
          (status, updatedAt) => status.copy(staffDeploymentsUpdatedAt = Option(updatedAt))),
      )

      val (staffingUpdateRequestQueue: ActorRef, staffingUpdateKillSwitch: UniqueKillSwitch) = RunnableStaffing(
        staffingQueueActor = actors.staffingQueueActor,
        staffQueue = staffQueue,
        legacyStaffAssignmentsReadActor = actorService.legacyStaffAssignmentsReadActor,
        fixedPointsActor = actorService.liveFixedPointsReadActor,
        movementsActor = actorService.liveStaffMovementsReadActor,
        staffMinutesActor = minuteLookups.staffMinutesRouterActor,
        now = now,
        setUpdatedAtForDay = DrtRunnableGraph.setUpdatedAt(
          airportConfig.portCode,
          aggregatedDb,
          _.staffUpdatedAt,
          (status, updatedAt) => status.copy(staffUpdatedAt = Option(updatedAt))),
      )

      actorService.legacyStaffAssignmentsReadActor ! AddUpdatesSubscriber(staffingUpdateRequestQueue)
      actorService.liveFixedPointsReadActor ! AddUpdatesSubscriber(staffingUpdateRequestQueue)
      actorService.liveStaffMovementsReadActor ! AddUpdatesSubscriber(staffingUpdateRequestQueue)

      feedService.forecastBaseFeedArrivalsActor ! AddUpdatesSubscriber(mergeArrivalsRequestQueueActor)
      feedService.forecastFeedArrivalsActor ! AddUpdatesSubscriber(mergeArrivalsRequestQueueActor)
      feedService.liveBaseFeedArrivalsActor ! AddUpdatesSubscriber(mergeArrivalsRequestQueueActor)
      feedService.liveFeedArrivalsActor ! AddUpdatesSubscriber(mergeArrivalsRequestQueueActor)

      actorService.flightsRouterActor ! AddUpdatesSubscriber(paxLoadsRequestQueueActor)
      actorService.flightsRouterActor ! AddHistoricSplitsRequestActor(historicSplitsQueueActor)
      actorService.flightsRouterActor ! AddHistoricPaxRequestActor(historicPaxQueueActor)

      actorService.queueLoadsRouterActor ! AddUpdatesSubscriber(deskRecsRequestQueueActor)
      actorService.queueLoadsRouterActor ! AddUpdatesSubscriber(deploymentRequestQueueActor)

      actorService.staffRouterActor ! AddUpdatesSubscriber(deploymentRequestQueueActor)

      slasActor ! AddUpdatesSubscriber(deskRecsRequestQueueActor)
      slasActor ! AddUpdatesSubscriber(deploymentRequestQueueActor)

      egateBanksUpdatesActor ! AddUpdatesSubscriber(paxLoadsRequestQueueActor)

      crunchManagerActor ! AddQueueCrunchSubscriber(paxLoadsRequestQueueActor)
      crunchManagerActor ! AddRecalculateArrivalsSubscriber(mergeArrivalsRequestQueueActor)
      crunchManagerActor ! AddQueueHistoricSplitsLookupSubscriber(historicSplitsQueueActor)
      crunchManagerActor ! AddQueueHistoricPaxLookupSubscriber(historicPaxQueueActor)

      val delayUntilTomorrow = (SDate.now().getLocalNextMidnight.millisSinceEpoch - SDate.now().millisSinceEpoch) + MilliTimes.oneHourMillis
      log.info(s"Scheduling next day staff calculations to begin at ${delayUntilTomorrow / 1000}s -> ${SDate.now().addMillis(delayUntilTomorrow).toISOString}")

      val staffChecker = StaffMinutesChecker(now, staffingUpdateRequestQueue, params.forecastMaxDays, airportConfig)

      system.scheduler.scheduleAtFixedRate(delayUntilTomorrow.millis, 1.day)(() => staffChecker.calculateForecastStaffMinutes())

      val killSwitches = Iterable(mergeArrivalsKillSwitch, historicSplitsKillSwitch, historicPaxKillSwitch,
        deskRecsKillSwitch, deploymentsKillSwitch, staffingUpdateKillSwitch)

      (mergeArrivalsRequestQueueActor, paxLoadsRequestQueueActor, deskRecsRequestQueueActor, deploymentRequestQueueActor, killSwitches)
    }

  val terminalEgatesProvider: Terminal => Future[EgateBanksUpdates] = EgateBanksUpdatesActor.terminalEgatesProvider(egateBanksUpdatesActor)

  val deskLimitsProviders: Map[Terminal, TerminalDeskLimitsLike] = if (config.get[Boolean]("crunch.flex-desks"))
    PortDeskLimits.flexed(airportConfig, terminalEgatesProvider)
  else
    PortDeskLimits.fixed(airportConfig, terminalEgatesProvider)

  def startCrunchSystem(startUpdateGraphs: () => (ActorRef, ActorRef, ActorRef, ActorRef, Iterable[UniqueKillSwitch]),
                       ): CrunchSystem[typed.ActorRef[FeedTick]] =
    CrunchSystem(CrunchProps(
      portStateActor = actorService.portStateActor,
      maxDaysToCrunch = params.forecastMaxDays,
      now = now,
      feedActors = feedService.feedActors,
      updateFeedStatus = feedService.updateFeedStatus,
      arrivalsForecastBaseFeed = feedService.baseArrivalsSource(feedService.maybeAclFeed),
      arrivalsForecastFeed = feedService.forecastArrivalsSource(airportConfig.portCode),
      arrivalsLiveBaseFeed = feedService.liveBaseArrivalsSource(airportConfig.portCode),
      arrivalsLiveFeed = feedService.liveArrivalsSource(airportConfig.portCode),
      passengerAdjustments = PaxDeltas.applyAdjustmentsToArrivals(passengersActorProvider, aclPaxAdjustmentDays),
      startDeskRecs = startUpdateGraphs,
      system = system,
    ))

  val persistManifests: ManifestsFeedResponse => Future[Done] = ManifestPersistence.processManifestFeedResponse(
    persistentStateActors.manifestsRouterActor,
    actorService.flightsRouterActor,
    splitsCalculator.splitsForManifest,
  )

  val arrivalStatsDao: ArrivalStatsDao = ArrivalStatsDao(aggregatedDb, now, airportConfig.portCode)

  private val daysInYear = 365
  private val retentionPeriod: FiniteDuration = (params.retainDataForYears * daysInYear).days
  val retentionHandler: DataRetentionHandler = DataRetentionHandler(
    retentionPeriod, params.forecastMaxDays, airportConfig.terminals, now, akkaDb, aggregatedDb)
  val dateIsSafeToPurge: UtcDate => Boolean = DataRetentionHandler.dateIsSafeToPurge(retentionPeriod, now)
  val latestDateToPurge: () => UtcDate = DataRetentionHandler.latestDateToPurge(retentionPeriod, now)

  def run(): Unit = {
    val actors = persistentStateActors

    val futurePortStates =
      for {
        mergeArrivalsQueue <- actors.mergeArrivalsQueueActor.ask(GetState).mapTo[SortedSet[TerminalUpdateRequest]]
        crunchQueue <- actors.crunchQueueActor.ask(GetState).mapTo[SortedSet[TerminalUpdateRequest]]
        deskRecsQueue <- actors.deskRecsQueueActor.ask(GetState).mapTo[SortedSet[TerminalUpdateRequest]]
        deploymentQueue <- actors.deploymentQueueActor.ask(GetState).mapTo[SortedSet[TerminalUpdateRequest]]
        staffingUpdateQueue <- actors.staffingQueueActor.ask(GetState).mapTo[SortedSet[TerminalUpdateRequest]]
      } yield (mergeArrivalsQueue, crunchQueue, deskRecsQueue, deploymentQueue, staffingUpdateQueue)

    futurePortStates.onComplete {
      case Success((mergeArrivalsQueue, crunchQueue, deskRecsQueue, deploymentQueue, staffingUpdateQueue)) =>
        system.log.info(s"Successfully restored initial state for App")

        val crunchInputs = startCrunchSystem(
          startUpdateGraphs(actors, mergeArrivalsQueue, crunchQueue, deskRecsQueue, deploymentQueue, staffingUpdateQueue)
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

        system.scheduler.scheduleAtFixedRate(0.millis, 1.minute)(ApiValidityReporter(feedService.flightLookups.flightsRouterActor))

        if (params.enablePreRetentionPeriodDataDeletion) {
          system.scheduler.scheduleAtFixedRate(0.millis, 1.day) { () =>
            log.info("Purging data outside retention period")
            retentionHandler.purgeDataOutsideRetentionPeriod()
          }
        }

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
