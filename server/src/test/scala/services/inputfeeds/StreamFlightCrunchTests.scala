package services.inputfeeds

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.stream.testkit.TestSubscriber.Probe
import akka.stream.testkit.scaladsl.TestSink
import akka.testkit.{ImplicitSender, TestKit}
import com.typesafe.config.ConfigFactory
import controllers._
import org.joda.time.DateTime
import org.specs2.execute.Result
import org.specs2.mutable.SpecificationLike
import org.specs2.specification.AfterAll
import services.FlightCrunchInteractionTests.TestCrunchActor
import services.WorkloadCalculatorTests._
import services.{FlightCrunchInteractionTests, SplitsProvider, WorkloadCalculatorTests}
import spatutorial.shared.FlightsApi.Flights
import spatutorial.shared._

import scala.concurrent.duration._
import scala.collection.immutable.Seq

object CrunchTests {

  case class TestContext(override val system: ActorSystem, props: Props) extends TestKit(system) with ImplicitSender {
    def sendToCrunch[T](o: T) = crunchActor ! o

    lazy val crunchActor = createCrunchActor

    def createCrunchActor: ActorRef = {
      system.actorOf(props, "CrunchActor")
    }

    def getCrunchActor = system.actorSelection("CrunchActor")
  }

  def withContextCustomActor[T](props: Props)(f: (TestContext) => T): T = {
    val context = TestContext(ActorSystem("streamCrunchTests", ConfigFactory.empty()), props: Props)
    val res = f(context)
    TestKit.shutdownActorSystem(context.system)
    res
  }

