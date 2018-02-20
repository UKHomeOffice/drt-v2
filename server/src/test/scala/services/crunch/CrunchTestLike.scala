package services.crunch

import actors.{CrunchStateActor, FixedPointsActor, ShiftsActor, StaffMovementsActor}
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.AskableActorRef
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.SourceQueueWithComplete
import akka.testkit.{TestKit, TestProbe}
import drt.shared.CrunchApi._
import drt.shared.FlightsApi.{Flights, FlightsWithSplits, QueueName, TerminalName}
import drt.shared.PaxTypesAndQueues._
import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios, SplitSources}
import drt.shared._
import org.apache.spark.sql.SparkSession
import org.slf4j.{Logger, LoggerFactory}
import org.specs2.mutable.SpecificationLike
import passengersplits.AkkaPersistTestConfig
import passengersplits.parsing.VoyageManifestParser.VoyageManifests
import services._
import services.crunch.CrunchSystem.CrunchProps
import services.graphstages.Crunch._
import services.graphstages.{DummySplitsPredictor, SplitsPredictorBase, SplitsPredictorStage}

import scala.collection.immutable
import scala.language.postfixOps
import scala.concurrent.duration._


class LiveCrunchStateTestActor(queues: Map[TerminalName, Seq[QueueName]], probe: ActorRef, now: () => SDateLike, expireAfterMillis: Long)
  extends CrunchStateActor(1, "live-test", queues, now, expireAfterMillis, false) {
  override def updateStateFromPortState(cs: PortState): Unit = {
    log.info(s"calling parent updateState...")
    super.updateStateFromPortState(cs)

    probe ! state.get
  }
}

class ForecastCrunchStateTestActor(queues: Map[TerminalName, Seq[QueueName]], probe: ActorRef, now: () => SDateLike, expireAfterMillis: Long)
  extends CrunchStateActor(1, "forecast-test", queues, now, expireAfterMillis, false) {
  override def updateStateFromPortState(cs: PortState): Unit = {
    log.info(s"calling parent updateState...")
    super.updateStateFromPortState(cs)

    probe ! state.get
  }
}

