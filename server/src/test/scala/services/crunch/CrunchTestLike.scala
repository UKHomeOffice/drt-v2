package services.crunch

import akka.actor.{ActorRef, ActorSystem, Props, Terminated}
import akka.pattern.ask
import akka.stream.QueueOfferResult.Enqueued
import akka.stream.Supervision.Stop
import akka.stream.scaladsl.SourceQueueWithComplete
import akka.stream.testkit.TestSubscriber.Probe
import akka.stream.{ActorMaterializer, QueueOfferResult}
import akka.testkit.{TestKit, TestProbe}
import akka.util.Timeout
import drt.shared.PaxTypes._
import drt.shared.PaxTypesAndQueues.{eeaMachineReadableToDesk, eeaMachineReadableToEGate, eeaNonMachineReadableToDesk, nonVisaNationalToDesk, visaNationalToDesk}
import drt.shared.PortState.empty.crunchMinutes
import drt.shared.Queues.Queue
import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios, SplitSources}
import drt.shared.Terminals.{T1, T2, Terminal}
import drt.shared._
import drt.shared.api.Arrival
import org.slf4j.{Logger, LoggerFactory}
import org.specs2.execute.Result
import org.specs2.mutable.SpecificationLike
import org.specs2.specification.{AfterAll, AfterEach}
import services._
import slickdb.Tables
import uk.gov.homeoffice.drt.auth.Roles.STN

import scala.collection.immutable
import scala.collection.immutable.{Map, SortedMap}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, ExecutionContextExecutor}
import scala.languageFeature.postfixOps


object H2Tables extends {
  val profile = slick.jdbc.H2Profile
} with Tables

object TestDefaults {
  val airportConfig: AirportConfig = AirportConfig(
    portCode = PortCode("STN"),
    queuesByTerminal = SortedMap(
      T1 -> Seq(Queues.EeaDesk, Queues.NonEeaDesk),
      T2 -> Seq(Queues.EeaDesk, Queues.NonEeaDesk)
    ),
    slaByQueue = Map(Queues.EeaDesk -> 25, Queues.NonEeaDesk -> 45),
    minutesToCrunch = 30,
    defaultWalkTimeMillis = Map(),
    terminalPaxSplits = List(T1, T2).map(t => (t, SplitRatios(
      SplitSources.TerminalAverage,
      SplitRatio(eeaMachineReadableToDesk, 1)
    ))).toMap,
    terminalProcessingTimes = Map(
      T1 -> Map(
        eeaMachineReadableToDesk -> 25d / 60,
        eeaNonMachineReadableToDesk -> 25d / 60
      ),
      T2 -> Map(
        eeaMachineReadableToDesk -> 25d / 60,
        eeaNonMachineReadableToDesk -> 25d / 60
      )
    ),
    minMaxDesksByTerminalQueue24Hrs = Map(
      T1 -> Map(
        Queues.EeaDesk -> ((List.fill[Int](24)(1), List.fill[Int](24)(20))),
        Queues.NonEeaDesk -> ((List.fill[Int](24)(1), List.fill[Int](24)(20))),
        Queues.EGate -> ((List.fill[Int](24)(1), List.fill[Int](24)(20)))),
      T2 -> Map(
        Queues.EeaDesk -> ((List.fill[Int](24)(1), List.fill[Int](24)(20))),
        Queues.NonEeaDesk -> ((List.fill[Int](24)(1), List.fill[Int](24)(20))),
        Queues.EGate -> ((List.fill[Int](24)(1), List.fill[Int](24)(20))))),
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
      ),
      T2 -> Map(
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
    queuesByTerminal = SortedMap(
      T1 -> Seq(Queues.EGate, Queues.EeaDesk, Queues.NonEeaDesk)
    ),
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
        Queues.EGate -> ((List.fill[Int](24)(1), List.fill[Int](24)(20)))
      )
    ),
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
    val queues = splits.map(_._1.queueType).toSet
    val queueOrder = Seq(Queues.EGate, Queues.EeaDesk, Queues.NonEeaDesk)
    val slas = Map[Queue, Int](Queues.EGate -> 25, Queues.EeaDesk -> 25, Queues.NonEeaDesk -> 45).filterKeys(queues.contains)
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
      case (pt, queueRatios) => (pt, queueRatios.map{ case (PaxTypeAndQueue(_, q), r) => (q, r)}.toList)
    }

    AirportConfig(
      portCode = PortCode("STN"),
      queuesByTerminal = SortedMap(
        T1 -> queueOrder.filter(queues.contains)
      ),
      slaByQueue = slas,
      minutesToCrunch = 30,
      defaultWalkTimeMillis = Map(),
      terminalPaxSplits = Map(T1 -> SplitRatios(ratios, SplitSources.TerminalAverage)),
      terminalProcessingTimes = Map(T1 -> procTimes.filterKeys { case PaxTypeAndQueue(_, q) => queues.contains(q) }),
      minMaxDesksByTerminalQueue24Hrs = Map(T1 -> queues.map(q => (q, minMax)).toMap),
      timeToChoxMillis = 120000L,
      role = STN,
      terminalPaxTypeQueueAllocation = Map(T1 -> paxTypeQueues),
      desksByTerminal = Map(T1 -> 40),
      feedSources = Seq(ApiFeedSource, LiveBaseFeedSource, LiveFeedSource, AclFeedSource)
    )
  }

  val pcpForFlightFromSch: Arrival => MilliDate = (a: Arrival) => MilliDate(SDate(a.Scheduled).millisSinceEpoch)
  val pcpForFlightFromBest: Arrival => MilliDate = (a: Arrival) => {
    if (a.ActualChox.isDefined) MilliDate(SDate(a.ActualChox.get).millisSinceEpoch)
    else if (a.EstimatedChox.isDefined) MilliDate(SDate(a.EstimatedChox.get).millisSinceEpoch)
    else if (a.Actual.isDefined) MilliDate(SDate(a.Actual.get).millisSinceEpoch)
    else if (a.Estimated.isDefined) MilliDate(SDate(a.Estimated.get).millisSinceEpoch)
    else MilliDate(SDate(a.Scheduled).millisSinceEpoch)
  }

  def testProbe(name: String)(implicit system: ActorSystem): TestProbe = TestProbe(name = name)

  val pcpPaxFn: Arrival => Int = PcpPax.bestPaxEstimateWithApi
}

