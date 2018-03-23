package services.crunch

import actors.{GetState, StaffMovements}
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.AskableActorRef
import akka.stream.scaladsl.{Source, SourceQueueWithComplete}
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.util.Timeout
import drt.shared.CrunchApi.{CrunchMinutes, PortState, StaffMinutes}
import drt.shared.FlightsApi.{Flights, FlightsWithSplits}
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.core.SplitsCalculator
import services._
import services.graphstages.Crunch._
import services.graphstages._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success}


case class CrunchSystem[MS](shifts: SourceQueueWithComplete[String],
                            fixedPoints: SourceQueueWithComplete[String],
                            staffMovements: SourceQueueWithComplete[Seq[StaffMovement]],
                            baseArrivals: SourceQueueWithComplete[Flights],
                            forecastArrivals: SourceQueueWithComplete[Flights],
                            liveArrivals: SourceQueueWithComplete[Flights],
                            manifests: MS,
                            actualDeskStats: SourceQueueWithComplete[ActualDeskStats]
                           )

case class CrunchProps[MS](system: ActorSystem,
                           airportConfig: AirportConfig,
                           pcpArrival: Arrival => MilliDate,
                           historicalSplitsProvider: SplitsProvider.SplitProvider,
                           liveCrunchStateActor: ActorRef,
                           forecastCrunchStateActor: ActorRef,
                           maxDaysToCrunch: Int,
                           expireAfterMillis: Long,
                           crunchPeriodStartMillis: SDateLike => SDateLike,
                           minutesToCrunch: Int = 1440,
                           warmUpMinutes: Int = 0,
                           actors: Map[String, AskableActorRef],
                           useNationalityBasedProcessingTimes: Boolean,
                           now: () => SDateLike = () => SDate.now(),
                           initialFlightsWithSplits: Option[FlightsWithSplits] = None,
                           splitsPredictorStage: SplitsPredictorBase,
                           manifestsSource: Source[DqManifests, MS],
                           voyageManifestsActor: ActorRef
                           )

object CrunchSystem {

  val log: Logger = LoggerFactory.getLogger(getClass)

  def apply[MS](props: CrunchProps[MS]): CrunchSystem[MS] = {

    val baseArrivalsActor: ActorRef = props.system.actorOf(Props(classOf[ForecastBaseArrivalsActor]), name = "base-arrivals-actor")
    val forecastArrivalsActor: ActorRef = props.system.actorOf(Props(classOf[ForecastPortArrivalsActor]), name = "forecast-arrivals-actor")
    val liveArrivalsActor: ActorRef = props.system.actorOf(Props(classOf[LiveArrivalsActor]), name = "live-arrivals-actor")

    val askableLiveCrunchStateActor: AskableActorRef = props.liveCrunchStateActor
    val askableForecastCrunchStateActor: AskableActorRef = props.forecastCrunchStateActor

    val initialLivePortState = initialPortState(askableLiveCrunchStateActor)
    val initialForecastPortState = initialPortState(askableForecastCrunchStateActor)
    val initialMergedPs = mergePortStates(initialForecastPortState, initialLivePortState)
    val initialShifts = initialShiftsLikeState(props.actors("shifts"))
    val initialFixedPoints = initialShiftsLikeState(props.actors("fixed-points"))
    val initialStaffMovements = initialStaffMovementsState(props.actors("staff-movements"))

    val baseArrivals: Source[Flights, SourceQueueWithComplete[Flights]] = Source.queue[Flights](1, OverflowStrategy.backpressure)
    val forecastArrivals: Source[Flights, SourceQueueWithComplete[Flights]] = Source.queue[Flights](1, OverflowStrategy.backpressure)
    val liveArrivals: Source[Flights, SourceQueueWithComplete[Flights]] = Source.queue[Flights](1, OverflowStrategy.backpressure)
    val manifests = props.manifestsSource
    val shiftsSource: Source[String, SourceQueueWithComplete[String]] = Source.queue[String](1, OverflowStrategy.backpressure)
    val fixedPointsSource: Source[String, SourceQueueWithComplete[String]] = Source.queue[String](1, OverflowStrategy.backpressure)
    val staffMovementsSource: Source[Seq[StaffMovement], SourceQueueWithComplete[Seq[StaffMovement]]] = Source.queue[Seq[StaffMovement]](1, OverflowStrategy.backpressure)
    val actualDesksAndQueuesSource: Source[ActualDeskStats, SourceQueueWithComplete[ActualDeskStats]] = Source.queue[ActualDeskStats](1, OverflowStrategy.backpressure)

    val splitsCalculator = SplitsCalculator(props.airportConfig.portCode, props.historicalSplitsProvider, props.airportConfig.defaultPaxSplits.splits.toSet)
    val groupFlightsByCodeShares = CodeShares.uniqueArrivalsWithCodeShares((f: ApiFlightWithSplits) => f.apiFlight) _

    val arrivalsStage = new ArrivalsGraphStage(
      name = "-",
      initialBaseArrivals = initialArrivals(baseArrivalsActor),
      initialForecastArrivals = initialArrivals(forecastArrivalsActor),
      initialLiveArrivals = initialArrivals(liveArrivalsActor),
      pcpArrivalTime = props.pcpArrival,
      validPortTerminals = props.airportConfig.terminalNames.toSet,
      expireAfterMillis = props.expireAfterMillis,
      now = props.now)

    val arrivalSplitsGraphStage = new ArrivalSplitsGraphStage(
      props.initialFlightsWithSplits,
      splitsCalculator,
      groupFlightsByCodeShares,
      props.expireAfterMillis,
      props.now,
      props.maxDaysToCrunch)

    val splitsPredictorStage = props.splitsPredictorStage

    val staffGraphStage = new StaffGraphStage(
      Option(initialShifts),
      Option(initialFixedPoints),
      Option(initialStaffMovements),
      props.now,
      props.airportConfig,
      180)

    val workloadGraphStage = new WorkloadGraphStage(
      initialMergedPs.map(ps => Loads(ps.crunchMinutes.values.toSeq)),
      props.initialFlightsWithSplits,
      props.airportConfig,
      AirportConfigs.nationalityProcessingTimes,
      props.expireAfterMillis,
      props.now,
      props.useNationalityBasedProcessingTimes)

    val maybeCrunchMinutes = initialMergedPs.map(ps => CrunchMinutes(ps.crunchMinutes.values.toSet))
    val crunchLoadGraphStage = new CrunchLoadGraphStage(
      maybeCrunchMinutes,
      props.airportConfig,
      props.expireAfterMillis,
      props.now,
      TryRenjin.crunch,
      props.crunchPeriodStartMillis,
      props.minutesToCrunch)

    val maybeStaffMinutes = initialMergedPs.map(ps => StaffMinutes(ps.staffMinutes))

    val simulationGraphStage = new SimulationGraphStage(
      maybeCrunchMinutes,
      maybeStaffMinutes,
      props.airportConfig,
      props.expireAfterMillis,
      props.now,
      TryRenjin.runSimulationOfWork,
      props.crunchPeriodStartMillis,
      props.minutesToCrunch
    )

    val crunchSystem = Crunch2(
      baseArrivals, forecastArrivals, liveArrivals, manifests, shiftsSource, fixedPointsSource, staffMovementsSource, actualDesksAndQueuesSource,
      arrivalsStage, arrivalSplitsGraphStage, splitsPredictorStage, workloadGraphStage, crunchLoadGraphStage, staffGraphStage, simulationGraphStage, props.liveCrunchStateActor, props.forecastCrunchStateActor,
      props.crunchPeriodStartMillis, props.now
    )

    implicit val actorSystem: ActorSystem = props.system
    implicit val materializer: ActorMaterializer = ActorMaterializer()
    val (baseIn, fcstIn, liveIn, manifestsIn, shiftsIn, fixedPointsIn, movementsIn, actDesksIn) = crunchSystem.run

    CrunchSystem(
      shifts = shiftsIn,
      fixedPoints = fixedPointsIn,
      staffMovements = movementsIn,
      baseArrivals = baseIn,
      forecastArrivals = fcstIn,
      liveArrivals = liveIn,
      manifests = manifestsIn,
      actualDeskStats = actDesksIn
    )
  }

