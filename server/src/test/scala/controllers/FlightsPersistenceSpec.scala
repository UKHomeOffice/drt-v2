package controllers

import actors.{FlightsActor, GetFlights}
import akka.actor.{Actor, ActorSystem, Kill, PoisonPill, Props}
import akka.testkit.{ImplicitSender, TestKit}
import akka.pattern._
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import controllers.SystemActors.SplitsProvider
import drt.shared.{AirportConfig, BestPax}
import drt.shared.FlightsApi.Flights
import org.joda.time.DateTime
import org.specs2.mutable.{After, Before, SpecificationLike}
import services.SplitsProvider
import services.WorkloadCalculatorTests.apiFlight
import services.inputfeeds.CrunchTests

import scala.collection.JavaConversions._
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._


class FlightsPersistenceSpec extends AkkaTestkitSpecs2SupportForPersistence("target/flightsPersistence") with SpecificationLike with Before {
  sequential
  isolated

  def before = {
    PersistenceCleanup.deleteJournal(s"$dbLocation/snapshot")
    PersistenceCleanup.deleteJournal(dbLocation)
  }

  val testSplitsProvider: SplitsProvider = SplitsProvider.emptyProvider

  private def flightsAndCrunchActors = {
    val crunchActorRef = crunchActor
    val flightsActorRef = system.actorOf(Props(
      classOf[FlightsActor],
      crunchActorRef,
      Actor.noSender,
      testSplitsProvider,
      BestPax.bestPax), "flightActor")
    (flightsActorRef, crunchActorRef)
  }

  private def crunchActor = {
    val airportConfig: AirportConfig = CrunchTests.airportConfig
    val timeProvider = () => new DateTime(2016, 1, 1, 0, 0)
    val props = Props(classOf[ProdCrunchActor], 1, airportConfig, SplitsProvider.defaultProvider(airportConfig) :: Nil, timeProvider, BestPax.bestPax)
    val crunchActor = system.actorOf(props, "crunchActor")
    crunchActor
  }

  implicit val timeout: Timeout = Timeout(5 seconds)

  "FlightsActor " should {
    "Store two sets of flights and retrieve flights from both sets after a shutdown" in {
      val (flightsActorRef1, crunchActorRef1) = flightsAndCrunchActors

      Set(
        Flights(List(apiFlight(flightId = 1, flightCode = "SA0123", airportCode = "JFK", totalPax = 100, scheduledDatetime = "2017-10-02T20:00"))),
        Flights(List(apiFlight(flightId = 2, flightCode = "BA0001", airportCode = "JFK", totalPax = 150, scheduledDatetime = "2017-10-02T21:55")))
      ).foreach(flights => {
        flightsActorRef1 ! flights
      })

      Await.ready(flightsActorRef1 ? GetFlights, 5 seconds)
      Await.ready(gracefulStop(flightsActorRef1, 5 seconds), 6 seconds)
      Await.ready(gracefulStop(crunchActorRef1, 5 seconds), 6 seconds)

      val (flightsActorRef2, _) = flightsAndCrunchActors

      val futureResult = flightsActorRef2 ? GetFlights

      val result = Await.result(futureResult, 2 seconds).asInstanceOf[Flights] match {
        case Flights(flights) => flights.toSet
      }

      val expected = Set(
        apiFlight(flightId = 1, flightCode = "SA0123", airportCode = "JFK", totalPax = 100, scheduledDatetime = "2017-10-02T20:00"),
        apiFlight(flightId = 2, flightCode = "BA0001", airportCode = "JFK", totalPax = 150, scheduledDatetime = "2017-10-02T21:55")
      )

      result === expected
    }
  }
}
