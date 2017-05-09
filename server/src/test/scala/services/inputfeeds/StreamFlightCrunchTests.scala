package services.inputfeeds

import java.io.File

import actors.{FlightsActor, GetLatestCrunch, PerformCrunchOnFlights}
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.stream.testkit.TestSubscriber.Probe
import akka.stream.testkit.scaladsl.TestSink
import akka.testkit.{ImplicitSender, TestKit}
import com.typesafe.config.ConfigFactory
import controllers._
import drt.shared.FlightsApi.Flights
import drt.shared.PaxTypesAndQueues._
import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios}
import drt.shared._
import org.joda.time.DateTime
import org.specs2.execute.Result
import org.specs2.mutable.{Specification, SpecificationLike}
import services.FlightCrunchInteractionTests.TestCrunchActor
import services.WorkloadCalculatorTests._
import services.{FlightCrunchInteractionTests, SplitsProvider, WorkloadCalculatorTests}
import drt.shared.FlightsApi.Flights
import drt.shared.PaxTypesAndQueues._
import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios}
import drt.shared._
import services.PcpArrival.FlightPcpArrivalTimeCalculator
import services.workloadcalculator.PaxLoadCalculator.{MillisSinceEpoch, PaxTypeAndQueueCount}

import collection.JavaConversions._
import scala.concurrent.duration._
import scala.collection.immutable.{IndexedSeq, Seq}


object CrunchTests {

  val airportConfig = AirportConfig(
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
    Map(
      "A1" -> Map(
        "eeaDesk" -> (List.fill[Int](1)(2), List.fill[Int](1)(25)),
        "nonEeaDesk" -> (List.fill[Int](1)(2), List.fill[Int](1)(25)),
        "eGate" -> (List.fill[Int](1)(2), List.fill[Int](1)(25))
      ),
      "A2" -> Map(
        "eeaDesk" -> (List.fill[Int](1)(2), List.fill[Int](1)(25)),
        "nonEeaDesk" -> (List.fill[Int](1)(2), List.fill[Int](1)(25)),
        "eGate" -> (List.fill[Int](1)(2), List.fill[Int](1)(25))
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


        val paxFlowCalculator = (flight: ApiFlight) => {
          PaxFlow.makeFlightPaxFlowCalculator(
            PaxFlow.splitRatioForFlight(SplitsProvider.defaultProvider(airportConfig) :: Nil),
            PaxFlow.pcpArrivalTimeForFlight(airportConfig)(WalkTimes.flightWalkTime(airportConfig.defaultWalkTimeMillis)))(flight)
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
        val paxFlowCalculator = (flight: ApiFlight) => {
          PaxFlow.makeFlightPaxFlowCalculator(
            PaxFlow.splitRatioForFlight(SplitsProvider.defaultProvider(airportConfig) :: Nil),
            PaxFlow.pcpArrivalTimeForFlight(airportConfig)(WalkTimes.flightWalkTime(airportConfig.defaultWalkTimeMillis)))(flight)
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
              PaxFlow.pcpArrivalTimeForFlight(airportConfig)((flight: ApiFlight) => 0L))(flight),
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
              val exp =
                CrunchResult(
                  timeProvider().getMillis,
                  60000,
                  Vector(2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2),
                  Vector(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))

              context.expectMsg(FiniteDuration(50, "seconds"), exp)
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
        val crunchActor = context.system.actorOf(Props(classOf[TestCrunchActor], 1, CrunchTests.airportConfig, () => DateTime.parse("2016-09-01")), "CrunchActor")

        val flights = Flights(
          List(apiFlight("BA123", terminal = "A1", totalPax = 200, scheduledDatetime = "2016-09-01T10:31")))

        crunchActor ! PerformCrunchOnFlights(flights.flights)
        crunchActor.tell(GetLatestCrunch("A1", "eeaDesk"), context.testActor)

        context.expectMsg(10 seconds,
          CrunchResult(
            DateTime.parse("2016-09-01").getMillis,
            60000,
            Vector(2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2),
            Vector(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)))
        true
      }
    }
  }
}
