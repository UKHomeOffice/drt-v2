package controllers

import actors.{FlightsActor, GetFlights}
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern._
import akka.util.Timeout
import controllers.SystemActors.SplitsProvider
import drt.shared.FlightsApi.Flights
import drt.shared._
import org.joda.time.DateTime
import org.specs2.mutable.{Before, SpecificationLike}
import server.protobuf.messages.FlightsMessage.FlightStateSnapshotMessage
import services.SplitsProvider.SplitProvider
import services.WorkloadCalculatorTests.apiFlight
import services.inputfeeds.CrunchTests
import services.{SDate, SplitsProvider}

import scala.concurrent.Await
import scala.concurrent.duration._


case class TriggerV1Snapshot(newFlights: Map[Int, ApiFlight])

case object GetLastKnownPax

class FlightsTestActor(crunchActorRef: ActorRef,
                       dqApiSplitsActorRef: AskableActorRef,
                       csvSplitsProvider: SplitProvider,
                       bestPax: (Arrival) => Int,
                       pcpArrivalTimeForFlight: (Arrival) => MilliDate = (a: Arrival) => MilliDate(SDate(a.ActChoxDT).millisSinceEpoch))
  extends FlightsActor(crunchActorRef,
    dqApiSplitsActorRef,
    csvSplitsProvider,
    bestPax,
    pcpArrivalTimeForFlight) {
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
      setFlightsAndShutdownActorSystem(Flights(List(apiFlight("SA0123", "STN", 1, "2017-10-02T20:00:00Z"))))
      val result = startNewActorSystemAndRetrieveFlights match {
        case Flights(flights) => flights.toSet
      }

      val expected = Set(apiFlight("SA0123", "STN", 1, "2017-10-02T20:00:00Z"))

      result === expected
    }

    "Store two sets of flights and retrieve flights from both sets after a shutdown" in {
      val (flightsActorRef1, crunchActorRef1) = flightsAndCrunchActors

      Set(
        Flights(List(apiFlight(flightId = 1, flightCode = "SA0123", airportCode = "JFK", totalPax = 100, scheduledDatetime = "2017-10-02T20:00:00Z"))),
        Flights(List(apiFlight(flightId = 2, flightCode = "BA0001", airportCode = "JFK", totalPax = 150, scheduledDatetime = "2017-10-02T21:55:00Z")))
      ).foreach(flights => {
        flightsActorRef1 ! flights
      })

      Await.ready(flightsActorRef1 ? GetFlights, 5 seconds)
      system.stop(flightsActorRef1)
      system.stop(crunchActorRef1)
      Thread.sleep(200L)

      val (flightsActorRef2, _) = flightsAndCrunchActors

      val futureResult = flightsActorRef2 ? GetFlights

      val result = Await.result(futureResult, 2 seconds).asInstanceOf[Flights] match {
        case Flights(flights) => flights.toSet
      }

      val expected = Set(
        apiFlight(flightId = 1, flightCode = "SA0123", airportCode = "JFK", totalPax = 100, scheduledDatetime = "2017-10-02T20:00:00Z"),
        apiFlight(flightId = 2, flightCode = "BA0001", airportCode = "JFK", totalPax = 150, scheduledDatetime = "2017-10-02T21:55:00Z")
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
      BestPax(airportCode),
      (a: Arrival) => MilliDate(SDate(a.SchDT).millisSinceEpoch)
    ), "FlightsActor")
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
      BestPax.bestPax,
      (a: Arrival) => MilliDate(SDate(a.SchDT).millisSinceEpoch)
    ), "flightActor")
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
