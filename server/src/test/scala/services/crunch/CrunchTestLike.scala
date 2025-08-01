package services.crunch

import org.apache.pekko.actor.{ActorRef, ActorSystem, Props, Terminated, typed}
import org.apache.pekko.pattern.ask
import org.apache.pekko.persistence.testkit.{PersistenceTestKitPlugin, PersistenceTestKitSnapshotPlugin}
import org.apache.pekko.stream.QueueOfferResult.Enqueued
import org.apache.pekko.stream.Supervision.Stop
import org.apache.pekko.stream.scaladsl.SourceQueueWithComplete
import org.apache.pekko.stream.testkit.TestSubscriber.Probe
import org.apache.pekko.stream.{Materializer, QueueOfferResult}
import org.apache.pekko.testkit.{TestKit, TestProbe}
import org.apache.pekko.util.Timeout
import com.typesafe.config.ConfigFactory
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import org.specs2.execute.Result
import org.specs2.mutable.SpecificationLike
import org.specs2.specification.{AfterAll, AfterEach, BeforeEach}
import uk.gov.homeoffice.drt.arrivals.{Arrival, UniqueArrival}
import uk.gov.homeoffice.drt.auth.Roles.STN
import uk.gov.homeoffice.drt.db.AggregateDbH2
import uk.gov.homeoffice.drt.ports.PaxTypes._
import uk.gov.homeoffice.drt.ports.PaxTypesAndQueues._
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.{SplitRatio, SplitRatios, SplitSources}
import uk.gov.homeoffice.drt.ports.Terminals.{T1, T2, Terminal}
import uk.gov.homeoffice.drt.ports._
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, SDateLike}

import scala.collection.immutable
import scala.collection.immutable.{Map, SortedMap}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, ExecutionContextExecutor, Future}

object TestDefaults {
  val airportConfig: AirportConfig = AirportConfig(
    portCode = PortCode("STN"),
    portName = "Stansted",
    queuesByTerminal = SortedMap(LocalDate(2014, 1, 1) -> SortedMap(
      T1 -> Seq(Queues.EeaDesk, Queues.NonEeaDesk),
      T2 -> Seq(Queues.EeaDesk, Queues.NonEeaDesk)
    )),
    slaByQueue = Map(Queues.EeaDesk -> 25, Queues.NonEeaDesk -> 45),
    minutesToCrunch = 30,
    defaultWalkTimeMillis = Map(),
    terminalPaxSplits = List(T1, T2).map(t => (t, SplitRatios(
      SplitSources.TerminalAverage,
      SplitRatio(eeaMachineReadableToDesk, 1)
    ))).toMap,
    terminalProcessingTimes = Map(
      T1 -> Map(
        gbrNationalToDesk -> 25d / 60,
        gbrNationalChildToDesk -> 25d / 60,
        eeaMachineReadableToDesk -> 25d / 60,
        eeaMachineReadableToDesk -> 25d / 60,
        eeaNonMachineReadableToDesk -> 25d / 60
      ),
      T2 -> Map(
        gbrNationalToDesk -> 25d / 60,
        gbrNationalChildToDesk -> 25d / 60,
        eeaMachineReadableToDesk -> 25d / 60,
        eeaNonMachineReadableToDesk -> 25d / 60
      )
    ),
    minMaxDesksByTerminalQueue24Hrs = Map(
      T1 -> Map(
        Queues.EeaDesk -> ((List.fill[Int](24)(1), List.fill[Int](24)(20))),
        Queues.NonEeaDesk -> ((List.fill[Int](24)(1), List.fill[Int](24)(20))),
        Queues.EGate -> ((List.fill[Int](24)(1), List.fill[Int](24)(2)))),
      T2 -> Map(
        Queues.EeaDesk -> ((List.fill[Int](24)(1), List.fill[Int](24)(20))),
        Queues.NonEeaDesk -> ((List.fill[Int](24)(1), List.fill[Int](24)(20))),
        Queues.EGate -> ((List.fill[Int](24)(1), List.fill[Int](24)(2))))),
    eGateBankSizes = Map(
      T1 -> Iterable(10, 10),
      T2 -> Iterable(10, 10),
    ),
    role = STN,
    terminalPaxTypeQueueAllocation = Map(
      T1 -> Map(
        GBRNational -> List(Queues.EeaDesk -> 1.0),
        GBRNationalBelowEgateAge -> List(Queues.EeaDesk -> 1.0),
        EeaMachineReadable -> List(Queues.EeaDesk -> 1.0),
        EeaBelowEGateAge -> List(Queues.EeaDesk -> 1.0),
        EeaNonMachineReadable -> List(Queues.EeaDesk -> 1.0),
        NonVisaNational -> List(Queues.NonEeaDesk -> 1.0),
        VisaNational -> List(Queues.NonEeaDesk -> 1.0),
        B5JPlusNational -> List(Queues.EeaDesk -> 1.0),
        B5JPlusNationalBelowEGateAge -> List(Queues.EeaDesk -> 1)
      ),
      T2 -> Map(
        GBRNational -> List(Queues.EeaDesk -> 1.0),
        GBRNationalBelowEgateAge -> List(Queues.EeaDesk -> 1.0),
        EeaMachineReadable -> List(Queues.EeaDesk -> 1),
        EeaBelowEGateAge -> List(Queues.EeaDesk -> 1.0),
        EeaNonMachineReadable -> List(Queues.EeaDesk -> 1.0),
        NonVisaNational -> List(Queues.NonEeaDesk -> 1.0),
        VisaNational -> List(Queues.NonEeaDesk -> 1.0),
        B5JPlusNational -> List(Queues.EeaDesk -> 1),
        B5JPlusNationalBelowEGateAge -> List(Queues.EeaDesk -> 1)
      )
    ),
    desksByTerminal = Map(T1 -> 40, T2 -> 40),
    feedSources = Seq(ApiFeedSource, LiveBaseFeedSource, LiveFeedSource, AclFeedSource)
  )

