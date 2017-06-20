package controllers

import actors.{FlightsActor, GetFlights}
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern._
import akka.util.Timeout
import controllers.SystemActors.SplitsProvider
import drt.shared.FlightsApi.Flights
import drt.shared._
import org.joda.time.DateTime
import org.specs2.mutable.{After, Before, BeforeAfter, SpecificationLike}
import server.protobuf.messages.FlightsMessage.FlightStateSnapshotMessage
import services.SplitsProvider.SplitProvider
import ArrivalGenerator.apiFlight
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

class FlightsPersistenceSpec extends AkkaTestkitSpecs2SupportForPersistence("target/flightsPersistence")
  with SpecificationLike
  with BeforeAfter {
  sequential
  isolated

  override def before = {
    PersistenceCleanup.deleteJournal(s"$dbLocation/snapshot")
    PersistenceCleanup.deleteJournal(dbLocation)
  }

  override def after = {
    PersistenceCleanup.deleteJournal(s"$dbLocation/snapshot")
    PersistenceCleanup.deleteJournal(dbLocation)
  }

  val testSplitsProvider: SplitsProvider = SplitsProvider.emptyProvider

  "FlightsActor " should {

    "Store a flight and retrieve it after a shutdown" in {
      val arrivals = List(apiFlight(flightId = 1, iata = "SA0123", airportId = "STN", actPax = 100, schDt = "2017-10-02T20:00:00Z"))
      setFlightsAndStopActors(arrivals)

      val result = getFlightsAsSet
      val expected = Set(apiFlight(flightId = 1, iata = "SA0123", airportId = "STN", actPax = 100, schDt = "2017-10-02T20:00:00Z"))

      result === expected
    }

    "Store two sets of flights and retrieve flights from both sets after a shutdown" in {
      val flightsSet = Set(
        Flights(List(apiFlight(flightId = 1, iata = "SA0123", airportId = "JFK", actPax = 100, schDt = "2017-10-02T20:00:00Z"))),
        Flights(List(apiFlight(flightId = 2, iata = "BA0001", airportId = "JFK", actPax = 150, schDt = "2017-10-02T21:55:00Z")))
      )
      setFlightsStopAndSleep(flightsSet)

      val result = getFlightsAsSet
      val expected = Set(
        apiFlight(flightId = 1, iata = "SA0123", airportId = "JFK", actPax = 100, schDt = "2017-10-02T20:00:00Z"),
        apiFlight(flightId = 2, iata = "BA0001", airportId = "JFK", actPax = 150, schDt = "2017-10-02T21:55:00Z")
      )

      result === expected
    }
  }

  implicit val timeout: Timeout = Timeout(0.5 seconds)

  def setFlightsStopAndSleep(flightsSet: Set[Flights]) = {
    val (flightsActorRef1, crunchActorRef1) = flightsAndCrunchActors(system)

    flightsSet.foreach(flightsActorRef1 ! _)

    Await.ready(flightsActorRef1 ? GetFlights, 1 seconds)
    system.stop(flightsActorRef1)
    system.stop(crunchActorRef1)
    Thread.sleep(100L)
  }

  def getFlightsAsSet = {
    val (flightsActorRef2, crunchActorRef2) = flightsAndCrunchActors(system)
    val futureResult = flightsActorRef2 ? GetFlights

    val result = Await.result(futureResult, 1 seconds).asInstanceOf[Flights] match {
      case Flights(flights) => flights.toSet
    }
    stopAndShutdown(flightsActorRef2, crunchActorRef2)
    result
  }

  def setFlightsAndStopActors(arrivals: List[Arrival]) = {
    val (flightsActorRef1, crunchActorRef1) = flightsAndCrunchActors(system)

    flightsActorRef1 ! Flights(arrivals)

    syncStopAndSleep(flightsActorRef1, crunchActorRef1)
  }

  def stopAndShutdown(flightsActorRef2: ActorRef, crunchActorRef2: ActorRef) = {
    system.stop(flightsActorRef2)
    system.stop(crunchActorRef2)
    shutDownActorSystem
  }

  def syncStopAndSleep(flightsActorRef1: ActorRef, crunchActorRef1: ActorRef) = {
    Await.ready(flightsActorRef1 ? GetFlights, 1 seconds)
    system.stop(flightsActorRef1)
    system.stop(crunchActorRef1)
    Thread.sleep(100L)
  }

  def flightsAndCrunchActors(system: ActorSystem) = {
    val crunchActorRef = crunchActor(system)
    val flightsActorRef = flightsActor(system, crunchActorRef)
    (flightsActorRef, crunchActorRef)
  }

  def flightsActor(system: ActorSystem, crunchActorRef: ActorRef, airportCode: String = "EDI") = {
    system.actorOf(Props(
      classOf[FlightsActor],
      crunchActorRef,
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
    system.actorOf(props, "crunchActor")
  }
}
