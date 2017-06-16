package controllers

import java.util.UUID

import actors.{FlightsActor, GetFlights}
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.util.Timeout
import org.specs2.mutable.Specification
import services.WorkloadCalculatorTests.apiFlight
import services.SplitsProvider
import drt.shared.FlightsApi.Flights
import drt.shared._
import akka.pattern._
import controllers.SystemActors.SplitsProvider
import org.joda.time.DateTime
import server.protobuf.messages.FlightsMessage.{FlightLastKnownPaxMessage, FlightMessage, FlightStateSnapshotMessage}
import services.SplitsProvider.SplitProvider
import services.inputfeeds.CrunchTests

import scala.concurrent.ExecutionContext.Implicits.global
import scala.collection.immutable.Seq
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.util.Success



class FlightsActorSpec extends Specification {
  sequential
  isolated

  val testSplitsProvider: SplitsProvider = SplitsProvider.emptyProvider

  private def flightsActor(system: ActorSystem, airportCode: String = "EDI") = {
    system.actorOf(Props(
      classOf[FlightsActor],
      crunchActor(system),
      Actor.noSender,
      testSplitsProvider,
      BestPax(airportCode)), "FlightsActor")
  }

  private def crunchActor(system: ActorSystem) = {
    val airportConfig: AirportConfig = CrunchTests.airportConfig
    val timeProvider = () => new DateTime(2016, 1, 1, 0, 0)
    val props = Props(classOf[ProdCrunchActor], 1, airportConfig, SplitsProvider.defaultProvider(airportConfig) :: Nil, timeProvider, BestPax.bestPax)
    val crunchActor = system.actorOf(props, "CrunchActor")
    crunchActor
  }

  "FlightsActor " should {
    "Store a flight and retrieve it" in new AkkaTestkitSpecs2SupportForPersistence("target/testFlightsActor") {
      implicit val timeout: Timeout = Timeout(5 seconds)
      val actor: ActorRef = flightsActor(system)

      actor ! Flights(List(apiFlight(flightId = 1, flightCode = "SA0123", airportCode = "STN", totalPax = 1, scheduledDatetime = "2017-08-02T20:00")))

      val futureResult: Future[Any] = actor ? GetFlights
      val futureFlights: Future[List[Arrival]] = futureResult.collect {
        case Success(Flights(fs)) => fs
      }

      val result = Await.result(futureResult, 1 second).asInstanceOf[Flights] match {
        case Flights(flights) => flights.toSet
      }

      val expected = Set(apiFlight("SA0123", "STN", 1, "2017-08-02T20:00"))

      result === expected
    }
  }
}
