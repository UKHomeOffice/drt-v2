package controllers

import actors.{FlightsActor, GetFlights}
import akka.actor.{Actor, ActorRef, ActorSystem, Kill, PoisonPill, Props}
import akka.testkit.{ImplicitSender, TestKit}
import akka.pattern._
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import controllers.SystemActors.SplitsProvider
import drt.shared.{AirportConfig, ApiFlight, Arrival, BestPax}
import drt.shared.FlightsApi.Flights
import org.joda.time.DateTime
import org.specs2.mutable.{After, Before, SpecificationLike}
import server.protobuf.messages.FlightsMessage.FlightStateSnapshotMessage
import services.SplitsProvider
import services.SplitsProvider.SplitProvider
import services.WorkloadCalculatorTests.apiFlight
import services.inputfeeds.CrunchTests

import scala.collection.JavaConversions._
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._


case class TriggerV1Snapshot(newFlights: Map[Int, ApiFlight])

case object GetLastKnownPax

class FlightsTestActor(crunchActorRef: ActorRef,
                       dqApiSplitsActorRef: AskableActorRef,
                       csvSplitsProvider: SplitProvider,
                       bestPax: (Arrival) => Int)
  extends FlightsActor(crunchActorRef,
    dqApiSplitsActorRef,
    csvSplitsProvider,
    bestPax) {
  override val snapshotInterval = 1

  override def receive: Receive = {
    case TriggerV1Snapshot(newFlights) =>
      saveSnapshot(newFlights)

    case fssm: FlightStateSnapshotMessage =>
      saveSnapshot(fssm)
    case GetLastKnownPax =>
      sender() ! lastKnownPaxState
  }
}

class FlightsPersistenceSpec extends AkkaTestkitSpecs2SupportForPersistence("target/flightsPersistence") with SpecificationLike with Before {
  sequential
  isolated

  def before = {
    PersistenceCleanup.deleteJournal(s"$dbLocation/snapshot")
    PersistenceCleanup.deleteJournal(dbLocation)
  }

  val testSplitsProvider: SplitsProvider = SplitsProvider.emptyProvider

  "FlightsActor " should {

    "Store a flight and retrieve it after a shutdown" in {
      setFlightsAndShutdownActorSystem(Flights(List(apiFlight("SA0123", "STN", 1, "2017-10-02T20:00"))))
      val result = startNewActorSystemAndRetrieveFlights match {
        case Flights(flights) => flights.toSet
      }

      val expected = Set(apiFlight("SA0123", "STN", 1, "2017-10-02T20:00"))

      result === expected
    }

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

  def flightsActor(system: ActorSystem, airportCode: String = "EDI") = {
    system.actorOf(Props(
      classOf[FlightsActor],
      crunchActor(system),
      Actor.noSender,
      testSplitsProvider,
      BestPax(airportCode)), "FlightsActor")
  }

  def crunchActor(system: ActorSystem) = {
    val airportConfig: AirportConfig = CrunchTests.airportConfig
    val timeProvider = () => new DateTime(2016, 1, 1, 0, 0)
    val props = Props(classOf[ProdCrunchActor], 1, airportConfig, SplitsProvider.defaultProvider(airportConfig) :: Nil, timeProvider, BestPax.bestPax)
    val crunchActor = system.actorOf(props, "CrunchActor")
    crunchActor
  }

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

  def setFlightsAndShutdownActorSystem(flights: Flights) = {
    val testKit1 = new AkkaTestkitSpecs2SupportForPersistence("target/testFlightsActor") {
      def setFlights(flights: Flights) = {
        flightsActor(system) ! flights
      }
    }
    testKit1.setFlights(flights)
    testKit1.shutDownActorSystem
  }

  def setFlightsAndShutdownActorSystem(flightSets: Set[Flights]) = {
    val testKit1 = new AkkaTestkitSpecs2SupportForPersistence("target/testFlightsActor") {
      def setFlights(flightSets: Set[Flights]) = {
        val flightsActorRef = flightsActor(system)
        flightSets.foreach(flights => {
          flightsActorRef ! flights
        })
      }
    }
    testKit1.setFlights(flightSets)
    testKit1.shutDownActorSystem
  }

  def flightsActorWithSnapshotIntervalOf1(system: ActorSystem, airportCode: String = "EDI") = {
    system.actorOf(Props(
      classOf[FlightsTestActor],
      crunchActor(system),
      Actor.noSender,
      testSplitsProvider,
      BestPax.bestPax
    ), "FlightsActor")
  }

  implicit val timeout: Timeout = Timeout(0.5 seconds)

  def startNewActorSystemAndRetrieveFlights: Flights = {
    val testKit2 = new AkkaTestkitSpecs2SupportForPersistence("target/testFlightsActor") {
      def getFlights = {
        val futureResult = flightsActor(system) ? GetFlights
        Await.result(futureResult, 2 seconds).asInstanceOf[Flights]
      }
    }

    val result = testKit2.getFlights
    testKit2.shutDownActorSystem
    result
  }
}