  val airportConfigWithEgates: AirportConfig = AirportConfig(
    portCode = PortCode("STN"),
    portName = "Stansted",
    queuesByTerminal = SortedMap(LocalDate(2014, 1, 1) -> SortedMap(
      T1 -> Seq(Queues.EGate, Queues.EeaDesk, Queues.NonEeaDesk)
    )),
    slaByQueue = Map(Queues.EGate -> 25, Queues.EeaDesk -> 25, Queues.NonEeaDesk -> 45),
    minutesToCrunch = 30,
    defaultWalkTimeMillis = Map(),
    terminalPaxSplits = List(T1).map(t => (t, SplitRatios(
      SplitSources.TerminalAverage,
      SplitRatio(eeaMachineReadableToEGate, 0.5),
      SplitRatio(eeaMachineReadableToDesk, 0.5)
    ))).toMap,
    terminalProcessingTimes = Map(
      T1 -> Map(
        eeaMachineReadableToDesk -> 25d / 60,
        eeaNonMachineReadableToDesk -> 25d / 60
      )
    ),
    minMaxDesksByTerminalQueue24Hrs = Map(
      T1 -> Map(
        Queues.EeaDesk -> ((List.fill[Int](24)(1), List.fill[Int](24)(20))),
        Queues.NonEeaDesk -> ((List.fill[Int](24)(1), List.fill[Int](24)(20))),
        Queues.EGate -> ((List.fill[Int](24)(1), List.fill[Int](24)(2)))
      )
    ),
    eGateBankSizes = Map(T1 -> Iterable(10, 5)),
    timeToChoxMillis = 120000L,
    role = STN,
    terminalPaxTypeQueueAllocation = Map(
      T1 -> Map(
        EeaMachineReadable -> List(Queues.EeaDesk -> 1.0),
        EeaBelowEGateAge -> List(Queues.EeaDesk -> 1.0),
        EeaNonMachineReadable -> List(Queues.EeaDesk -> 1.0),
        NonVisaNational -> List(Queues.NonEeaDesk -> 1.0),
        VisaNational -> List(Queues.NonEeaDesk -> 1.0),
        B5JPlusNational -> List(Queues.EeaDesk -> 1.0),
        B5JPlusNationalBelowEGateAge -> List(Queues.EeaDesk -> 1)
      )
    ),
    desksByTerminal = Map(T1 -> 40),
    feedSources = Seq(ApiFeedSource, LiveBaseFeedSource, LiveFeedSource, AclFeedSource)
  )

