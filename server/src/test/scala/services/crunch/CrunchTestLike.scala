package services.crunch

import actors._
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.AskableActorRef
import akka.stream.QueueOfferResult.Enqueued
import akka.stream.scaladsl.{Source, SourceQueueWithComplete}
import akka.stream.{ActorMaterializer, OverflowStrategy, QueueOfferResult}
import akka.testkit.{TestKit, TestProbe}
import drt.shared.CrunchApi._
import drt.shared.FlightsApi.{Flights, FlightsWithSplits, QueueName, TerminalName}
import drt.shared.PaxTypesAndQueues._
import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios, SplitSources}
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import org.specs2.mutable.SpecificationLike
import passengersplits.AkkaPersistTestConfig
import services._
import services.graphstages.Crunch._
import services.graphstages.{ActualDeskStats, Crunch, DqManifests, DummySplitsPredictor}

import scala.concurrent.Await
//import scala.language.postfixOps
import scala.concurrent.duration._


class LiveCrunchStateTestActor(name: String = "", queues: Map[TerminalName, Seq[QueueName]], probe: ActorRef, now: () => SDateLike, expireAfterMillis: Long)
  extends CrunchStateActor(1, s"live-test-$name", queues, now, expireAfterMillis, false) {
  override def updateStateFromPortState(cs: PortState): Unit = {
    log.info(s"calling parent updateState...")
    super.updateStateFromPortState(cs)

    probe ! state.get
  }
}

class ForecastCrunchStateTestActor(name: String = "", queues: Map[TerminalName, Seq[QueueName]], probe: ActorRef, now: () => SDateLike, expireAfterMillis: Long)
  extends CrunchStateActor(1, s"forecast-test-$name", queues, now, expireAfterMillis, false) {
  override def updateStateFromPortState(cs: PortState): Unit = {
    log.info(s"calling parent updateState...")
    super.updateStateFromPortState(cs)

    probe ! state.get
  }
}

case class CrunchGraph(baseArrivalsInput: SourceQueueWithComplete[Flights],
                       forecastArrivalsInput: SourceQueueWithComplete[Flights],
                       liveArrivalsInput: SourceQueueWithComplete[Flights],
                       manifestsInput: SourceQueueWithComplete[DqManifests],
                       liveShiftsInput: SourceQueueWithComplete[String],
                       liveFixedPointsInput: SourceQueueWithComplete[String],
                       liveStaffMovementsInput: SourceQueueWithComplete[Seq[StaffMovement]],
                       forecastShiftsInput: SourceQueueWithComplete[String],
                       forecastFixedPointsInput: SourceQueueWithComplete[String],
                       forecastStaffMovementsInput: SourceQueueWithComplete[Seq[StaffMovement]],
                       actualDesksAndQueuesInput: SourceQueueWithComplete[ActualDeskStats],
                       liveCrunchActor: ActorRef,
                       forecastCrunchActor: ActorRef,
                       liveTestProbe: TestProbe,
                       forecastTestProbe: TestProbe)

