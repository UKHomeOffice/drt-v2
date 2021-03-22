package scenarios

import actors.acking.AckingReceiver.Ack
import akka.NotUsed
import akka.actor.{Actor, Props}
import akka.stream.scaladsl.Source
import controllers.ArrivalGenerator
import dispatch.Future
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.PaxTypes.{B5JPlusNational, B5JPlusNationalBelowEGateAge, EeaBelowEGateAge, EeaMachineReadable}
import drt.shared.Queues.Queue
import drt.shared.Terminals.{T2, Terminal}
import drt.shared._
import drt.shared.api.Arrival
import drt.shared.dates.LocalDate
import manifests.passengers.ManifestLike
import manifests.queues.SplitsCalculator
import passengersplits.parsing.VoyageManifestParser.VoyageManifests
import queueus.{B5JPlusTypeAllocator, ChildEGateAdjustments, PaxTypeQueueAllocation, TerminalQueueAllocatorWithFastTrack}
import services.crunch.CrunchTestLike
import services.crunch.deskrecs.RunnableOptimisation.CrunchRequest
import services.imports.ArrivalCrunchSimulationActor
import services.scenarios.Scenarios

import scala.concurrent.Await
import scala.concurrent.duration._

class ArrivalsScenarioSpec extends CrunchTestLike {
  val crunchDate = LocalDate(2021, 3, 8)

  val terminalQueueAllocationMap: Map[Terminal, Map[PaxType, List[(Queue, Double)]]] = Map(T2 -> Map(
    EeaMachineReadable -> List(Queues.EGate -> 0.5, Queues.EeaDesk -> 0.5),
    B5JPlusNational -> List(Queues.EGate -> 0.5, Queues.EeaDesk -> 0.5),
    EeaBelowEGateAge -> List(Queues.EeaDesk -> 1.0),
    B5JPlusNationalBelowEGateAge -> List(Queues.EeaDesk -> 1.0)
  ))

  val testPaxTypeAllocator = PaxTypeQueueAllocation(
    B5JPlusTypeAllocator,
    TerminalQueueAllocatorWithFastTrack(terminalQueueAllocationMap))

  val splitsCalculator = SplitsCalculator(testPaxTypeAllocator, defaultAirportConfig.terminalPaxSplits, ChildEGateAdjustments(1.0))
  private val arrival: Arrival = ArrivalGenerator.arrival(actPax = Option(100), schDt = "2021-03-08T00:00")
  val arrivals = List(
    arrival
  )

  def flightsProvider(cr: CrunchRequest): Future[Source[List[Arrival], NotUsed]] = {

    Future(Source(List(arrivals)))
  }

  def manifestsProvider(cr: CrunchRequest): Future[Source[VoyageManifests, NotUsed]] = Future(Source(List()))

  def historicManifestsProvider(arrivals: Iterable[Arrival]): Future[Source[ManifestLike, NotUsed]] = Future(Source(List()))

  "Given some arrivals and simlution config I should get back DeskRecMinutes containing all the passengers from the arrivals" >> {

    val simulationParams = SimulationParams(Terminals.T1, crunchDate, defaultAirportConfig)

    val portStateActor = system.actorOf(Props(new ArrivalCrunchSimulationActor(
      simulationParams.applyPassengerWeighting(FlightsWithSplits(arrivals.map(a => ApiFlightWithSplits(a, Set())))
        ))))

    val futureResult: Future[CrunchApi.DeskRecMinutes] = Scenarios.simulationResult(
      simulationParams,
      simulationParams.applyToAirportConfig(defaultAirportConfig),
      splitsCalculator,
      flightsProvider,
      manifestsProvider,
      historicManifestsProvider,
      system.actorOf(Props(new AkkingActor())),
      portStateActor
    )

    val result = Await.result(futureResult, 1 second)

    result.minutes.map(_.paxLoad).sum === 100
  }

}

class AkkingActor extends Actor {
  override def receive: Receive = {
    case _ => sender() ! Ack
  }
}

