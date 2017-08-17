package services.inputfeeds

import java.io.File

import actors._
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern._
import akka.testkit.{ImplicitSender, TestKit}
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import controllers.ArrivalGenerator.apiFlight
import controllers._
import drt.services.AirportConfigHelpers
import drt.shared.FlightsApi.{QueueName, TerminalName}
import drt.shared.PaxTypesAndQueues._
import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios}
import drt.shared.{Arrival, _}
import org.joda.time.DateTime
import org.slf4j.LoggerFactory
import org.specs2.mutable.SpecificationLike
import services.workloadcalculator.PaxLoadCalculator
import services.workloadcalculator.PaxLoadCalculator.{MillisSinceEpoch, PaxTypeAndQueueCount}
import services.{FlightCrunchInteractionTests, SDate, SplitsProvider}

import scala.collection.JavaConversions._
import scala.collection.immutable.{IndexedSeq, Seq}
import scala.concurrent.Await
import scala.concurrent.duration._


object TestCrunchConfig {
  val airportConfig = airportConfigForHours(1)
  val AirportConfigOrigin = "Airport Config"

  def airportConfigForHours(hours: Int) = {
    val seqOfHoursInts = List.fill[Int](hours) _
    AirportConfig(
      portCode = "EDI",
      queues = Map(
        "A1" -> Seq("eeaDesk", "eGate", "nonEeaDesk"),
        "A2" -> Seq("eeaDesk", "eGate", "nonEeaDesk")
      ),
      slaByQueue = Map(
        "eeaDesk" -> 20,
        "eGate" -> 25,
        "nonEeaDesk" -> 45
      ),
      terminalNames = Seq("A1", "A2"),
      timeToChoxMillis = 0L,
      firstPaxOffMillis = 0L,
      defaultWalkTimeMillis = 0L,
      defaultPaxSplits = SplitRatios(
        AirportConfigOrigin,
        SplitRatio(eeaMachineReadableToDesk, 0.4875),
        SplitRatio(eeaMachineReadableToEGate, 0.1625),
        SplitRatio(eeaNonMachineReadableToDesk, 0.1625),
        SplitRatio(visaNationalToDesk, 0.05),
        SplitRatio(nonVisaNationalToDesk, 0.05)
      ),
      defaultProcessingTimes = Map(
        "A1" -> Map(
          eeaMachineReadableToDesk -> 16d / 60,
          eeaMachineReadableToEGate -> 25d / 60,
          eeaNonMachineReadableToDesk -> 50d / 60,
          visaNationalToDesk -> 75d / 60,
          nonVisaNationalToDesk -> 64d / 60
        ),
        "A2" -> Map(
          eeaMachineReadableToDesk -> 30d / 60,
          eeaMachineReadableToEGate -> 25d / 60,
          eeaNonMachineReadableToDesk -> 50d / 60,
          visaNationalToDesk -> 120d / 60,
          nonVisaNationalToDesk -> 120d / 60
        )),
      minMaxDesksByTerminalQueue = Map(
        "A1" -> Map(
          "eeaDesk" -> (seqOfHoursInts(2), seqOfHoursInts(25)),
          "nonEeaDesk" -> (seqOfHoursInts(2), seqOfHoursInts(25)),
          "eGate" -> (seqOfHoursInts(2), seqOfHoursInts(25))
        ),
        "A2" -> Map(
          "eeaDesk" -> (seqOfHoursInts(2), seqOfHoursInts(25)),
          "nonEeaDesk" -> (seqOfHoursInts(2), seqOfHoursInts(25)),
          "eGate" -> (seqOfHoursInts(2), seqOfHoursInts(25))
        )
      ),
      shiftExamples = Seq(
        "Midnight shift, A1, {date}, 00:00, 00:59, 10",
        "Night shift, A1, {date}, 01:00, 06:59, 4",
        "Morning shift, A1, {date}, 07:00, 13:59, 15",
        "Afternoon shift, A1, {date}, 14:00, 16:59, 10",
        "Evening shift, A1, {date}, 17:00, 23:59,17"
      )
    )
  }

  def levelDbJournalDir(tn: String) = s"target/test/journal/$tn"