  def airportConfigForSplits(splits: Map[PaxTypeAndQueue, Double]): AirportConfig = {
    val queues = Seq(Queues.EGate, Queues.EeaDesk, Queues.NonEeaDesk)
    val slas = Map[Queue, Int](Queues.EGate -> 25, Queues.EeaDesk -> 25, Queues.NonEeaDesk -> 45).view.filterKeys(queues.contains).toMap
    val ratios: immutable.Iterable[SplitRatio] = splits.map {
      case (pt, q) => SplitRatio(pt, q)
    }
    val procTimes = Map(
      eeaMachineReadableToDesk -> 25d / 60,
      eeaMachineReadableToEGate -> 20d / 60,
      eeaNonMachineReadableToDesk -> 25d / 60,
      nonVisaNationalToDesk -> 45d / 60,
      visaNationalToDesk -> 60d / 60,
    )
    val minMax = (List.fill[Int](24)(1), List.fill[Int](24)(20))
    val paxTypeQueues: Map[PaxType, List[(Queue, Double)]] = splits.groupBy(_._1.passengerType).map {
      case (pt, queueRatios) => (pt, queueRatios.map { case (PaxTypeAndQueue(_, q), r) => (q, r) }.toList)
    }

    AirportConfig(
      portCode = PortCode("STN"),
      portName = "Stansted",
      queuesByTerminal = SortedMap(LocalDate(2014, 1, 1) -> SortedMap(
        T1 -> queues
      )),
      slaByQueue = slas,
      minutesToCrunch = 30,
      defaultWalkTimeMillis = Map(),
      terminalPaxSplits = Map(T1 -> SplitRatios(ratios, SplitSources.TerminalAverage)),
      terminalProcessingTimes = Map(T1 -> procTimes.view.filterKeys { case PaxTypeAndQueue(_, q) => queues.contains(q) }.toMap),
      minMaxDesksByTerminalQueue24Hrs = Map(T1 -> queues.map(q => (q, minMax)).toMap),
      eGateBankSizes = Map(T1 -> Iterable(10, 5)),
      timeToChoxMillis = 120000L,
      role = STN,
      terminalPaxTypeQueueAllocation = Map(T1 -> paxTypeQueues),
      desksByTerminal = Map(T1 -> 40),
      feedSources = Seq(ApiFeedSource, LiveBaseFeedSource, LiveFeedSource, AclFeedSource)
    )
  }

  val setPcpFromSch: Seq[Arrival] => Future[Seq[Arrival]] =
    arrivals => Future.successful(arrivals.map(a => a.copy(PcpTime = Option(SDate(a.Scheduled).millisSinceEpoch))))

  val setPcpFromBest: Seq[Arrival] => Future[Seq[Arrival]] =
    arrivals => Future.successful(
      arrivals.map { a =>
        val pcp = if (a.ActualChox.isDefined) SDate(a.ActualChox.get).millisSinceEpoch
        else if (a.EstimatedChox.isDefined) SDate(a.EstimatedChox.get).millisSinceEpoch
        else if (a.Actual.isDefined) SDate(a.Actual.get).millisSinceEpoch
        else if (a.Estimated.isDefined) SDate(a.Estimated.get).millisSinceEpoch
        else SDate(a.Scheduled).millisSinceEpoch
        a.copy(PcpTime = Option(pcp))
      }
    )

  def testProbe(name: String)(implicit system: ActorSystem): TestProbe = TestProbe(name = name)

