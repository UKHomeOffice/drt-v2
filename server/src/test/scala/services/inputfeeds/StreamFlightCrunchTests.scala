package services.inputfeeds

import akka.actor.{ActorSystem, Props}
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
import spatutorial.shared.{NoCrunchAvailable, CrunchResult}
import spatutorial.shared.FlightsApi.Flights

import scala.concurrent.duration._

class StreamFlightCrunchTests extends TestKit(ActorSystem("streamCrunchTests", ConfigFactory.empty()))
  with ImplicitSender
  with AfterAll
  with SpecificationLike {
  isolated

  implicit val mat = ActorMaterializer()

  def afterAll() = TestKit.shutdownActorSystem(system)

  implicit def probe2Success[R <: Probe[_]](r: R): Result = success

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
          val crunchActor = system.actorOf(Props(classOf[CrunchActor], 1), "CrunchActor")
          crunchActor ! GetLatestCrunch()
          expectMsg(NoCrunchAvailable())
          true
        }
      }
      "and we have sent it flights" in {
        "when we ask for the latest crunch, we get a crunch result for the flights we've sent it" in {
          val crunchActor = system.actorOf(Props(classOf[CrunchActor], 1), "CrunchActor")
          val flights = Flights(
            List(apiFlight("BA123", totalPax = 200, scheduledDatetime = "2016-09-01T10:31")))

          crunchActor ! CrunchFlightsChange(flights.flights)
          crunchActor ! GetLatestCrunch()
          val exp = CrunchResult(Vector(2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2),Vector(1, 1, 2, 2, 2, 3, 3, 3, 4, 4, 4, 5, 5, 6, 6, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))

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