  def mergePortStates(maybeForecastPs: Option[PortState], maybeLivePs: Option[PortState]): Option[PortState] = (maybeForecastPs, maybeLivePs) match {
    case (None, None) => None
    case (Some(fps), None) => Option(fps)
    case (None, Some(lps)) => Option(lps)
    case (Some(fps), Some(lps)) =>
      Option(PortState(
        fps.flights ++ lps.flights,
        fps.crunchMinutes ++ lps.crunchMinutes,
        fps.staffMinutes ++ lps.staffMinutes))
  }

  def initialPortState(askableCrunchStateActor: AskableActorRef): Option[PortState] = {
    Await.result(askableCrunchStateActor.ask(GetState)(new Timeout(5 minutes)).map {
      case Some(ps: PortState) =>
        log.info(s"Got an initial port state from ${askableCrunchStateActor.toString} with ${ps.staffMinutes.size} staff minutes, ${ps.crunchMinutes.size} crunch minutes, and ${ps.flights.size} flights")
        Option(ps)
      case _ =>
        log.info(s"Got no initial port state from ${askableCrunchStateActor.toString}")
        None
    }, 5 minutes)
  }

  def initialShiftsLikeState(askableShiftsLikeActor: AskableActorRef): String = {
    Await.result(askableShiftsLikeActor.ask(GetState)(new Timeout(5 minutes)).map {
      case shifts: String if shifts.nonEmpty =>
        log.info(s"Got initial state from ${askableShiftsLikeActor.toString}")
        shifts
      case _ =>
        log.info(s"Got no initial state from ${askableShiftsLikeActor.toString}")
        ""
    }, 5 minutes)
  }

  def initialStaffMovementsState(askableStaffMovementsActor: AskableActorRef): Seq[StaffMovement] = {
    Await.result(askableStaffMovementsActor.ask(GetState)(new Timeout(5 minutes)).map {
      case StaffMovements(mms) if mms.nonEmpty =>
        log.info(s"Got initial state from ${askableStaffMovementsActor.toString}")
        mms
      case _ =>
        log.info(s"Got no initial state from ${askableStaffMovementsActor.toString}")
        Seq()
    }, 5 minutes)
  }

  def initialArrivals(arrivalsActor: AskableActorRef): Set[Arrival] = {
    val canWaitMinutes = 5
    val arrivalsFuture: Future[Set[Arrival]] = arrivalsActor.ask(GetState)(new Timeout(canWaitMinutes minutes)).map {
      case ArrivalsState(arrivals) => arrivals.values.toSet
      case _ => Set[Arrival]()
    }
    arrivalsFuture.onComplete {
      case Success(arrivals) => arrivals
      case Failure(t) =>
        log.warn(s"Failed to get an initial ArrivalsState: $t")
        Set[Arrival]()
    }
    Await.result(arrivalsFuture, canWaitMinutes minutes)
  }
}