case class CrunchGraph(baseArrivalsInput: SourceQueueWithComplete[Flights],
                       forecastArrivalsInput: SourceQueueWithComplete[Flights],
                       liveArrivalsInput: SourceQueueWithComplete[Flights],
                       manifestsInput: SourceQueueWithComplete[VoyageManifests],
                       liveShiftsInput: SourceQueueWithComplete[String],
                       liveFixedPointsInput: SourceQueueWithComplete[String],
                       liveStaffMovementsInput: SourceQueueWithComplete[Seq[StaffMovement]],
                       forecastShiftsInput: SourceQueueWithComplete[String],
                       forecastFixedPointsInput: SourceQueueWithComplete[String],
                       forecastStaffMovementsInput: SourceQueueWithComplete[Seq[StaffMovement]],
                       actualDesksAndQueuesInput: SourceQueueWithComplete[ActualDeskStats],
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

  val oneMinute = 60000
  val uniquifyArrivals: (Seq[ApiFlightWithSplits]) => List[(ApiFlightWithSplits, Set[Arrival])] =
    CodeShares.uniqueArrivalsWithCodeShares((f: ApiFlightWithSplits) => f.apiFlight)

  val airportConfig = AirportConfig(
    portCode = "STN",
    queues = Map("T1" -> Seq(Queues.EeaDesk)),
    slaByQueue = Map(Queues.EeaDesk -> 25, Queues.EGate -> 20, Queues.NonEeaDesk -> 45),
    terminalNames = Seq("T1", "T2"),
    defaultWalkTimeMillis = Map(),
    defaultPaxSplits = SplitRatios(
      SplitSources.TerminalAverage,
      SplitRatio(eeaMachineReadableToDesk, 1)
    ),
    defaultProcessingTimes = Map(
      "T1" -> Map(eeaMachineReadableToDesk -> 25d / 60),
      "T2" -> Map(eeaMachineReadableToDesk -> 25d / 60)
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

  def liveCrunchStateActor(testProbe: TestProbe, now: () => SDateLike): ActorRef = system.actorOf(Props(classOf[LiveCrunchStateTestActor], airportConfig.queues, testProbe.ref, now, 2 * oneDayMillis), name = "crunch-live-state-actor")

  def forecastCrunchStateActor(testProbe: TestProbe, now: () => SDateLike): ActorRef = system.actorOf(Props(classOf[ForecastCrunchStateTestActor], airportConfig.queues, testProbe.ref, now, 2 * oneDayMillis), name = "crunch-forecast-state-actor")

  def baseArrivalsActor: ActorRef = system.actorOf(Props(classOf[ForecastBaseArrivalsActor]), name = "forecast-base-arrivals-actor")

  def forecastArrivalsActor: ActorRef = system.actorOf(Props(classOf[ForecastPortArrivalsActor]), name = "forecast-port-arrivals-actor")

  def liveArrivalsActor: ActorRef = system.actorOf(Props(classOf[LiveArrivalsActor]), name = "live-arrivals-actor")

  def testProbe(name: String) = TestProbe(name = name)

  def runCrunchGraph(initialBaseArrivals: Set[Arrival] = Set(),
                     initialForecastArrivals: Set[Arrival] = Set(),
                     initialLiveArrivals: Set[Arrival] = Set(),
                     initialManifests: VoyageManifests = VoyageManifests(Set()),
                     initialFlightsWithSplits: Option[FlightsWithSplits] = None,
                     airportConfig: AirportConfig = airportConfig,
                     csvSplitsProvider: SplitsProvider.SplitProvider = (_, _) => None,
                     pcpArrivalTime: (Arrival) => MilliDate = pcpForFlight,
                     minutesToCrunch: Int = 30,
                     warmUpMinutes: Int = 0,
                     crunchStartDateProvider: (SDateLike) => SDateLike,
                     crunchEndDateProvider: (SDateLike) => SDateLike,
                     calcPcpWindow: (Set[ApiFlightWithSplits], Set[ApiFlightWithSplits]) => Option[(SDateLike, SDateLike)] = (_, _) => Some((SDate.now(), SDate.now())),
                     now: () => SDateLike,
                     shifts: String = "",
                     fixedPoints: String = ""
                    ): CrunchGraph = {

    val maxDaysToCrunch = 100
    val expireAfterMillis = 2 * oneDayMillis

    val liveProbe = testProbe("live")
    val forecastProbe = testProbe("forecast")
    val shiftsActor: ActorRef = system.actorOf(Props(classOf[ShiftsActor]))
    val fixedPointsActor: ActorRef = system.actorOf(Props(classOf[FixedPointsActor]))
    val staffMovementsActor: ActorRef = system.actorOf(Props(classOf[StaffMovementsActor]))

    val crunchInputs = CrunchSystem(CrunchProps(
      system = actorSystem,
      airportConfig = airportConfig,
      pcpArrival = pcpArrivalTime,
      historicalSplitsProvider = csvSplitsProvider,
      liveCrunchStateActor = liveCrunchStateActor(liveProbe, now),
      forecastCrunchStateActor = forecastCrunchStateActor(forecastProbe, now),
      maxDaysToCrunch = maxDaysToCrunch,
      expireAfterMillis = expireAfterMillis,
      minutesToCrunch = minutesToCrunch,
      warmUpMinutes = warmUpMinutes,
      actors = Map[String, AskableActorRef](
        "shifts" -> shiftsActor,
        "fixed-points" -> fixedPointsActor,
        "staff-movements" -> staffMovementsActor),
      useNationalityBasedProcessingTimes = false,
      now = now,
      crunchStartDateProvider = crunchStartDateProvider,
      crunchEndDateProvider = crunchEndDateProvider,
      calcPcpTimeWindow = (_) => calcPcpWindow,
      initialFlightsWithSplits = initialFlightsWithSplits,
      splitsPredictorStage = splitsPredictorStage
    ))

    crunchInputs.baseArrivals.offer(Flights(initialBaseArrivals.toList))
    crunchInputs.forecastArrivals.offer(Flights(initialForecastArrivals.toList))
    crunchInputs.liveArrivals.offer(Flights(initialLiveArrivals.toList))
    crunchInputs.manifests.offer(initialManifests)
    crunchInputs.shifts.offer(shifts)
    crunchInputs.fixedPoints.offer(fixedPoints)

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
}

