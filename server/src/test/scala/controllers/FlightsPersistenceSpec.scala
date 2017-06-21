package controllers

import actors.{FlightsActor, GetFlights}
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern._
import akka.util.Timeout
import controllers.ArrivalGenerator.apiFlight
import controllers.SystemActors.SplitsProvider
import drt.shared.FlightsApi.Flights
import drt.shared._
import org.joda.time.DateTime
import org.specs2.mutable.{BeforeAfter, SpecificationLike}
import server.protobuf.messages.FlightsMessage.FlightStateSnapshotMessage
import services.SplitsProvider.SplitProvider
import services.inputfeeds.CrunchTests
import services.{SDate, SplitsProvider}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.Success
import scala.concurrent.ExecutionContext.Implicits.global


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

    "Remember the previous Pax for a flight and use them if the flight comes in with default pax" in
      new AkkaTestkitSpecs2SupportForPersistence("target/testFlightsActor") {
        implicit val timeout: Timeout = Timeout(5 seconds)
        val actor: ActorRef = flightsActor(system = system, airportCode = "LHR")

        actor ! Flights(List(apiFlight(flightId = 1, iata = "SA0124", airportId = "LHR", actPax = 300, schDt = "2017-08-01T20:00")))
        actor ! Flights(List(apiFlight(flightId = 2, iata = "SA0124", airportId = "LHR", actPax = 200, schDt = "2017-08-02T20:00")))

        val futureResult: Future[Any] = actor ? GetFlights
        val futureFlights: Future[List[Arrival]] = futureResult.collect {
          case Success(Flights(fs)) => fs
        }

        val result = Await.result(futureResult, 1 second)

        val expected = Flights(List(
          apiFlight(flightId = 1, iata = "SA0124", airportId = "LHR", actPax = 300, schDt = "2017-08-01T20:00"),
          apiFlight(flightId = 2, iata = "SA0124", airportId = "LHR", actPax = 200, schDt = "2017-08-02T20:00", lastKnownPax = Option(300))))

        result === expected
      }

//    "Restore from a v1 snapshot using legacy ApiFlight" in
//      new AkkaTestkitSpecs2SupportForPersistence("target/testFlightsActor") {
//        createV1SnapshotAndShutdownActorSystem(Map(1 -> legacyApiFlight("SA0123", "STN", 1, "2017-10-02T20:00")))
//        val result = startNewActorSystemAndRetrieveFlights
//
//        Flights(List(apiFlight("SA0123", "STN", 1, "2017-10-02T20:00"))) === result
//      }
//    "Restore from a v2 snapshot using protobuf" in
//      new AkkaTestkitSpecs2SupportForPersistence("target/testFlightsActor") {
//        createV2SnapshotAndShutdownActorSystem(FlightStateSnapshotMessage(
//          Seq(FlightMessage(iATA = Option("SA324"))),
//          Seq(FlightLastKnownPaxMessage(Option("SA324"), Option(300)))
//        ))
//        val result = startNewActorSystemAndRetrieveFlights
//
//        result === Flights(List(Arrival("", "", "", "", "", "", "", "", 0, 0, 0, "", "", 0, "", "", "", "SA324", "", "", 0, None)))
//      }
//    "Restore from a v2 snapshot using protobuf" in
//      new AkkaTestkitSpecs2SupportForPersistence("target/testFlightsActor") {
//        createV2SnapshotAndShutdownActorSystem(FlightStateSnapshotMessage(
//          Seq(FlightMessage(iATA = Option("SA324"))),
//          Seq(FlightLastKnownPaxMessage(Option("SA324"), Option(300)))
//        ))
//        val result = startNewActorSystemAndRetrieveLastKnownPax
//
//        result === Map("SA324" -> 300)
//      }
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

  def flightsActor(system: ActorSystem, crunchActorRef: ActorRef = crunchActor(system), airportCode: String = "EDI") = {
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
