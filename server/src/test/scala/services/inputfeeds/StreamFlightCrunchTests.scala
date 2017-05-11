package services.inputfeeds

import java.io.File

import actors.{FlightsActor, GetLatestCrunch, PerformCrunchOnFlights, TimeZone}
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.stream.testkit.TestSubscriber.Probe
import akka.testkit.{ImplicitSender, TestKit}
import com.typesafe.config.ConfigFactory
import controllers._
import drt.shared.FlightsApi.{Flights, QueueName, TerminalName}
import drt.shared.PaxTypesAndQueues._
import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios}
import drt.shared._
import org.joda.time.chrono.ISOChronology
import org.joda.time.{DateTime, DateTimeZone}
import org.slf4j.LoggerFactory
import org.specs2.execute.Result
import org.specs2.mutable.{Specification, SpecificationLike}
import services.FlightCrunchInteractionTests.TestCrunchActor
import services.PcpArrival.{GateOrStand, GateOrStandWalkTime, gateOrStandWalkTimeCalculator}
import services.WorkloadCalculatorTests._
import services.{FlightCrunchInteractionTests, SplitsProvider, WorkloadCalculatorTests}

import scala.collection.JavaConversions._
import scala.collection.immutable.Seq
import scala.concurrent.duration._


object CrunchTests {
  val airportConfig = airportConfigForHours(1)

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
    "akka.persistence.snapshot-store.plugin" -> "akka.persistence.snapshot-store.local"
  )).withFallback(ConfigFactory.load(getClass.getResource("/application.conf").getPath.toString)))

  case class TestContext(override val system: ActorSystem, props: Props) extends
    TestKit(system) with ImplicitSender {

    def sendToCrunch[T](o: T) = crunchActor ! o

    lazy val crunchActor = createCrunchActor

    def createCrunchActor: ActorRef = {
      system.actorOf(props, "CrunchActor")
    }

    def getCrunchActor = system.actorSelection("CrunchActor")
  }

  def withContextCustomActor[T](props: Props, tn: String = "")(f: (TestContext) => T): T = {
    val context = TestContext(levelDbTestActorSystem(tn), props: Props)
    val res = f(context)
    TestKit.shutdownActorSystem(context.system)
    res
  }

  def withContext[T](tn: String = "", timeProvider: () => DateTime = () => DateTime.now())(f: (TestContext) => T): T = {
    val journalDirName = CrunchTests.levelDbJournalDir(tn)

    val journalDir = new File(journalDirName)
    journalDir.mkdirs()
    val props = Props(classOf[FlightCrunchInteractionTests.TestCrunchActor], 1, airportConfig, timeProvider)
    val context = TestContext(levelDbTestActorSystem(tn), props)
    val res = f(context)
    TestKit.shutdownActorSystem(context.system)
    PersistenceCleanup.deleteJournal(journalDirName)
    res
  }
}

class NewStreamFlightCrunchTests extends SpecificationLike {
  isolated
  sequential

  import CrunchTests._