class CrunchTestLike
  extends TestKit(ActorSystem("StreamingCrunchTests", AkkaPersistTestConfig.inMemoryAkkaPersistConfig))
    with SpecificationLike {
  isolated
  sequential

  implicit val actorSystem: ActorSystem = system
  implicit val materializer: ActorMaterializer = ActorMaterializer()

  val log: Logger = LoggerFactory.getLogger(getClass)

  val oneMinuteMillis = 60000
  val uniquifyArrivals: (Seq[ApiFlightWithSplits]) => List[(ApiFlightWithSplits, Set[Arrival])] =
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
    firstPaxOffMillis = 180000L
  )

  val splitsPredictorStage = new DummySplitsPredictor()

  val pcpForFlight: (Arrival) => MilliDate = (a: Arrival) => MilliDate(SDate(a.SchDT).millisSinceEpoch)

  def liveCrunchStateActor(name: String = "", testProbe: TestProbe, now: () => SDateLike): ActorRef = system.actorOf(Props(classOf[LiveCrunchStateTestActor], name, airportConfig.queues, testProbe.ref, now, 2 * oneDayMillis), name = "crunch-live-state-actor")

  def forecastCrunchStateActor(name: String = "", testProbe: TestProbe, now: () => SDateLike): ActorRef = system.actorOf(Props(classOf[ForecastCrunchStateTestActor], name, airportConfig.queues, testProbe.ref, now, 2 * oneDayMillis), name = "crunch-forecast-state-actor")

  def testProbe(name: String) = TestProbe(name = name)

  def runCrunchGraph(initialBaseArrivals: Set[Arrival] = Set(),
                     initialForecastArrivals: Set[Arrival] = Set(),
                     initialLiveArrivals: Set[Arrival] = Set(),
                     initialManifests: DqManifests = DqManifests("", Set()),
                     initialPortState: Option[PortState] = None,
                     airportConfig: AirportConfig = airportConfig,
                     csvSplitsProvider: SplitsProvider.SplitProvider = (_, _) => None,
                     pcpArrivalTime: (Arrival) => MilliDate = pcpForFlight,
                     minutesToCrunch: Int = 60,
                     calcPcpWindow: (Set[ApiFlightWithSplits], Set[ApiFlightWithSplits]) => Option[(SDateLike, SDateLike)] = (_, _) => Some((SDate.now(), SDate.now())),
                     now: () => SDateLike,
                     initialShifts: String = "",
                     initialFixedPoints: String = "",
                     logLabel: String = "",
                     cruncher: TryCrunch = TestableCrunchLoadStage.mockCrunch,
                     simulator: Simulator = TestableCrunchLoadStage.mockSimulator
                    ): CrunchGraph = {

    val maxDaysToCrunch = 5
    val expireAfterMillis = 2 * oneDayMillis

    val liveProbe = testProbe("live")
    val forecastProbe = testProbe("forecast")
    val shiftsActor: ActorRef = system.actorOf(Props(classOf[ShiftsActor]))
    val fixedPointsActor: ActorRef = system.actorOf(Props(classOf[FixedPointsActor]))
    val staffMovementsActor: ActorRef = system.actorOf(Props(classOf[StaffMovementsActor]))
    val manifestsActor: ActorRef = system.actorOf(Props(classOf[VoyageManifestsActor], now, expireAfterMillis))

    val liveCrunchActor = liveCrunchStateActor(logLabel, liveProbe, now)
    val forecastCrunchActor = forecastCrunchStateActor(logLabel, forecastProbe, now)

    val manifestsSource: Source[DqManifests, SourceQueueWithComplete[DqManifests]] = Source.queue[DqManifests](0, OverflowStrategy.backpressure)

    val crunchInputs = CrunchSystem(CrunchProps(
      logLabel = logLabel,
      system = actorSystem,
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
        "staff-movements" -> staffMovementsActor),
      useNationalityBasedProcessingTimes = false,
      now = now,
      splitsPredictorStage = splitsPredictorStage,
      manifestsSource = manifestsSource,
      voyageManifestsActor = manifestsActor,
      cruncher = cruncher,
      simulator = simulator,
      initialPortState = initialPortState
    ))

    if (initialBaseArrivals.nonEmpty) offerAndWait(crunchInputs.baseArrivals, Flights(initialBaseArrivals.toList))
    if (initialForecastArrivals.nonEmpty) offerAndWait(crunchInputs.forecastArrivals, Flights(initialForecastArrivals.toList))
    if (initialLiveArrivals.nonEmpty) offerAndWait(crunchInputs.liveArrivals, Flights(initialLiveArrivals.toList))
    if (initialShifts.nonEmpty) offerAndWait(crunchInputs.shifts, initialShifts)
    if (initialFixedPoints.nonEmpty) offerAndWait(crunchInputs.fixedPoints, initialFixedPoints)
    if (initialManifests.manifests.nonEmpty) offerAndWait(crunchInputs.manifests, initialManifests)

    CrunchGraph(
      crunchInputs.baseArrivals,
      crunchInputs.forecastArrivals,
      crunchInputs.liveArrivals,
      crunchInputs.manifests,
      crunchInputs.shifts,
      crunchInputs.fixedPoints,
      crunchInputs.staffMovements,
      crunchInputs.shifts,
      crunchInputs.fixedPoints,
      crunchInputs.staffMovements,
      crunchInputs.actualDeskStats,
      liveCrunchActor,
      forecastCrunchActor,
      liveProbe,
      forecastProbe
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

  def getLastMessageReceivedBy(testProbe: TestProbe, timeDurationToWait: Duration): PortState = {
    testProbe
      .receiveWhile(timeDurationToWait) { case ps@PortState(_, _, _) => ps }
      .reverse
      .head
  }

  def offerAndWait[T](sourceQueue: SourceQueueWithComplete[T], offering: T): QueueOfferResult = {
    Await.result(sourceQueue.offer(offering), 5 seconds) match {
      case offerResult if offerResult != Enqueued =>
        throw new Exception(s"Queue offering (${offering.getClass}) was not enqueued: ${offerResult.getClass}")
      case offerResult =>
        offerResult
    }
  }
}