  lazy val airportConfig = AirportConfig(
    defaultPaxSplits = List(
      SplitRatio(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eeaDesk), 0.5),
      SplitRatio(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eGate), 0.5)),
    defaultProcessingTimes = Map(
      "A1" ->
        Map(
          PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eeaDesk) -> 20d / 60,
          PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eGate) -> 25d / 60)),
    queues = Seq("eeaDesk", "eGate"),
    portCode = "EDI",
    slaByQueue = Map("eeaDesk" -> 25, "eGate" -> 5),
    terminalNames = Seq("A1")
  )

  def withContext[T](timeProvider: () => DateTime = () => DateTime.now())(f: (TestContext) => T): T = {
    val props = Props(classOf[FlightCrunchInteractionTests.TestCrunchActor], 1, airportConfig, timeProvider)
    val context = TestContext(ActorSystem("streamCrunchTests", ConfigFactory.empty()), props)
    val res = f(context)
    TestKit.shutdownActorSystem(context.system)
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
        val props = Props(classOf[ProdCrunchActor], 1, airportConfig, SplitsProvider.defaultProvider(airportConfig) :: Nil, timeProvider)

        withContextCustomActor(props) { context =>
          val flights = Flights(
            List(
              apiFlight("BA123", terminal = "A1", totalPax = 200, scheduledDatetime = "2016-01-01T00:00", flightId = 1),
              apiFlight("EZ456", terminal = "A2", totalPax = 100, scheduledDatetime = "2016-01-01T00:00", flightId = 2)))
          context.sendToCrunch(PerformCrunchOnFlights(flights.flights))
          context.sendToCrunch(GetLatestCrunch("A1", "eeaDesk"))
          val exp =
            CrunchResultWithTimeAndInterval(
              timeProvider().getMillis,
              60000,
              Vector(2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2),Vector(1, 1, 2, 2, 2, 3, 3, 4, 4, 4, 5, 5, 6, 6, 6, 7, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))

          context.expectMsg(15 seconds, exp)
          true
        }
      }

      "when we ask for the latest crunch for eGates at terminal A1, we get a crunch result only including flights at that terminal" in {
        val airportConfig: AirportConfig = CrunchTests.airportConfig
        val timeProvider = () => new DateTime(2016, 1, 1, 0, 0)
        val props = Props(classOf[ProdCrunchActor], 1, airportConfig, SplitsProvider.defaultProvider(airportConfig) :: Nil, timeProvider)

        withContextCustomActor(props) { context =>
          val flights = Flights(
            List(
              apiFlight("BA123", terminal = "A1", totalPax = 200, scheduledDatetime = "2016-01-01T01:00", flightId = 1)/*,
              apiFlight("EZ456", terminal = "A2", totalPax = 100, scheduledDatetime = "2016-01-01T10:00", flightId = 2)*/))
          context.sendToCrunch(PerformCrunchOnFlights(flights.flights))
          context.sendToCrunch(GetLatestCrunch("A1", "eGate"))
          val exp =
            CrunchResultWithTimeAndInterval(
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
      //      "when we ask for the latest crunch for  terminal A2, we get a crunch result only including flights at that terminal, and the wait times will be lower" in {
      //        val edi: AirportConfig = AirportConfigs.edi
      //        val splitsProviders = List(SplitsProvider.defaultProvider(edi))
      //        val timeProvider = () => DateTime.parse("2016-09-01")
      //        val testActorProps = Props(classOf[ProdCrunchActor], 1, edi, splitsProviders, timeProvider)
      //        withContextCustomActor(testActorProps) { context =>
      //          val flights = Flights(
      //            List(
      //              apiFlight("BA123", terminal = "A1", totalPax = 200, scheduledDatetime = "2016-09-01T10:31"),
      //              apiFlight("EZ456", terminal = "A2", totalPax = 100, scheduledDatetime = "2016-09-01T10:30")))
      //          context.sendToCrunch(PerformCrunchOnFlights(flights.flights))
      //          context.sendToCrunch(GetLatestCrunch("A2", "eeaDesk"))
      //
      //          val expectedLowerWaitTimes = CrunchResult(
      //            Vector(2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2),
      //            Vector(1, 1, 2, 2, 2, 3, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
      //          context.expectMsg(10 seconds, expectedLowerWaitTimes)
      //          true
      //        }
      //      }
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
        withContext(() => DateTime.parse("2016-09-01T11:00")) {
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
          val edi: AirportConfig = AirportConfigs.edi
          val splitsProviders = List(SplitsProvider.defaultProvider(edi))
          val timeProvider = () => DateTime.parse("2016-09-01")
          val testActorProps = Props(classOf[ProdCrunchActor], 1, edi, splitsProviders, timeProvider)
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
                CrunchResultWithTimeAndInterval(
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


class StreamFlightCrunchTests extends
  TestKit(ActorSystem("streamCrunchTests", ConfigFactory.empty()))
  with ImplicitSender
  with AfterAll
  with SpecificationLike {
  isolated
  sequential

  implicit val mat = ActorMaterializer()

  def afterAll() = TestKit.shutdownActorSystem(system)

  implicit def probe2Success[R <: Probe[_]](r: R): Result = success

  def createCrunchActor(hours: Int = 24, airportConfig: AirportConfig = AirportConfigs.edi, timeProvider: () => DateTime = () => DateTime.now()): ActorRef = {
    system.actorOf(Props(classOf[TestCrunchActor], hours, airportConfig, timeProvider), "CrunchActor")
  }

  "Streamed Flight tests" >> {
    "can split a stream into two materializers" in {
      val source = Source(List(1, 2, 3, 4))
      val doubled = source.map((x) => x * 2)
      val squared = source.map((x) => x * x)
      val actDouble = doubled
        .runWith(TestSink.probe[Int])
        .toStrict(FiniteDuration(1, SECONDS))

      assert(actDouble == List(2, 4, 6, 8))
      val actSquared = squared
        .runWith(TestSink.probe[Int])
        .toStrict(FiniteDuration(1, SECONDS))

      actSquared == List(1, 4, 9, 16)
    }

    "we tell the crunch actor about flights when they change" in {
      import WorkloadCalculatorTests._
      val flightsActor = system.actorOf(Props(classOf[FlightsActor], testActor), "flightsActor")
      val flights = Flights(
        List(apiFlight("BA123", totalPax = 200, scheduledDatetime = "2016-09-01T10:31")))
      flightsActor ! flights
      expectMsg(PerformCrunchOnFlights(flights.flights))
      true
    }
    "and we have sent it a flight in A1" in {
      "when we ask for the latest crunch, we get a crunch result for the flight we've sent it" in {
        val crunchActor = createCrunchActor(hours = 1, timeProvider = () => DateTime.parse("2016-09-01"))
        val flights = Flights(
          List(apiFlight("BA123", terminal = "A1", totalPax = 200, scheduledDatetime = "2016-09-01T10:31")))

        crunchActor ! PerformCrunchOnFlights(flights.flights)
        crunchActor ! GetLatestCrunch("A1", "eeaDesk")

        expectMsg(10 seconds,
          CrunchResultWithTimeAndInterval(
            DateTime.parse("2016-09-01").getMillis,
            60000,
            Vector(2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2),
            Vector(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)))
        true
      }
      //        "and we have sent it flights for different terminal  - A1" in {
      //          val crunchActor = createCrunchActor
      //          val flights = Flights(
      //            List(
      //              apiFlight("BA123", terminal = "A1", totalPax = 200, scheduledDatetime = "2016-09-01T10:31"),
      //              apiFlight("EZ456", terminal = "A2", totalPax = 100, scheduledDatetime = "2016-09-01T10:30")))
      //          crunchActor ! PerformCrunchOnFlights(flights.flights)
      //
      //          "when we ask for the latest crunch for a terminal, we get a crunch result only including flights at that terminal" in {
      //            crunchActor ! GetLatestCrunch("A1", "eeaDesk")
      //            val exp = CrunchResult(Vector(2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2), Vector(1, 1, 2, 2, 2, 3, 3, 3, 4, 4, 4, 5, 5, 6, 6, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
      //
      //            expectMsg(10 seconds,
      //              CrunchResult(
      //                recommendedDesks = Vector(2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2),
      //                waitTimes = Vector(1, 1, 2, 2, 2, 3, 3, 3, 4, 4, 4, 5, 5, 6, 6, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)))
      //            true
      //          }
      //        }
    }
  }

}

