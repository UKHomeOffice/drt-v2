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

class FlightsTestActor(crunchActorRef: ActorRef,
                       dqApiSplitsActorRef: AskableActorRef,
                       csvSplitsProvider: SplitProvider,
                       airportConfig: AirportConfig)
  extends FlightsActor(crunchActorRef,
    dqApiSplitsActorRef,
    csvSplitsProvider,
    airportConfig) {
  override val snapshotInterval = 1

  override def receive: Receive = {
    case TriggerV1Snapshot(newFlights) =>
      saveSnapshot(newFlights)

    case fssm: FlightStateSnapshotMessage =>
      saveSnapshot(fssm)
    case GetLastKnownPax =>
      sender() ! lastKnownPax
  }
}

case class TriggerV1Snapshot(newFlights: Map[Int, ApiFlight])
case object GetLastKnownPax

class FlightsActorSpec extends Specification {
  sequential

  val testSplitsProvider: SplitsProvider = SplitsProvider.emptyProvider

  private def flightsActor(system: ActorSystem, airportCode: String = "EDI") = {
    system.actorOf(Props(
      classOf[FlightsActor],
      crunchActor(system),
      Actor.noSender,
      testSplitsProvider,
      CrunchTests.airportConfig.copy(portCode = airportCode)), "FlightsActor")
  }


  private def flightsActorWithSnapshotIntervalOf1(system: ActorSystem, airportCode: String = "EDI") = {
    system.actorOf(Props(
      classOf[FlightsTestActor],
      crunchActor(system),
      Actor.noSender,
      testSplitsProvider,
      CrunchTests.airportConfig.copy(portCode = airportCode)), "FlightsActor")
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

      actor ! Flights(List(apiFlight(flightId = 1, flightCode = "SA0123", airportCode = "STN", totalPax = 1, scheduledDatetime = "2017-08-02T20:00")))

      val futureResult: Future[Any] = actor ? GetFlights
      val futureFlights: Future[List[Arrival]] = futureResult.collect {
        case Success(Flights(fs)) => fs
      }

      val result = Await.result(futureResult, 1 second)

      result === Flights(List(apiFlight("SA0123", "STN", 1, "2017-08-02T20:00")))
    }

    "Store a flight and retrieve it after a shutdown" in {
      setFlightsAndShutdownActorSystem(Flights(List(apiFlight("SA0123", "STN", 1, "2017-10-02T20:00"))))
      val result = startNewActorSystemAndRetrieveFlights

      Flights(List(apiFlight("SA0123", "STN", 1, "2017-10-02T20:00"))) === result
    }

    "Remember the previous Pax for a flight and use them if the flight comes in with default pax" in
      new AkkaTestkitSpecs2Support("target/testFlightsActor") {
        implicit val timeout: Timeout = Timeout(5 seconds)
        val actor: ActorRef = flightsActor(system, "LHR")

        actor ! Flights(List(apiFlight(flightId = 1, flightCode = "SA0124", airportCode = "LHR", totalPax = 300, scheduledDatetime = "2017-08-01T20:00")))
        actor ! Flights(List(apiFlight(flightId = 2, flightCode = "SA0124", airportCode = "LHR", totalPax = 200, scheduledDatetime = "2017-08-02T20:00")))

        val futureResult: Future[Any] = actor ? GetFlights
        val futureFlights: Future[List[Arrival]] = futureResult.collect {
          case Success(Flights(fs)) => fs
        }

        val result = Await.result(futureResult, 1 second)

        val expected = Flights(List(
          apiFlight(flightId = 1, flightCode = "SA0124", airportCode = "LHR", totalPax = 300, scheduledDatetime = "2017-08-01T20:00"),
          apiFlight(flightId = 2, flightCode = "SA0124", airportCode = "LHR", totalPax = 200, scheduledDatetime = "2017-08-02T20:00", lastKnownPax = Option(300))))

        result === expected
      }
    "not add last known pax for ports other than LHR" in
      new AkkaTestkitSpecs2Support("target/testFlightsActor") {
        implicit val timeout: Timeout = Timeout(5 seconds)
        val actor: ActorRef = flightsActor(system)

        actor ! Flights(List(apiFlight(flightId = 1, flightCode = "SA0124", airportCode = "STN", totalPax = 300, scheduledDatetime = "2017-08-01T20:00")))
        actor ! Flights(List(apiFlight(flightId = 2, flightCode = "SA0124", airportCode = "STN", totalPax = 200, scheduledDatetime = "2017-08-02T20:00")))

        val futureResult: Future[Any] = actor ? GetFlights
        val futureFlights: Future[List[Arrival]] = futureResult.collect {
          case Success(Flights(fs)) => fs
        }

        val result = Await.result(futureResult, 1 second)

        val expected = Flights(List(
          apiFlight(flightId = 1, flightCode = "SA0124", airportCode = "STN", totalPax = 300, scheduledDatetime = "2017-08-01T20:00"),
          apiFlight(flightId = 2, flightCode = "SA0124", airportCode = "STN", totalPax = 200, scheduledDatetime = "2017-08-02T20:00")))

        result === expected
      }