  "Streamed Flight Crunch Tests" >> {
    "and we have sent it flights for different terminals A1 and A2" in {
      "when we ask for the latest crunch for eeaDesk at terminal A1, we get a crunch result only including flights at that terminal" in {
        val airportConfig: AirportConfig = CrunchTests.airportConfig
        val timeProvider = () => new DateTime(2016, 1, 1, 0, 0)

        val walkTimeProvider: GateOrStandWalkTime = (_, _) => Some(0L)

        val paxFlowCalculator = (flight: ApiFlight) => {
          PaxFlow.makeFlightPaxFlowCalculator(
            PaxFlow.splitRatioForFlight(SplitsProvider.defaultProvider(airportConfig) :: Nil),
            PaxFlow.pcpArrivalTimeForFlight(airportConfig)(gateOrStandWalkTimeCalculator(walkTimeProvider, walkTimeProvider, airportConfig.defaultWalkTimeMillis)))(flight)
        }
        val props = Props(classOf[ProdCrunchActor], 1, airportConfig, paxFlowCalculator, timeProvider)

        withContextCustomActor(props) { context =>
          val flights = Flights(
            List(
              apiFlight("BA123", terminal = "A1", totalPax = 200, scheduledDatetime = "2016-01-01T00:00", flightId = 1),
              apiFlight("EZ456", terminal = "A2", totalPax = 100, scheduledDatetime = "2016-01-01T00:00", flightId = 2)))
          context.sendToCrunch(PerformCrunchOnFlights(flights.flights))
          context.sendToCrunch(GetLatestCrunch("A1", "eeaDesk"))
          val exp =
            CrunchResult(
              timeProvider().getMillis,
              60000,
              Vector(2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2),
              Vector(1, 2, 2, 3, 4, 4, 5, 5, 6, 7, 7, 8, 9, 9, 10, 10, 11, 12, 12, 13, 14, 14, 15, 15, 16, 17, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))

          context.expectMsg(15 seconds, exp)
          true
        }
      }

      "when we ask for the latest crunch for eGates at terminal A1, we get a crunch result only including flights at that terminal" in {
        val airportConfig: AirportConfig = CrunchTests.airportConfig
        val timeProvider = () => new DateTime(2016, 1, 1, 0, 0)

        val walkTimeProvider: GateOrStandWalkTime = (_, _) => Some(0L)

        val paxFlowCalculator = (flight: ApiFlight) => {
          PaxFlow.makeFlightPaxFlowCalculator(
            PaxFlow.splitRatioForFlight(SplitsProvider.defaultProvider(airportConfig) :: Nil),
            PaxFlow.pcpArrivalTimeForFlight(airportConfig)(gateOrStandWalkTimeCalculator(walkTimeProvider, walkTimeProvider, airportConfig.defaultWalkTimeMillis)))(flight)
        }
        val props = Props(classOf[ProdCrunchActor], 1, airportConfig, paxFlowCalculator, timeProvider)

        withContextCustomActor(props) { context =>
          val flights = Flights(
            List(
              apiFlight("BA123", terminal = "A1", totalPax = 200, scheduledDatetime = "2016-01-01T01:00", flightId = 1) /*,
              apiFlight("EZ456", terminal = "A2", totalPax = 100, scheduledDatetime = "2016-01-01T10:00", flightId = 2)*/))
          context.sendToCrunch(PerformCrunchOnFlights(flights.flights))
          context.sendToCrunch(GetLatestCrunch("A1", "eGate"))
          val exp =
            CrunchResult(
              timeProvider().getMillis,
              60000,
              Vector(
                1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
                1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
                1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
                1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
                1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
                1, 1, 1, 1, 1, 1, 1, 1, 1, 1),
              Vector(
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0))


          context.expectMsg(15 seconds, exp)
          true
        }
      }
    }
  }
}

class TimezoneFlightCrunchTests extends SpecificationLike {
  isolated
  sequential

  val log = LoggerFactory.getLogger(getClass)

  import CrunchTests._

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
            apiFlight("BA123", terminal = terminalToCrunch, totalPax = 200, scheduledDatetime = "2016-01-01T02:00", flightId = 1))

          val result = crunchFlights(flights, terminalToCrunch, "eeaDesk", hoursToCrunch, now = dt20160101_1011)
          val expectedMidnightLocalTime = 1451606400000L
          assertCrunchResult(result, expectedMidnightLocalTime, hoursInMinutes(2))
        }
      }

      "Given we have sent flights in BST and now() is in GMT" in {
        "When we ask for the latest crunch the workload appears at the correct offset" in {
          val hoursToCrunch = 4
          val terminalToCrunch = "A1"
          val flights = List(
            apiFlight("FR991", terminal = terminalToCrunch, totalPax = 200, scheduledDatetime = "2016-07-01T02:00Z", flightId = 1))

          val result = crunchFlights(flights, terminalToCrunch, "eeaDesk", hoursToCrunch, now = dt20160701_0555)
          val expectedMidnightLocalTime = 1467327600000L
          assertCrunchResult(result, expectedMidnightLocalTime, hoursInMinutes(3))
        }
      }
    }
  }

  private def hoursInMinutes(hours: Int) = 60 * hours

  private def crunchFlights(flights: Seq[ApiFlight], crunchTerminal: TerminalName, deskToInspect: QueueName, hoursToCrunch: Int, now: DateTime) = {
    val props: Props = crunchActorProps(hoursToCrunch, () => now)

    val result = withContextCustomActor(props) { context =>
      context.sendToCrunch(PerformCrunchOnFlights(flights))
      context.sendToCrunch(GetLatestCrunch(crunchTerminal, deskToInspect))
      context.fishForMessage(15 seconds, "Looking for CrunchResult in BST test") {
        case cr: CrunchResult => true
      }
    }
    result
  }

  private def crunchActorProps(hoursToCrunch: Int, timeProvider: () => DateTime) = {
    val airportConfig: AirportConfig = CrunchTests.airportConfigForHours(hoursToCrunch)
    val walkTimeProvider: GateOrStandWalkTime = (_, _) => Some(0L)
    val paxFlowCalculator = (flight: ApiFlight) => {
      PaxFlow.makeFlightPaxFlowCalculator(
        PaxFlow.splitRatioForFlight(SplitsProvider.defaultProvider(airportConfig) :: Nil),
        PaxFlow.pcpArrivalTimeForFlight(airportConfig)(gateOrStandWalkTimeCalculator(walkTimeProvider, walkTimeProvider, airportConfig.defaultWalkTimeMillis)))(flight)
    }
    val props = Props(classOf[ProdCrunchActor], hoursToCrunch, airportConfig, paxFlowCalculator, timeProvider)
    props
  }

  private def assertCrunchResult(result: Any, expectedMidnightLocalTime: Long, expectedFirstMinuteOfNonZeroWaitTime: Int): Boolean = {
    result match {
      case cr@CrunchResult(`expectedMidnightLocalTime`, _, _, waitTimes) =>
        waitTimes.indexWhere(_ != 0) == expectedFirstMinuteOfNonZeroWaitTime
      case _ => false
    }
  }
}