  def levelDbTestActorSystem(tn: String) = ActorSystem("testActorSystem", ConfigFactory.parseMap(Map(
    "akka.persistence.journal.plugin" -> "akka.persistence.journal.leveldb",
    "akka.persistence.no-snapshot-store.class" -> "akka.persistence.snapshot.NoSnapshotStore",
    "akka.persistence.journal.leveldb.dir" -> levelDbJournalDir(tn),
    "akka.persistence.snapshot-store.plugin" -> "akka.persistence.snapshot-store.local",
    "akka.persistence.snapshot-store.local.dir" -> s"$tn/snapshot"
  )).withFallback(ConfigFactory.load(getClass.getResource("/application.conf").getPath.toString)))

  case class TestContext(override val system: ActorSystem, props: Props) extends
    TestKit(system) with ImplicitSender {
    implicit val timeout: Timeout = Timeout(5 seconds)

    def sendToCrunch[T](o: T) = crunchActor ! o

    def askCrunchAndWaitForReply[T](o: T) = Await.result(crunchActor ? o, 1 second)

    lazy val crunchActor = createCrunchActor

    def createCrunchActor: ActorRef = {
      system.actorOf(props, "CrunchActor")
    }

    def getCrunchActor = system.actorSelection("CrunchActor")
  }

  def withContextCustomActor[T](props: Props, actorSystem: ActorSystem)(f: (TestContext) => T): T = {
    val context = TestContext(actorSystem, props: Props)
    val res = f(context)
    TestKit.shutdownActorSystem(context.system)
    res
  }

  def withContext[T](tn: String = "", timeProvider: () => DateTime = () => DateTime.now())(f: (TestContext) => T): T = {
    val journalDirName = TestCrunchConfig.levelDbJournalDir(tn)

    val journalDir = new File(journalDirName)
    journalDir.mkdirs()
    val props = Props(classOf[FlightCrunchInteractionTests.TestCrunchActor], 1, airportConfig, timeProvider)
    val context = TestContext(levelDbTestActorSystem(tn), props)
    val res = f(context)
    TestKit.shutdownActorSystem(context.system)
    PersistenceCleanup.deleteJournal(journalDirName)
    res
  }

  def hoursInMinutes(hours: Int) = 60 * hours

  def crunchAndGetCrunchResult(flights: Seq[Arrival], crunchTerminal: TerminalName, deskToInspect: QueueName, hoursToCrunch: Int, now: DateTime) = {
    val props: Props = crunchActorProps(hoursToCrunch, () => now)

    val result = withContextCustomActor(props, actorSystem = levelDbTestActorSystem("")) { context =>
      context.sendToCrunch(PerformCrunchOnFlights(flights))
      Thread.sleep(1000)
      context.sendToCrunch(GetLatestCrunch(crunchTerminal, deskToInspect))
      context.fishForMessage(15 seconds, "Looking for CrunchResult in BST test") {
        case cr: CrunchResult => true
      }
    }
    result
  }

  def crunchActorProps(hoursToCrunch: Int, timeProvider: () => DateTime) = {
    val airportConfig: AirportConfig = TestCrunchConfig.airportConfigForHours(hoursToCrunch)
    val paxFlowCalculator = (flight: Arrival) => {
      PaxFlow.makeFlightPaxFlowCalculator(
        PaxFlow.splitRatioForFlight(SplitsProvider.defaultProvider(airportConfig) :: Nil),
        BestPax.bestPax
      )(flight)
    }
    val props = Props(classOf[ProdCrunchActor], hoursToCrunch, airportConfig, paxFlowCalculator, timeProvider, BestPax.bestPax)
    props
  }

  def assertCrunchResult(result: Any, expectedMidnightLocalTime: Long, expectedFirstMinuteOfNonZeroWaitTime: Int): Boolean = {
    result match {
      case CrunchResult(`expectedMidnightLocalTime`, _, _, waitTimes) =>
        waitTimes.indexWhere(_ != 0) == expectedFirstMinuteOfNonZeroWaitTime
      case _ => false
    }
  }