  val paxFeedSourceOrder: List[FeedSource] = List(
    ScenarioSimulationSource,
    LiveFeedSource,
    ApiFeedSource,
    MlFeedSource,
    ForecastFeedSource,
    HistoricApiFeedSource,
    AclFeedSource,
  )
}

class CrunchTestLike
  extends TestKit(ActorSystem("DRT-TEST", PersistenceTestKitPlugin.config.withFallback(PersistenceTestKitSnapshotPlugin.config.withFallback(ConfigFactory.load()))))
    with SpecificationLike
    with AfterAll
    with AfterEach
    with BeforeEach {
  isolated
  sequential

  implicit val aggregatedDb: AggregateDbH2.type = AggregateDbH2

  val paxFeedSourceOrder: List[FeedSource] = TestDefaults.paxFeedSourceOrder

  implicit def probe2Success[R <: Probe[_]](r: R): Result = success

  var maybeDrtActor: Option[ActorRef] = None

  override def afterAll(): Unit = {
    log.info("Final cleanup: Shutting down drt actor")
    maybeDrtActor.foreach(shutDownDrtActor)
  }

  override def after(): Unit = {
    log.info("Cleanup: Shutting down actor system")
    TestKit.shutdownActorSystem(system)
  }

  override def before(): Any = {
    log.info("Before: Clearing db tables")
    aggregatedDb.dropAndCreateH2Tables()
  }

  implicit val materializer: Materializer = Materializer.createMaterializer(system)
  implicit val ec: ExecutionContextExecutor = ExecutionContext.global
  implicit val timeout: Timeout = new Timeout(5.seconds)

  val log: Logger = LoggerFactory.getLogger(getClass)

  val oneMinuteMillis = 60000

  val defaultAirportConfig: AirportConfig = TestDefaults.airportConfig

  def runCrunchGraph(config: TestConfig): CrunchGraphInputsAndProbes = {
    maybeDrtActor.foreach(shutDownDrtActor)
    val testDrtActor = system.actorOf(Props(new TestDrtActor()))
    maybeDrtActor = Option(testDrtActor)
    Await.result(testDrtActor.ask(config).mapTo[CrunchGraphInputsAndProbes], 1.seconds)
  }

  def shutDownDrtActor(drtActor: ActorRef): Terminated = {
    log.info("Shutting down drt actor")
    watch(drtActor)
    drtActor ! Stop
    expectMsgClass(classOf[Terminated])
  }

  def expectArrivals(arrivalsToExpect: Iterable[Arrival])(implicit crunch: CrunchGraphInputsAndProbes): Unit =
    crunch.portStateTestProbe.fishForMessage(1.seconds) {
      case PortState(flights, _, _) =>
        flights.values.map(_.apiFlight) == arrivalsToExpect
    }

  def expectUniqueArrival(uniqueArrival: UniqueArrival)(implicit crunch: CrunchGraphInputsAndProbes): Unit =
    crunch.portStateTestProbe.fishForMessage(1.seconds) {
      case ps: PortState =>
        ps.flights.contains(uniqueArrival)
    }

  def expectNoUniqueArrival(uniqueArrival: UniqueArrival)(implicit crunch: CrunchGraphInputsAndProbes): Unit =
    crunch.portStateTestProbe.fishForMessage(1.seconds) {
      case ps: PortState =>
        !ps.flights.contains(uniqueArrival)
    }

  def expectFeedSources(sourcesToExpect: Set[FeedSource])(implicit crunch: CrunchGraphInputsAndProbes): Unit =
    crunch.portStateTestProbe.fishForMessage(1.seconds, s"Expected $sourcesToExpect") {
      case ps: PortState =>
        ps.flights.values.flatMap(_.apiFlight.FeedSources).toSet == sourcesToExpect
    }

  def expectPaxNos(totalPaxToExpect: Double)(implicit crunch: CrunchGraphInputsAndProbes): Unit =
    crunch.portStateTestProbe.fishForMessage(1.seconds) {
      case ps: PortState =>
        ps.crunchMinutes.values.map(_.paxLoad).sum == totalPaxToExpect
    }

  def expectPaxByQueue(paxByQueueToExpect: Map[Queue, Double])(implicit crunch: CrunchGraphInputsAndProbes): Unit =
    crunch.portStateTestProbe.fishForMessage(1.seconds) {
      case ps: PortState =>
        val paxByQueue = ps.crunchMinutes.values
          .groupBy(_.queue)
          .map {
            case (queue, mins) => (queue, mins.map(_.paxLoad).sum)
          }
          .filter {
            case (_, mins) => mins > 0
          }
        val nonZerosToExpect = paxByQueueToExpect
          .filter {
            case (_, mins) => mins > 0
          }

        paxByQueue == nonZerosToExpect
    }

  def paxLoadsFromPortState(portState: PortState,
                            minsToTake: Int,
                            startFromMinuteIdx: Int = 0): Map[Terminal, Map[Queue, List[Double]]] = portState
    .crunchMinutes
    .values
    .groupBy(_.terminal)
    .map {
      case (tn, tms) =>
        val terminalLoads = tms
          .groupBy(_.queue)
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

  def paxLoadsFromPortState(portState: PortState,
                            minsToTake: Int,
                            startFromMinute: SDateLike): Map[Terminal, Map[Queue, List[Double]]] = {
    val startFromMillis = startFromMinute.millisSinceEpoch

    portState
      .crunchMinutes
      .values
      .groupBy(_.terminal)
      .map {
        case (tn, tms) =>
          val terminalLoads = tms
            .groupBy(_.queue)
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

  def allWorkLoadsFromPortState(portState: PortState): Map[Terminal, Map[Queue, List[Double]]] = portState
    .crunchMinutes
    .values
    .groupBy(_.terminal)
    .map {
      case (tn, tms) =>
        val terminalLoads = tms
          .groupBy(_.queue)
          .map {
            case (qn, qms) =>
              val sortedCms = qms.toList.sortBy(_.minute)
              val workLoad = sortedCms.map(_.workLoad)
              (qn, workLoad)
          }
        (tn, terminalLoads)
    }

  def workLoadsFromPortState(portState: PortState,
                             minsToTake: Int): Map[Terminal, Map[Queue, List[Double]]] = portState
    .crunchMinutes
    .values
    .groupBy(_.terminal)
    .map {
      case (tn, tms) =>
        val terminalLoads = tms
          .groupBy(_.queue)
          .map {
            case (qn, qms) =>
              val sortedCms = qms.toList.sortBy(_.minute)
              val workLoad = sortedCms.map(m => Math.round(m.workLoad * 100).toDouble / 100).take(minsToTake)
              (qn, workLoad)
          }
        (tn, terminalLoads)
    }

  def deskRecsFromPortState(portState: PortState, minsToTake: Int): Map[Terminal, Map[Queue, List[Int]]] = portState
    .crunchMinutes
    .values
    .groupBy(_.terminal)
    .map {
      case (tn, tms) =>
        val terminalLoads = tms
          .groupBy(_.queue)
          .map {
            case (qn, qms) =>
              val sortedCms = qms.toList.sortBy(_.minute)
              val deskRecs = sortedCms.map(_.deskRec).take(minsToTake)
              (qn, deskRecs)
          }
        (tn, terminalLoads)
    }

  def offerAndWait[T](sourceQueue: SourceQueueWithComplete[T], offering: T): QueueOfferResult = {
    Await.result(sourceQueue.offer(offering), 10.seconds) match {
      case offerResult if offerResult != Enqueued =>
        throw new Exception(s"Queue offering (${offering.getClass}) was not enqueued: ${offerResult.getClass}")
      case offerResult =>
        offerResult
    }
  }

  def waitForFlightsInPortState(testProbe: TestProbe): Any =
    testProbe.fishForMessage(1.second) {
      case ps: PortState => ps.flights.nonEmpty
    }


  def offerAndWait[T](source: typed.ActorRef[T], offering: T): Unit = {
    source ! offering
  }

  def offerAndWait[T](source: ActorRef, offering: T): Unit =
    Await.ready(source.ask(offering), 1.second)
}
