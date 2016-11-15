package services.inputfeeds

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.stream.testkit.TestSubscriber.Probe
import akka.stream.testkit.scaladsl.TestSink
import akka.testkit.{ImplicitSender, TestKit}
import com.typesafe.config.ConfigFactory
import controllers._
import org.specs2.execute.Result
import org.specs2.mutable.SpecificationLike
import org.specs2.specification.AfterAll
import org.specs2.specification.core.Fragments
import services.WorkloadCalculatorTests
import services.WorkloadCalculatorTests._
import spatutorial.shared.{AirportConfigs, NoCrunchAvailable, CrunchResult}
import spatutorial.shared.FlightsApi.Flights

import scala.concurrent.duration._
import scalaz.Alpha.T

object CrunchTests {

  case class TestContext(override val system: ActorSystem) extends TestKit(system) with ImplicitSender {
    lazy val crunchActor = createCrunchActor

    def createCrunchActor: ActorRef = {
      system.actorOf(Props(classOf[CrunchActor], 1, AirportConfigs.edi), "CrunchActor")
    }

    def getCrunchActor = system.actorSelection("CrunchActor")
  }

  def withContext[T](f: (TestContext) => T): T = {
    val context = TestContext(ActorSystem("streamCrunchTests", ConfigFactory.empty()))
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
      withContext { context =>
        val crunchActor = context.createCrunchActor
        val flights = Flights(
          List(
            apiFlight("BA123", terminal = "A1", totalPax = 200, scheduledDatetime = "2016-09-01T10:31"),
            apiFlight("EZ456", terminal = "A2", totalPax = 100, scheduledDatetime = "2016-09-01T10:30")))
        crunchActor ! CrunchFlightsChange(flights.flights)

        "when we ask for the latest crunch for  terminal A1, we get a crunch result only including flights at that terminal" in {
          crunchActor ! GetLatestCrunch("A1", "eeaDesk")
          val exp = CrunchResult(Vector(2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2), Vector(1, 1, 2, 2, 2, 3, 3, 3, 4, 4, 4, 5, 5, 6, 6, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))

          context.expectMsg(11 seconds,
            CrunchResult(
              recommendedDesks = Vector(2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2),
              waitTimes = Vector(1, 1, 2, 2, 2, 3, 3, 3, 4, 4, 4, 5, 5, 6, 6, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)))
          true
        }

//        "when we ask for the latest crunch for  terminal A2, we get a crunch result only including flights at that terminal" in {
//          crunchActor ! GetLatestCrunch("A2", "eeaDesk")
//          val exp = CrunchResult(Vector(2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2), Vector(1, 1, 2, 2, 2, 3, 3, 3, 4, 4, 4, 5, 5, 6, 6, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
//
//          context.expectMsg(10 seconds,
//
//            CrunchResult(
//              recommendedDesks = Vector(2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2),
//              waitTimes = Vector(1, 1, 2, 2, 2, 3, 3, 3, 4, 4, 4, 5, 5, 6, 6, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)))
//          true
//        }
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
  //  sequential

  implicit val mat = ActorMaterializer()

  def afterAll() = TestKit.shutdownActorSystem(system)

  implicit def probe2Success[R <: Probe[_]](r: R): Result = success

  def createCrunchActor: ActorRef = {
    system.actorOf(Props(classOf[CrunchActor], 1, AirportConfigs.edi), "CrunchActor")
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
      expectMsg(CrunchFlightsChange(flights.flights))
      true
    }
    "given a crunch actor" >> {
      "and we've not sent it flights" in {
        "when we ask for the latest crunch, we get a NoCrunchAvailable" in {
          val crunchActor = createCrunchActor
          crunchActor ! GetLatestCrunch("A1", "eeaDesk")
          expectMsg(NoCrunchAvailable())
          true
        }
      }
      "and we have sent it a flight in A1" in {
        "when we ask for the latest crunch, we get a crunch result for the flight we've sent it" in {
          val crunchActor = createCrunchActor
          val flights = Flights(
            List(apiFlight("BA123", terminal = "A1", totalPax = 200, scheduledDatetime = "2016-09-01T10:31")))

          crunchActor ! CrunchFlightsChange(flights.flights)
          crunchActor ! GetLatestCrunch("A1", "eeaDesk")
          val exp = CrunchResult(Vector(2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2), Vector(1, 1, 2, 2, 2, 3, 3, 3, 4, 4, 4, 5, 5, 6, 6, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))

          expectMsg(10 seconds,
            CrunchResult(
              recommendedDesks = Vector(2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2),
              waitTimes = Vector(1, 1, 2, 2, 2, 3, 3, 3, 4, 4, 4, 5, 5, 6, 6, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)))
          true
        }
        "and we have sent it flights for different terminal  - A1" in {
          val crunchActor = createCrunchActor
          val flights = Flights(
            List(
              apiFlight("BA123", terminal = "A1", totalPax = 200, scheduledDatetime = "2016-09-01T10:31"),
              apiFlight("EZ456", terminal = "A2", totalPax = 100, scheduledDatetime = "2016-09-01T10:30")))
          crunchActor ! CrunchFlightsChange(flights.flights)

          "when we ask for the latest crunch for a terminal, we get a crunch result only including flights at that terminal" in {
            crunchActor ! GetLatestCrunch("A1", "eeaDesk")
            val exp = CrunchResult(Vector(2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2), Vector(1, 1, 2, 2, 2, 3, 3, 3, 4, 4, 4, 5, 5, 6, 6, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))

            expectMsg(10 seconds,
              CrunchResult(
                recommendedDesks = Vector(2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2),
                waitTimes = Vector(1, 1, 2, 2, 2, 3, 3, 3, 4, 4, 4, 5, 5, 6, 6, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)))
            true
          }
        }
      }
    }
  }

}