class UnexpectedTerminalInFlightFeedsWhenCrunching extends SpecificationLike {
  isolated
  sequential

  import CrunchTests._

  "given a crunch actor" >> {
    "and we've not sent it flights" in {
      "when we ask for the latest crunch, we get a NoCrunchAvailable" in {
        withContext("nocrunch", () => DateTime.parse("2016-09-01T11:00")) {
          context =>
            context.sendToCrunch(GetLatestCrunch("A1", "eeaDesk"))
            context.expectMsg(NoCrunchAvailable())
            true
        }
      }
    }
    "given a crunch actor with airport config for terminals A1 and A2" >> {
      "when we send it a flight for A3" >> {
        "then the crunch should log an error (untested) and ??ignore the flight??, and crunch successfully" in {
          val splitsProviders = List(SplitsProvider.defaultProvider(airportConfig))
          val timeProvider = () => DateTime.parse("2016-09-01")
          val testActorProps = Props(classOf[ProdCrunchActor], 1,
            airportConfig,
            (flight: ApiFlight) => PaxFlow.makeFlightPaxFlowCalculator(
              PaxFlow.splitRatioForFlight(splitsProviders),
              PaxFlow.pcpArrivalTimeForFlight(airportConfig)((_: ApiFlight) => 0L))(flight),
            timeProvider)
          withContextCustomActor(testActorProps) {
            context =>
              println("here we are, born to be kings")
              val flights = Flights(
                List(
                  apiFlight("BA123", terminal = "A1", totalPax = 200, scheduledDatetime = "2016-09-01T10:31", flightId = 1),
                  apiFlight("EZ456", terminal = "A2", totalPax = 100, scheduledDatetime = "2016-09-01T10:30", flightId = 2),
                  apiFlight("RY789", terminal = "A3", totalPax = 200, scheduledDatetime = "2016-09-01T10:31", flightId = 3)))
              context.sendToCrunch(PerformCrunchOnFlights(flights.flights))
              context.sendToCrunch(GetLatestCrunch("A1", "eeaDesk"))
              context.expectMsgAnyClassOf(classOf[CrunchResult])
              true
          }
        }
      }
    }
  }
}


class StreamFlightCrunchTests
  extends Specification {
  isolated
  sequential

  val log = LoggerFactory.getLogger(getClass)

  implicit def probe2Success[R <: Probe[_]](r: R): Result = success

  "we tell the crunch actor about flights when they change" in {
    CrunchTests.withContext("tellCrunch") { context =>
      import WorkloadCalculatorTests._
      val flightsActor = context.system.actorOf(Props(classOf[FlightsActor], context.testActor, Actor.noSender), "flightsActor")
      val flights = Flights(
        List(apiFlight("BA123", totalPax = 200, scheduledDatetime = "2016-09-01T10:31")))
      flightsActor ! flights
      context.expectMsg(PerformCrunchOnFlights(flights.flights))
      true
    }
  }
  "and we have sent it a flight in A1" in {
    "when we ask for the latest crunch, we get a crunch result for the flight we've sent it" in {
      CrunchTests.withContext("canAskCrunch") { context =>
        val hoursToCrunch = 2
        val crunchActor = context.system.actorOf(Props(classOf[TestCrunchActor], hoursToCrunch, CrunchTests.airportConfigForHours(hoursToCrunch), () => DateTime.parse("2016-09-01T04:00Z")), "CrunchActor")

        val flights = Flights(
          List(apiFlight("BA123", terminal = "A1", totalPax = 200, scheduledDatetime = "2016-09-01T00:31")))

        crunchActor ! PerformCrunchOnFlights(flights.flights)
        crunchActor.tell(GetLatestCrunch("A1", "eeaDesk"), context.testActor)

        context.fishForMessage(15 seconds, "Looking for CrunchResult") {
          case _: CrunchResult => true
        } match {
          case CrunchResult(_, _, _, waitTimes) => waitTimes.indexWhere(_ != 0) == 91
        }
      }
    }
  }
}
