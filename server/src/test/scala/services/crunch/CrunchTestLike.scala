package services.crunch

import java.util.UUID

import actors.Sizes.oneMegaByte
import actors._
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.AskableActorRef
import akka.stream.QueueOfferResult.Enqueued
import akka.stream.scaladsl.{Source, SourceQueueWithComplete}
import akka.stream.{ActorMaterializer, OverflowStrategy, QueueOfferResult}
import akka.testkit.{TestKit, TestProbe}
import drt.shared.CrunchApi._
import drt.shared.FlightsApi.{QueueName, TerminalName}
import drt.shared.PaxTypesAndQueues._
import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios, SplitSources}
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import org.specs2.mutable.SpecificationLike
import passengersplits.InMemoryPersistence
import server.feeds.{ArrivalsFeedResponse, ManifestsFeedResponse}
import server.protobuf.messages.CrunchState.CrunchDiffMessage
import services._
import services.graphstages.Crunch._
import services.graphstages.{DummySplitsPredictor, TestableCrunchLoadStage}
import slickdb.Tables

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._
import scala.language.implicitConversions
import scala.util.Success


class LiveCrunchStateTestActor(name: String = "", queues: Map[TerminalName, Seq[QueueName]], probe: ActorRef, now: () => SDateLike, expireAfterMillis: Long)
  extends CrunchStateActor(None, oneMegaByte, s"live-test-$name", queues, now, expireAfterMillis, false) {
  override def applyDiff(cdm: CrunchDiffMessage): Unit = {
    log.info(s"calling parent updateState...")
    super.applyDiff(cdm)

    probe ! state.immutable
  }
}

class ForecastCrunchStateTestActor(name: String = "", queues: Map[TerminalName, Seq[QueueName]], probe: ActorRef, now: () => SDateLike, expireAfterMillis: Long)
  extends CrunchStateActor(None, oneMegaByte, s"forecast-test-$name", queues, now, expireAfterMillis, false) {
  override def applyDiff(cdm: CrunchDiffMessage): Unit = {
    log.info(s"calling parent updateState...")
    super.applyDiff(cdm)

    probe ! state.immutable
  }
}

case class CrunchGraphInputsAndProbes(baseArrivalsInput: SourceQueueWithComplete[ArrivalsFeedResponse],
                                      forecastArrivalsInput: SourceQueueWithComplete[ArrivalsFeedResponse],
                                      liveArrivalsInput: SourceQueueWithComplete[ArrivalsFeedResponse],
                                      manifestsLiveInput: SourceQueueWithComplete[ManifestsFeedResponse],
                                      manifestsHistoricInput: SourceQueueWithComplete[ManifestsFeedResponse],
                                      shiftsInput: SourceQueueWithComplete[ShiftAssignments],
                                      fixedPointsInput: SourceQueueWithComplete[FixedPointAssignments],
                                      liveStaffMovementsInput: SourceQueueWithComplete[Seq[StaffMovement]],
                                      forecastStaffMovementsInput: SourceQueueWithComplete[Seq[StaffMovement]],
                                      actualDesksAndQueuesInput: SourceQueueWithComplete[ActualDeskStats],
                                      liveCrunchActor: ActorRef,
                                      forecastCrunchActor: ActorRef,
                                      liveTestProbe: TestProbe,
                                      forecastTestProbe: TestProbe,
                                      baseArrivalsTestProbe: TestProbe,
                                      forecastArrivalsTestProbe: TestProbe,
                                      liveArrivalsTestProbe: TestProbe,
                                      aggregatedArrivalsActor: ActorRef)



object H2Tables extends {
  val profile = slick.jdbc.H2Profile
} with Tables