    "Restore from a v1 snapshot using legacy ApiFlight" in
      new AkkaTestkitSpecs2Support("target/testFlightsActor") {
        createV1SnapshotAndShutdownActorSystem(Map(1 -> legacyApiFlight("SA0123", "STN", 1, "2017-10-02T20:00")))
        val result = startNewActorSystemAndRetrieveFlights

        Flights(List(apiFlight("SA0123", "STN", 1, "2017-10-02T20:00"))) === result
      }
    "Restore from a v2 snapshot using protobuf" in
      new AkkaTestkitSpecs2Support("target/testFlightsActor") {
        createV2SnapshotAndShutdownActorSystem(FlightStateSnapshotMessage(
          Seq(FlightMessage(iATA=Option("SA324"))),
          Seq(FlightLastKnownPaxMessage(Option("SA324"), Option(300)))
        ))
        val result = startNewActorSystemAndRetrieveFlights

        result === Flights(List(Arrival("","","","","","","","",0,0,0,"","",0,"","","","SA324","","",0,None)))
      }
    "Restore from a v2 snapshot using protobuf" in
      new AkkaTestkitSpecs2Support("target/testFlightsActor") {
        createV2SnapshotAndShutdownActorSystem(FlightStateSnapshotMessage(
          Seq(FlightMessage(iATA=Option("SA324"))),
          Seq(FlightLastKnownPaxMessage(Option("SA324"), Option(300)))
        ))
        val result = startNewActorSystemAndRetrieveLastKnownPax

        result === Map("SA324" -> 300)
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
  def startNewActorSystemAndRetrieveLastKnownPax() = {
    val testKit2 = new AkkaTestkitSpecs2Support("target/testFlightsActor") {
      def getLastKnownPax = {
        val futureResult = flightsActorWithSnapshotIntervalOf1(system) ? GetLastKnownPax
        Await.result(futureResult, 2 seconds)
      }
    }

    val result = testKit2.getLastKnownPax
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

  def createV1SnapshotAndShutdownActorSystem(flights: Map[Int, ApiFlight]) = {
    val testKit1 = new AkkaTestkitSpecs2Support("target/testFlightsActor") {
      def saveSnapshot(flights: Map[Int, ApiFlight]) = {
        flightsActorWithSnapshotIntervalOf1(system) ! TriggerV1Snapshot(flights)
      }
    }
    testKit1.saveSnapshot(flights)
    testKit1.shutDownActorSystem
  }

  def createV2SnapshotAndShutdownActorSystem(flightStateSnapshotMessage: FlightStateSnapshotMessage) = {
    val testKit1 = new AkkaTestkitSpecs2Support("target/testFlightsActor") {
      def saveSnapshot(flightStateSnapshotMessage: FlightStateSnapshotMessage) = {
        flightsActorWithSnapshotIntervalOf1(system) ! flightStateSnapshotMessage
      }
    }
    testKit1.saveSnapshot(flightStateSnapshotMessage)
    testKit1.shutDownActorSystem
  }

  def legacyApiFlight(flightCode: String,
                      airportCode: String = "EDI",
                      totalPax: Int,
                      scheduledDatetime: String,
                      terminal: String = "A1",
                      origin: String = "",
                      flightId: Int = 1,
                      lastKnownPax: Option[Int] = None
                     ): ApiFlight =
    ApiFlight(
      FlightID = flightId,
      SchDT = scheduledDatetime,
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
      ActPax = totalPax,
      TranPax = 0,
      RunwayID = "",
      BaggageReclaimId = "",
      AirportID = airportCode,
      rawICAO = flightCode,
      rawIATA = flightCode,
      PcpTime = 0
    )

}