class CrunchTestLike
  extends TestKit(ActorSystem("DRT-TEST"))
    with SpecificationLike
    with AfterAll
    with AfterEach {
  isolated
  sequential

  implicit def probe2Success[R <: Probe[_]](r: R): Result = success

  var maybeDrtActor: Option[ActorRef] = None

  override def afterAll: Unit = {
    maybeDrtActor.foreach(shutDownDrtActor)
  }

  override def after: Unit = {
    log.info("Shutting down actor system!!!")
    TestKit.shutdownActorSystem(system)
  }

  implicit val materializer: ActorMaterializer = ActorMaterializer.create(system)
  implicit val ec: ExecutionContextExecutor = ExecutionContext.global
  implicit val timeout: Timeout = new Timeout(5 seconds)

  val log: Logger = LoggerFactory.getLogger(getClass)

  val oneMinuteMillis = 60000
  val uniquifyArrivals: Seq[ApiFlightWithSplits] => List[(ApiFlightWithSplits, Set[Arrival])] =
    CodeShares.uniqueArrivalsWithCodeShares((f: ApiFlightWithSplits) => f.apiFlight)

  val defaultAirportConfig: AirportConfig = TestDefaults.airportConfig

  def runCrunchGraph(config: TestConfig): CrunchGraphInputsAndProbes = {
    maybeDrtActor.foreach(shutDownDrtActor)
    val testDrtActor = system.actorOf(Props(new TestDrtActor()))
    maybeDrtActor = Option(testDrtActor)
    Await.result(testDrtActor.ask(config).mapTo[CrunchGraphInputsAndProbes], 1 seconds)
  }

  def shutDownDrtActor(drtActor: ActorRef): Terminated = {
    log.info("Shutting down drt actor")
    watch(drtActor)
    drtActor ! Stop
    expectMsgClass(classOf[Terminated])
  }

  def expectArrivals(arrivalsToExpect: Iterable[Arrival])(implicit crunch: CrunchGraphInputsAndProbes): Unit =
    crunch.portStateTestProbe.fishForMessage(1 seconds) {
      case ps: PortState =>
        ps.flights.values.map(_.apiFlight) == arrivalsToExpect
    }

  def expectUniqueArrival(uniqueArrival: UniqueArrival)(implicit crunch: CrunchGraphInputsAndProbes): Unit =
    crunch.portStateTestProbe.fishForMessage(1 seconds) {
      case ps: PortState =>
        ps.flights.contains(uniqueArrival)
    }

  def expectNoUniqueArrival(uniqueArrival: UniqueArrival)(implicit crunch: CrunchGraphInputsAndProbes): Unit =
    crunch.portStateTestProbe.fishForMessage(1 seconds) {
      case ps: PortState =>
        !ps.flights.contains(uniqueArrival)
    }

  def expectFeedSources(sourcesToExpect: Set[FeedSource])(implicit crunch: CrunchGraphInputsAndProbes): Unit =
    crunch.portStateTestProbe.fishForMessage(1 seconds) {
      case ps: PortState =>
        ps.flights.values.flatMap(_.apiFlight.FeedSources).toSet == sourcesToExpect
    }

  def expectPaxNos(totalPaxToExpect: Double)(implicit crunch: CrunchGraphInputsAndProbes): Unit =
    crunch.portStateTestProbe.fishForMessage(1 seconds) {
      case ps: PortState =>
        ps.crunchMinutes.values.map(_.paxLoad).sum == totalPaxToExpect
    }

  def expectPaxByQueue(paxByQueueToExpect: Map[Queue, Double])(implicit crunch: CrunchGraphInputsAndProbes): Unit =
    crunch.portStateTestProbe.fishForMessage(1 seconds) {
      case ps: PortState =>
        val paxByQueue = ps.crunchMinutes.values
          .groupBy(_.queue)
          .map {
            case (queue, mins) => (queue, mins.map(_.paxLoad).sum)
          }
        val asExpected = paxByQueue == paxByQueueToExpect
        if (!asExpected) println(s"\nNo match yet\ngot: $paxByQueue\nexp: $paxByQueueToExpect")
        asExpected
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
              val workLoad = sortedCms.map(_.workLoad).take(minsToTake)
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
    Await.result(sourceQueue.offer(offering), 3 seconds) match {
      case offerResult if offerResult != Enqueued =>
        throw new Exception(s"Queue offering (${offering.getClass}) was not enqueued: ${offerResult.getClass}")
      case offerResult =>
        offerResult
    }
  }
}
