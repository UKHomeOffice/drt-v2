package controllers

import java.util.UUID

import actors.{FlightsActor, GetFlights}
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.util.Timeout
import org.specs2.mutable.Specification
import services.{SplitsProvider, WorkloadCalculatorTests}
import spatutorial.shared.FlightsApi.Flights
import spatutorial.shared.{AirportConfig, ApiFlight, MilliDate, StaffMovement}
import akka.pattern._
import org.joda.time.DateTime
import services.inputfeeds.CrunchTests

import scala.concurrent.ExecutionContext.Implicits.global
import scala.collection.immutable.Seq
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.util.Success

class FlightsActorSpec extends Specification {
  sequential

  private def flightsActor(system: ActorSystem) = {
    val actor = system.actorOf(Props(classOf[FlightsActor], crunchActor(system)), "FlightsActor")
    actor
  }

  private def crunchActor(system: ActorSystem) = {
    val airportConfig: AirportConfig = CrunchTests.airportConfig
    val timeProvider = () => new DateTime(2016, 1, 1, 0, 0)
    val props = Props(classOf[ProdCrunchActor], 1, airportConfig, SplitsProvider.defaultProvider(airportConfig) :: Nil, timeProvider)
    val crunchActor = system.actorOf(props, "CrunchActor")
    crunchActor
  }

  "FlightsActor " should {
    "Store a flight and retrieve it" in new AkkaTestkitSpecs2Support("target/testFlightsActor") {
      implicit val timeout: Timeout = Timeout(5 seconds)
      val actor: ActorRef = flightsActor(system)

      actor ! Flights(List(WorkloadCalculatorTests.apiFlight("SA123", "STN", 1, "2017-08-02 20:00")))

      val futureResult: Future[Any] = actor ? GetFlights

      val result = Await.result(futureResult, 1 second)

      assert(Flights(List(WorkloadCalculatorTests.apiFlight("SA123", "STN", 1, "2017-08-02 20:00"))) == result)
    }

    "Store a flight and retrieve it after a shutdown" in {
      setFlightsAndShutdownActorSystem(Flights(List(WorkloadCalculatorTests.apiFlight("SA123", "STN", 1, "2017-10-02 20:00"))))
      val result = startNewActorSystemAndRetrieveFlights

      println(s"The result = $result")

      Flights(List(WorkloadCalculatorTests.apiFlight("SA123", "STN", 1, "2017-10-02 20:00"))) == result
    }
  }

  implicit val timeout: Timeout = Timeout(5 seconds)

  def startNewActorSystemAndRetrieveFlights() = {
    val testKit2 = new AkkaTestkitSpecs2Support("target/testFlightsActor") {
      def getFlights = {
        val futureResult = flightsActor(system) ? GetFlights
        Await.result(futureResult, 2 seconds)
      }
    }

    val result = testKit2.getFlights
    testKit2.shutDownActorSystem
    result
  }

  def setFlightsAndShutdownActorSystem(flights: Flights) = {
    val testKit1 = new AkkaTestkitSpecs2Support("target/testFlightsActor") {
      def setFlights(flights: Flights) = {
        flightsActor(system) ! flights
      }
    }
    testKit1.setFlights(flights)
    testKit1.shutDownActorSystem
  }
}
