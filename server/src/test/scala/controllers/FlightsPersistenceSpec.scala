package controllers

import java.util.UUID

import actors.{FlightsActor, GetFlights}
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern._
import akka.testkit.TestProbe
import akka.util.Timeout
import controllers.ArrivalGenerator.apiFlight
import controllers.SystemActors.SplitsProvider
import drt.shared.FlightsApi.Flights
import drt.shared.PassengerSplits.{SplitsPaxTypeAndQueueCount, VoyagePaxSplits}
import drt.shared.{AirportConfig, ApiFlight, BestPax, _}
import org.joda.time.{DateTime, DateTimeZone}
import org.specs2.mutable.{BeforeAfter, SpecificationLike}
import passengersplits.core.PassengerInfoRouterActor.ReportVoyagePaxSplit
import server.protobuf.messages.FlightsMessage.{FlightLastKnownPaxMessage, FlightMessage, FlightStateSnapshotMessage}
import services.SplitsProvider.SplitProvider
import services.inputfeeds.TestCrunchConfig
import services.{SDate, SplitsProvider}

import scala.collection.immutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.Success


case class TriggerV1Snapshot(newFlights: Map[Int, ApiFlight])

case object GetLastKnownPax

class FlightsTestActor(dqApiSplitsActorRef: ActorRef,
                       csvSplitsProvider: SplitProvider,
                       subscriber: ActorRef,
                       bestPax: (Arrival) => Int,
                       pcpArrivalTimeForFlight: (Arrival) => MilliDate = (a: Arrival) => MilliDate(SDate(a.ActChoxDT, DateTimeZone.UTC).millisSinceEpoch),
                       airportConfig: AirportConfig)
  extends FlightsActor(Actor.noSender, dqApiSplitsActorRef, subscriber, csvSplitsProvider, bestPax, pcpArrivalTimeForFlight, airportConfig) {
  log.info(s"On construction we got $dqApiSplitsActorRef")
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

class SplitsTestActor extends Actor {
  override def receive: Receive = {
    case ReportVoyagePaxSplit(_, _, _, _) =>
      sender() ! VoyagePaxSplits("LHR", "BA", "1234", 25, MilliDate(60000L), List(SplitsPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, 25)))
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
      val arrivals = List(apiFlight(flightId = 1, iata = "SA0123", airportId = "STN", actPax = 100, schDt = "2050-10-02T20:00:00Z"))
      setFlightsAndStopActors(arrivals)

      val result = getFlightsAsSet()

      val expected = Set(apiFlight(flightId = 1, iata = "SA0123", airportId = "STN", actPax = 100, schDt = "2050-10-02T20:00:00Z"))
      result === expected
    }

    "Store two sets of flights and retrieve flights from both sets after a shutdown" in {
      val flightsSet = Set(
        Flights(List(apiFlight(flightId = 1, iata = "SA0123", airportId = "JFK", actPax = 100, schDt = "2050-10-02T20:00:00Z"))),
        Flights(List(apiFlight(flightId = 2, iata = "BA0001", airportId = "JFK", actPax = 150, schDt = "2050-10-02T21:55:00Z")))
      )
      setFlightsStopAndSleep(flightsSet)

      val result = getFlightsAsSet()

      val expected = Set(
        apiFlight(flightId = 1, iata = "SA0123", airportId = "JFK", actPax = 100, schDt = "2050-10-02T20:00:00Z"),
        apiFlight(flightId = 2, iata = "BA0001", airportId = "JFK", actPax = 150, schDt = "2050-10-02T21:55:00Z")
      )
      result === expected
    }

    "Remember the previous Pax for a flight and use them if the flight comes in with default pax" in
      new AkkaTestkitSpecs2SupportForPersistence("target/testFlightsActor") {
        implicit val timeout: Timeout = Timeout(5 seconds)
        val actor: ActorRef = flightsActor(system = system, airportCode = "LHR")

        actor ! Flights(List(apiFlight(flightId = 1, iata = "SA0124", airportId = "LHR", actPax = 300, schDt = "2050-08-01T20:00")))
        actor ! Flights(List(apiFlight(flightId = 2, iata = "SA0124", airportId = "LHR", actPax = 200, schDt = "2050-08-02T20:00")))

        val futureResult: Future[Any] = actor ? GetFlights
        val futureFlights: Future[List[Arrival]] = futureResult.collect {
          case Success(Flights(fs)) => fs
        }

        val result = Await.result(futureResult, 1 second)

        val expected = Flights(List(
          apiFlight(flightId = 1, iata = "SA0124", airportId = "LHR", actPax = 300, schDt = "2050-08-01T20:00"),
          apiFlight(flightId = 2, iata = "SA0124", airportId = "LHR", actPax = 200, schDt = "2050-08-02T20:00", lastKnownPax = Option(300))))

        result === expected
      }

    "Not remember the previous Pax for a flight if it was the default" in
      new AkkaTestkitSpecs2SupportForPersistence("target/testFlightsActor") {
        implicit val timeout: Timeout = Timeout(5 seconds)
        val actor: ActorRef = flightsActor(system = system, airportCode = "LHR")

        actor ! Flights(List(apiFlight(flightId = 1, iata = "SA0124", airportId = "LHR", actPax = 300, schDt = "2050-08-01T20:00")))
        actor ! Flights(List(apiFlight(flightId = 2, iata = "SA0124", airportId = "LHR", actPax = 200, schDt = "2050-08-02T20:00")))
        actor ! Flights(List(apiFlight(flightId = 2, iata = "SA0124", airportId = "LHR", actPax = 200, schDt = "2050-08-02T20:00")))

        val futureResult: Future[Any] = actor ? GetFlights
        val futureFlights: Future[List[Arrival]] = futureResult.collect {
          case Success(Flights(fs)) => fs
        }

        val result = Await.result(futureResult, 1 second)

        val expected = Flights(List(
          apiFlight(flightId = 1, iata = "SA0124", airportId = "LHR", actPax = 300, schDt = "2050-08-01T20:00"),
          apiFlight(flightId = 2, iata = "SA0124", airportId = "LHR", actPax = 200, schDt = "2050-08-02T20:00", lastKnownPax = Option(300))))

        result === expected
      }


    "Restore from a v1 snapshot using legacy ApiFlight" in
      new AkkaTestkitSpecs2SupportForPersistence("target/testFlightsActor") {
        createV1SnapshotAndShutdownActorSystem(Map(1 -> legacyApiFlight("SA0123", "STN", 1, "2050-10-02T20:00")))
        val result = startNewActorSystemAndRetrieveFlights()

        Flights(List(apiFlight(flightId = 1, iata = "SA0123", airportId = "STN", actPax = 1, schDt = "2050-10-02T20:00"))) === result
      }

    "Restore flights from a v2 snapshot using protobuf" in
      new AkkaTestkitSpecs2SupportForPersistence("target/testFlightsActor") {
        createV2SnapshotAndShutdownActorSystem(FlightStateSnapshotMessage(
          flightMessages = Seq(FlightMessage(iATA = Option("SA324"))),
          lastKnownPax = Seq(FlightLastKnownPaxMessage(Option("SA324"), Option(300)))
        ))
        val result = startNewActorSystemAndRetrieveFlights()

        result === Flights(List(Arrival("", "", "", "", "", "", "", "", 0, 0, 0, "", "", 0, "", "", "", "SA324", "", "", 0, None)))
      }

    "Restore last known pax from a v2 snapshot using protobuf" in
      new AkkaTestkitSpecs2SupportForPersistence("target/testFlightsActor") {
        createV2SnapshotAndShutdownActorSystem(FlightStateSnapshotMessage(
          flightMessages = Seq(FlightMessage(iATA = Option("SA324"))),
          lastKnownPax = Seq(FlightLastKnownPaxMessage(Option("SA324"), Option(300)))
        ))
        val result = startNewActorSystemAndRetrieveLastKnownPax()

        result === Map("SA324" -> 300)
      }
  }

  implicit val timeout: Timeout = Timeout(0.5 seconds)

  def setFlightsStopAndSleep(flightsSet: Set[Flights]) = {
    val flightsActorRef1 = flightsAndCrunchActors(system)

    flightsSet.foreach(flightsActorRef1 ! _)

    Await.ready(flightsActorRef1 ? GetFlights, 1 seconds)
    system.stop(flightsActorRef1)
    Thread.sleep(100L)
  }

  def getFlightsAsSet() = {
    val flightsActorRef2 = flightsAndCrunchActors(system)
    val futureResult = flightsActorRef2 ? GetFlights

    val result = Await.result(futureResult, 1 seconds).asInstanceOf[Flights] match {
      case Flights(flights) => flights.toSet
    }
    stopAndShutdown(flightsActorRef2)
    result
  }

  def setFlightsAndStopActors(arrivals: List[Arrival]) = {
    val flightsActorRef1 = flightsAndCrunchActors(system)

    flightsActorRef1 ! Flights(arrivals)

    syncStopAndSleep(flightsActorRef1)
  }

  def stopAndShutdown(flightsActorRef2: ActorRef) = {
    system.stop(flightsActorRef2)
    shutDownActorSystem
  }

  def syncStopAndSleep(flightsActorRef1: ActorRef) = {
    Await.ready(flightsActorRef1 ? GetFlights, 1 seconds)
    system.stop(flightsActorRef1)
    Thread.sleep(100L)
  }

  def flightsAndCrunchActors(system: ActorSystem) = {
    val flightsActorRef = flightsActor(system)
    (flightsActorRef)
  }

  def flightsActor(system: ActorSystem, airportCode: String = "EDI") = {
    system.actorOf(Props(
      classOf[FlightsActor],
      Actor.noSender,
      system.actorOf(Props(classOf[SplitsTestActor]), UUID.randomUUID().toString),
      TestProbe()(system).ref,
      testSplitsProvider,
      BestPax(airportCode),
      (a: Arrival) => MilliDate(SDate(a.SchDT, DateTimeZone.UTC).millisSinceEpoch),
      AirportConfigs.lhr
    ), "FlightsActor")
  }

  def flightsActorWithSnapshotIntervalOf1(system: ActorSystem, airportCode: String = "EDI") = {
    implicit val testSystem = system
    system.actorOf(Props(
      classOf[FlightsTestActor],
      Actor.noSender,
      testSplitsProvider,
      TestProbe()(system).ref,
      BestPax.bestPax,
      (a: Arrival) => MilliDate(SDate(a.ActChoxDT, DateTimeZone.UTC).millisSinceEpoch),
      AirportConfigs.lhr
    ), "FlightsActor")
  }

  def createV1SnapshotAndShutdownActorSystem(flights: Map[Int, ApiFlight]) = {
    val testKit1 = new AkkaTestkitSpecs2SupportForPersistence("target/testFlightsActor") {
      def saveSnapshot(flights: Map[Int, ApiFlight]) = {
        flightsActorWithSnapshotIntervalOf1(system) ! TriggerV1Snapshot(flights)
      }
    }
    testKit1.saveSnapshot(flights)
    testKit1.shutDownActorSystem
  }

  def createV2SnapshotAndShutdownActorSystem(flightStateSnapshotMessage: FlightStateSnapshotMessage) = {
    val testKit1 = new AkkaTestkitSpecs2SupportForPersistence("target/testFlightsActor") {
      def saveSnapshot(flightStateSnapshotMessage: FlightStateSnapshotMessage) = {
        flightsActorWithSnapshotIntervalOf1(system) ! flightStateSnapshotMessage
      }
    }
    testKit1.saveSnapshot(flightStateSnapshotMessage)
    testKit1.shutDownActorSystem
  }

  def startNewActorSystemAndRetrieveFlights(): Flights = {
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

  def startNewActorSystemAndRetrieveLastKnownPax(): Map[String, Int] = {
    val testKit2 = new AkkaTestkitSpecs2SupportForPersistence("target/testFlightsActor") {
      def getLastKnownPax = {
        val futureResult = flightsActorWithSnapshotIntervalOf1(system) ? GetLastKnownPax
        Await.result(futureResult, 2 seconds).asInstanceOf[Map[String, Int]]
      }
    }

    val result = testKit2.getLastKnownPax
    testKit2.shutDownActorSystem
    result
  }

  def legacyApiFlight(iata: String,
                      airportId: String = "EDI",
                      actPax: Int,
                      schDt: String,
                      terminal: String = "T1",
                      origin: String = "",
                      flightId: Int = 1,
                      lastKnownPax: Option[Int] = None
                     ): ApiFlight =
    ApiFlight(
      FlightID = flightId,
      SchDT = schDt,
      Terminal = terminal,
      Origin = origin,
      Operator = "",
      Status = "",
      EstDT = "",
      ActDT = "",
      EstChoxDT = "",
      ActChoxDT = "",
      Gate = "",
      Stand = "",
      MaxPax = 0,
      ActPax = actPax,
      TranPax = 0,
      RunwayID = "",
      BaggageReclaimId = "",
      AirportID = airportId,
      rawICAO = "",
      rawIATA = iata,
      PcpTime = if (schDt != "") SDate(schDt, DateTimeZone.UTC).millisSinceEpoch else 0
    )
}