  def assertCrunchResultDeskRecs(result: Any, expectedMidnightLocalTime: Long, expectedDeskRecs: IndexedSeq[Int]): Boolean = {
    result match {
      case CrunchResult(`expectedMidnightLocalTime`, _, recommendedDesks, _) =>
        recommendedDesks == expectedDeskRecs
      case CrunchResult(`expectedMidnightLocalTime`, _, recommendedDesks, _) =>
        println(s"recommendedDesks: found: $recommendedDesks, expected: $expectedDeskRecs")
        false
    }
  }
}

class TimezoneFlightCrunchTests extends SpecificationLike {
  isolated
  sequential

  val log = LoggerFactory.getLogger(getClass)

  import TestCrunchConfig._

  "Flight Crunch needs to be aware of local times" >> {
    val dt20160101_1011 = new DateTime(2016, 1, 1, 10, 11)
    val dt20160701_0555 = new DateTime(2016, 7, 1, 5, 55)

    "2016-01-01 02:00 millis since epoch is 1451606400000L" >> {
      TimeZone.lastLocalMidnightOn(dt20160101_1011).getMillis === 1451606400000L
    }

    "2016-07-01 02:00 millis since epoch is 1467327600000L" >> {
      TimeZone.lastLocalMidnightOn(dt20160701_0555).getMillis === 1467327600000L
    }

    "Flight Crunch Tests With BST" >> {
      "Given we have sent flights in GMT and now() is in GMT" in {
        "When we ask for the latest crunch the workload appears at the correct offset" in {
          val hoursToCrunch = 4
          val terminalToCrunch = "A1"
          val flights = List(
            apiFlight(iata = "BA123", terminal = terminalToCrunch, actPax = 200, schDt = "2016-01-01T02:00", flightId = 1))

          val result = crunchAndGetCrunchResult(flights, terminalToCrunch, "eeaDesk", hoursToCrunch, now = dt20160101_1011)
          val expectedMidnightLocalTime = 1451606400000L
          assertCrunchResult(result, expectedMidnightLocalTime, hoursInMinutes(2))
        }
      }

      "Given we have sent flights in BST and now() is in GMT" in {
        "When we ask for the latest crunch the workload appears at the correct offset" in {
          val hoursToCrunch = 4
          val terminalToCrunch = "A1"
          val flights = List(
            apiFlight(iata = "FR991", terminal = terminalToCrunch, actPax = 200, schDt = "2016-07-01T02:00Z", flightId = 1))

          val result = crunchAndGetCrunchResult(flights, terminalToCrunch, "eeaDesk", hoursToCrunch, now = dt20160701_0555)
          val expectedMidnightLocalTime = 1467327600000L
          assertCrunchResult(result, expectedMidnightLocalTime, hoursInMinutes(3))
        }
      }
    }
  }
}

class SplitsRequestRecordingCrunchActor(hours: Int, override val airportConfig: AirportConfig, timeProvider: () => DateTime = () => DateTime.now(), _splitRatioProvider: (Arrival => Option[SplitRatios]))
  extends CrunchActor(hours, airportConfig, timeProvider) with AirportConfigHelpers {

  override def bestPax(f: Arrival): Int = BestPax.bestPax(f)

  def splitRatioProvider = _splitRatioProvider

  def procTimesProvider(terminalName: TerminalName)(paxTypeAndQueue: PaxTypeAndQueue): Double = 1d

  def pcpArrivalTimeProvider(flight: Arrival): MilliDate = MilliDate(SDate.parseString(flight.SchDT).millisSinceEpoch)

  def flightPaxTypeAndQueueCountsFlow(flight: Arrival): IndexedSeq[(MillisSinceEpoch, PaxTypeAndQueueCount)] =
    PaxLoadCalculator.flightPaxFlowProvider(splitRatioProvider, BestPax.bestPax)(flight)

  override def lastLocalMidnightString: String = "2000-01-01"

  override def crunchQueueWorkloads(workloads: Seq[WL], terminalName: TerminalName, queueName: QueueName, crunchWindowStartTimeMillis: Long): CrunchResult = {
    CrunchResult(0L, 0L, IndexedSeq(), Seq())
  }
}
