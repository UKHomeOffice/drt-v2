package controllers

import actors.GetFlights
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern._
import akka.testkit.TestProbe
import akka.util.Timeout
import controllers.SystemActors.SplitsProvider
import drt.shared.FlightsApi.Flights
import drt.shared._
import org.specs2.mutable.Specification
//import services.WorkloadCalculatorTests.apiFlight
import controllers.ArrivalGenerator.apiFlight
import services.{SDate, SplitsProvider}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.Success

class FlightsActorSpec extends Specification {
  sequential
  isolated

  val testSplitsProvider: SplitsProvider = SplitsProvider.emptyProvider
  val testPcpArrival: (Arrival) => MilliDate = (a: Arrival) => MilliDate(SDate.parseString(a.SchDT).millisSinceEpoch)

  private def flightsActor(system: ActorSystem, airportCode: String = "EDI") = {
    system.actorOf(Props(
      classOf[FlightsActor],
      Actor.noSender,
      system.actorOf(Props(classOf[SplitsTestActor]), "splits-test-actor"),
      TestProbe()(system).ref,
      testSplitsProvider,
      BestPax(airportCode),
      testPcpArrival,
      AirportConfigs.edi
    ), "FlightsActor")
  }

  "FlightsActor " should {
    "Store a flight and retrieve it" in new AkkaTestkitSpecs2SupportForPersistence("target/testFlightsActor") {
      implicit val timeout: Timeout = Timeout(5 seconds)
      val actor: ActorRef = flightsActor(system)

      actor ! Flights(List(apiFlight(flightId = 1, iata = "SA0123", airportId = "STN", actPax = 1, schDt = "2050-08-02T20:00")))

      val futureResult: Future[Any] = actor ? GetFlights
      val futureFlights: Future[List[Arrival]] = futureResult.collect {
        case Success(Flights(fs)) => fs
      }

      val result = Await.result(futureResult, 1 second).asInstanceOf[Flights] match {
        case Flights(flights) => flights.toSet
      }

      val expected = Set(apiFlight(flightId = 1, iata = "SA0123", airportId = "STN", actPax = 1, schDt = "2050-08-02T20:00"))

      result === expected
    }
  }
}