class CrunchTestLike
  extends TestKit(ActorSystem("StreamingCrunchTests", InMemoryPersistence.akkaAndAggregateDbConfig))
    with SpecificationLike {
  isolated
  sequential

  implicit val actorSystem: ActorSystem = system
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val ec = ExecutionContext.global

  val log: Logger = LoggerFactory.getLogger(getClass)

  val oneMinuteMillis = 60000
  val uniquifyArrivals: Seq[ApiFlightWithSplits] => List[(ApiFlightWithSplits, Set[Arrival])] =
    CodeShares.uniqueArrivalsWithCodeShares((f: ApiFlightWithSplits) => f.apiFlight)

  val airportConfig = AirportConfig(
    portCode = "STN",
    queues = Map("T1" -> Seq(Queues.EeaDesk, Queues.NonEeaDesk), "T2" -> Seq(Queues.EeaDesk, Queues.NonEeaDesk), "T2" -> Seq(Queues.EeaDesk)),
    slaByQueue = Map(Queues.EeaDesk -> 25, Queues.EGate -> 20, Queues.NonEeaDesk -> 45),
    terminalNames = Seq("T1", "T2"),
    defaultWalkTimeMillis = Map(),
    defaultPaxSplits = SplitRatios(
      SplitSources.TerminalAverage,
      SplitRatio(eeaMachineReadableToDesk, 1)
    ),
    defaultProcessingTimes = Map(
      "T1" -> Map(
        eeaMachineReadableToDesk -> 25d / 60,
        eeaNonMachineReadableToDesk -> 25d / 60
      ),
      "T2" -> Map(
        eeaMachineReadableToDesk -> 25d / 60,
        eeaNonMachineReadableToDesk -> 25d / 60
      )
    ),
    minMaxDesksByTerminalQueue = Map(
      "T1" -> Map(
        Queues.EeaDesk -> ((List.fill[Int](24)(1), List.fill[Int](24)(20))),
        Queues.NonEeaDesk -> ((List.fill[Int](24)(1), List.fill[Int](24)(20))),
        Queues.EGate -> ((List.fill[Int](24)(1), List.fill[Int](24)(20)))),
      "T2" -> Map(
        Queues.EeaDesk -> ((List.fill[Int](24)(1), List.fill[Int](24)(20))),
        Queues.NonEeaDesk -> ((List.fill[Int](24)(1), List.fill[Int](24)(20))),
        Queues.EGate -> ((List.fill[Int](24)(1), List.fill[Int](24)(20))))),
    timeToChoxMillis = 120000L,
    firstPaxOffMillis = 180000L,
    role = STNAccess,
    terminalPaxTypeQueueAllocation = Map("T1" -> AirportConfigs.defaultQueueRatios)
  )

  val splitsPredictorStage = new DummySplitsPredictor()

  val pcpForFlightFromSch: Arrival => MilliDate = (a: Arrival) => MilliDate(SDate(a.Scheduled).millisSinceEpoch)
  val pcpForFlightFromBest: Arrival => MilliDate = (a: Arrival) => {
    if (a.ActualChox.isDefined) MilliDate(SDate(a.ActualChox.get).millisSinceEpoch)
    else if (a.EstimatedChox.isDefined) MilliDate(SDate(a.EstimatedChox.get).millisSinceEpoch)
    else if (a.Actual.isDefined) MilliDate(SDate(a.Actual.get).millisSinceEpoch)
    else if (a.Estimated.isDefined) MilliDate(SDate(a.Estimated.get).millisSinceEpoch)
    else MilliDate(SDate(a.Scheduled).millisSinceEpoch)
  }

  def liveCrunchStateActor(name: String = "", testProbe: TestProbe, now: () => SDateLike): ActorRef = system.actorOf(Props(classOf[LiveCrunchStateTestActor], name, airportConfig.queues, testProbe.ref, now, 2 * oneDayMillis), name = "crunch-live-state-actor" + UUID.randomUUID().toString)

  def forecastCrunchStateActor(name: String = "", testProbe: TestProbe, now: () => SDateLike): ActorRef = system.actorOf(Props(classOf[ForecastCrunchStateTestActor], name, airportConfig.queues, testProbe.ref, now, 2 * oneDayMillis), name = "crunch-forecast-state-actor")

  def testProbe(name: String) = TestProbe(name = name)

  def runCrunchGraph(initialBaseArrivals: Set[Arrival] = Set(),
                     initialForecastArrivals: Set[Arrival] = Set(),
                     initialLiveArrivals: Set[Arrival] = Set(),
                     initialPortState: Option[PortState] = None,
                     airportConfig: AirportConfig = airportConfig,
                     csvSplitsProvider: SplitsProvider.SplitProvider = (_, _) => None,
                     pcpArrivalTime: Arrival => MilliDate = pcpForFlightFromSch,
                     minutesToCrunch: Int = 60,
                     expireAfterMillis: Long = DrtStaticParameters.expireAfterMillis,
                     calcPcpWindow: (Set[ApiFlightWithSplits], Set[ApiFlightWithSplits]) => Option[(SDateLike, SDateLike)] = (_, _) => Some((SDate.now(), SDate.now())),
                     now: () => SDateLike,
                     initialShifts: ShiftAssignments = ShiftAssignments.empty,
                     initialFixedPoints: FixedPointAssignments = FixedPointAssignments.empty,
                     initialStaffMovements: Seq[StaffMovement] = Seq(),
                     logLabel: String = "",
                     cruncher: TryCrunch = TestableCrunchLoadStage.mockCrunch,
                     simulator: Simulator = TestableCrunchLoadStage.mockSimulator,
                     aggregatedArrivalsActor: ActorRef = testProbe("aggregated-arrivals").ref,
                     useLegacyManifests: Boolean = false,
                     maxDaysToCrunch: Int = 2,
                     checkRequiredStaffUpdatesOnStartup: Boolean = false
                    ): CrunchGraphInputsAndProbes = {

    val liveProbe = testProbe("live")
    val forecastProbe = testProbe("forecast")
    val baseArrivalsProbe = testProbe("base-arrivals")
    val forecastArrivalsProbe = testProbe("forecast-arrivals")
    val liveArrivalsProbe = testProbe("live-arrivals")

    val shiftsActor: ActorRef = system.actorOf(Props(classOf[ShiftsActor], now, DrtStaticParameters.timeBeforeThisMonth(now)))
    val fixedPointsActor: ActorRef = system.actorOf(Props(classOf[FixedPointsActor], now))
    val staffMovementsActor: ActorRef = system.actorOf(Props(classOf[StaffMovementsActor], now, DrtStaticParameters.time48HoursAgo(now)))
    val snapshotInterval = 1
    val manifestsActor: ActorRef = system.actorOf(Props(classOf[VoyageManifestsActor], oneMegaByte, now, DrtStaticParameters.expireAfterMillis, Option(snapshotInterval)))
    val manifestsRequestActor: ActorRef = testProbe("manifests-request").ref

    val liveCrunchActor = liveCrunchStateActor(logLabel, liveProbe, now)
    val forecastCrunchActor = forecastCrunchStateActor(logLabel, forecastProbe, now)

    val manifestsSource: Source[ManifestsFeedResponse, SourceQueueWithComplete[ManifestsFeedResponse]] = Source.queue[ManifestsFeedResponse](0, OverflowStrategy.backpressure)
    val liveArrivals: Source[ArrivalsFeedResponse, SourceQueueWithComplete[ArrivalsFeedResponse]] = Source.queue[ArrivalsFeedResponse](0, OverflowStrategy.backpressure)
    val fcstArrivals: Source[ArrivalsFeedResponse, SourceQueueWithComplete[ArrivalsFeedResponse]] = Source.queue[ArrivalsFeedResponse](0, OverflowStrategy.backpressure)
    val baseArrivals: Source[ArrivalsFeedResponse, SourceQueueWithComplete[ArrivalsFeedResponse]] = Source.queue[ArrivalsFeedResponse](0, OverflowStrategy.backpressure)

    val crunchInputs = CrunchSystem(CrunchProps(
      logLabel = logLabel,
      airportConfig = airportConfig,
      pcpArrival = pcpArrivalTime,
      historicalSplitsProvider = csvSplitsProvider,
      liveCrunchStateActor = liveCrunchActor,
      forecastCrunchStateActor = forecastCrunchActor,
      maxDaysToCrunch = maxDaysToCrunch,
      expireAfterMillis = expireAfterMillis,
      minutesToCrunch = minutesToCrunch,
      actors = Map[String, AskableActorRef](
        "shifts" -> shiftsActor,
        "fixed-points" -> fixedPointsActor,
        "staff-movements" -> staffMovementsActor,
        "base-arrivals" -> baseArrivalsProbe.ref,
        "forecast-arrivals" -> forecastArrivalsProbe.ref,
        "live-arrivals" -> liveArrivalsProbe.ref,
        "aggregated-arrivals" -> aggregatedArrivalsActor
      ),
      useNationalityBasedProcessingTimes = false,
      useLegacyManifests = useLegacyManifests,
      now = now,
      splitsPredictorStage = splitsPredictorStage,
      b5JStartDate = SDate("2019-06-01"),
      manifestsLiveSource = manifestsSource,
      manifestsHistoricSource = manifestsSource,
      voyageManifestsActor = manifestsActor,
      voyageManifestsRequestActor = manifestsRequestActor,
      cruncher = cruncher,
      simulator = simulator,
      initialPortState = initialPortState,
      initialBaseArrivals = initialBaseArrivals,
      initialFcstArrivals = initialForecastArrivals,
      initialLiveArrivals = initialLiveArrivals,
      arrivalsBaseSource = baseArrivals,
      arrivalsFcstSource = fcstArrivals,
      arrivalsLiveSource = liveArrivals,
      initialShifts = initialShifts,
      initialFixedPoints = initialFixedPoints,
      initialStaffMovements = initialStaffMovements,
      checkRequiredStaffUpdatesOnStartup = checkRequiredStaffUpdatesOnStartup
    ))

    CrunchGraphInputsAndProbes(
      crunchInputs.baseArrivalsResponse,
      crunchInputs.forecastArrivalsResponse,
      crunchInputs.liveArrivalsResponse,
      crunchInputs.manifestsLiveResponse,
      crunchInputs.manifestsHistoricResponse,
      crunchInputs.shifts,
      crunchInputs.fixedPoints,
      crunchInputs.staffMovements,
      crunchInputs.staffMovements,
      crunchInputs.actualDeskStats,
      liveCrunchActor,
      forecastCrunchActor,
      liveProbe,
      forecastProbe,
      baseArrivalsProbe,
      forecastArrivalsProbe,
      liveArrivalsProbe,
      aggregatedArrivalsActor
    )
  }

  def paxLoadsFromPortState(portState: PortState, minsToTake: Int, startFromMinuteIdx: Int = 0): Map[TerminalName, Map[QueueName, List[Double]]] = portState
    .crunchMinutes
    .values
    .groupBy(_.terminalName)
    .map {
      case (tn, tms) =>
        val terminalLoads = tms
          .groupBy(_.queueName)
          .map {
            case (qn, qms) =>
              val paxLoad = qms
                .toList
                .sortBy(_.minute)
                .map(_.paxLoad)
                .slice(startFromMinuteIdx, startFromMinuteIdx + minsToTake)
              (qn, paxLoad)
          }
        (tn, terminalLoads)
    }

  def paxLoadsFromPortState(portState: PortState, minsToTake: Int, startFromMinute: SDateLike): Map[TerminalName, Map[QueueName, List[Double]]] = {
    val startFromMillis = startFromMinute.millisSinceEpoch

    portState
      .crunchMinutes
      .values
      .groupBy(_.terminalName)
      .map {
        case (tn, tms) =>
          val terminalLoads = tms
            .groupBy(_.queueName)
            .map {
              case (qn, qms) =>
                val startIdx = qms
                  .toList
                  .sortBy(_.minute)
                  .indexWhere(_.minute == startFromMillis)
                val paxLoad = qms
                  .toList
                  .sortBy(_.minute)
                  .map(_.paxLoad)
                  .slice(startIdx, startIdx + minsToTake)
                (qn, paxLoad)
            }
          (tn, terminalLoads)
      }
  }

  def allWorkLoadsFromPortState(portState: PortState): Map[TerminalName, Map[QueueName, List[Double]]] = portState
    .crunchMinutes
    .values
    .groupBy(_.terminalName)
    .map {
      case (tn, tms) =>
        val terminalLoads = tms
          .groupBy(_.queueName)
          .map {
            case (qn, qms) =>
              val sortedCms = qms.toList.sortBy(_.minute)
              val workLoad = sortedCms.map(_.workLoad)
              (qn, workLoad)
          }
        (tn, terminalLoads)
    }

  def workLoadsFromPortState(portState: PortState, minsToTake: Int): Map[TerminalName, Map[QueueName, List[Double]]] = portState
    .crunchMinutes
    .values
    .groupBy(_.terminalName)
    .map {
      case (tn, tms) =>
        val terminalLoads = tms
          .groupBy(_.queueName)
          .map {
            case (qn, qms) =>
              val sortedCms = qms.toList.sortBy(_.minute)
              val workLoad = sortedCms.map(_.workLoad).take(minsToTake)
              (qn, workLoad)
          }
        (tn, terminalLoads)
    }

  def deskRecsFromPortState(portState: PortState, minsToTake: Int): Map[TerminalName, Map[QueueName, List[Int]]] = portState
    .crunchMinutes
    .values
    .groupBy(_.terminalName)
    .map {
      case (tn, tms) =>
        val terminalLoads = tms
          .groupBy(_.queueName)
          .map {
            case (qn, qms) =>
              val sortedCms = qms.toList.sortBy(_.minute)
              val deskRecs = sortedCms.map(_.deskRec).take(minsToTake)
              (qn, deskRecs)
          }
        (tn, terminalLoads)
    }

  def offerAndWait[T](sourceQueue: SourceQueueWithComplete[T], offering: T): QueueOfferResult = {
    Await.result(sourceQueue.offer(offering), 3 seconds) match {
      case offerResult if offerResult != Enqueued =>
        throw new Exception(s"Queue offering (${offering.getClass}) was not enqueued: ${offerResult.getClass}")
      case offerResult =>
        offerResult
    }
  }
}

