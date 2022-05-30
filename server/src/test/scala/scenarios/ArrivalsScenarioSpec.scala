package scenarios

import actors.acking.AckingReceiver.Ack
import akka.NotUsed
import akka.actor.{Actor, Props}
import akka.stream.scaladsl.Source
import controllers.ArrivalGenerator
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared._
import manifests.UniqueArrivalKey
import manifests.passengers.{ManifestPaxCount, ManifestLike}
import manifests.queues.SplitsCalculator
import passengersplits.parsing.VoyageManifestParser.{VoyageManifest, VoyageManifests}
import queueus.{B5JPlusTypeAllocator, ChildEGateAdjustments, PaxTypeQueueAllocation, TerminalQueueAllocatorWithFastTrack}
import services.SDate
import services.crunch.CrunchTestLike
import services.crunch.deskrecs.RunnableOptimisation.CrunchRequest
import services.imports.ArrivalCrunchSimulationActor
import services.scenarios.Scenarios
import uk.gov.homeoffice.drt.arrivals.EventTypes.DC
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, Arrival, TotalPaxSource, VoyageNumber}
import uk.gov.homeoffice.drt.egates.{EgateBank, EgateBanksUpdate, EgateBanksUpdates, PortEgateBanksUpdates}
import uk.gov.homeoffice.drt.ports.PaxTypes._
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSource
import uk.gov.homeoffice.drt.ports.Terminals.{T2, Terminal}
import uk.gov.homeoffice.drt.ports._
import uk.gov.homeoffice.drt.redlist.RedListUpdates
import uk.gov.homeoffice.drt.time.LocalDate

import scala.collection.SortedSet
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

class ArrivalsScenarioSpec extends CrunchTestLike {
  val crunchDate: LocalDate = LocalDate(2021, 3, 8)

  val terminalQueueAllocationMap: Map[Terminal, Map[PaxType, List[(Queue, Double)]]] = Map(T2 -> Map(
    EeaMachineReadable -> List(Queues.EGate -> 0.5, Queues.EeaDesk -> 0.5),
    B5JPlusNational -> List(Queues.EGate -> 0.5, Queues.EeaDesk -> 0.5),
    EeaBelowEGateAge -> List(Queues.EeaDesk -> 1.0),
    B5JPlusNationalBelowEGateAge -> List(Queues.EeaDesk -> 1.0)
  ))

  val testPaxTypeAllocator: PaxTypeQueueAllocation = PaxTypeQueueAllocation(
    B5JPlusTypeAllocator,
    TerminalQueueAllocatorWithFastTrack(terminalQueueAllocationMap))

  val splitsCalculator: SplitsCalculator = SplitsCalculator(testPaxTypeAllocator, defaultAirportConfig.terminalPaxSplits, ChildEGateAdjustments(1.0))
  private val arrival: Arrival = ArrivalGenerator.arrival(actPax = Option(100), schDt = "2021-03-08T00:00",totalPax = SortedSet(TotalPaxSource(100,LiveFeedSource,None)))
  val arrivals = List(
    arrival
  )

  def flightsProvider(cr: CrunchRequest): Future[Source[List[ApiFlightWithSplits], NotUsed]] =
    Future.successful(Source(List(arrivals.map(a => ApiFlightWithSplits(a, Set())))))

  def manifestsProvider(cr: CrunchRequest): Future[Source[VoyageManifests, NotUsed]] = Future.successful(Source(List()))

  def historicManifestsProvider(arrivals: Iterable[Arrival]): Source[ManifestLike, NotUsed] = Source(List())

  def historicManifestsPaxProvider(arrival:Arrival): Future[Option[ManifestPaxCount]] = Future.successful(
    Option(ManifestPaxCount(SplitSource("ApiSplitsWithHistoricalEGateAndFTPercentages"),
      UniqueArrivalKey(PortCode("LHR"),departurePort = arrival.Origin,voyageNumber = arrival.VoyageNumber,SDate(arrival.Scheduled)),10)))

  "Given some arrivals and simlution config I should get back DeskRecMinutes containing all the passengers from the arrivals" >> {

    val simulationParams = defaultSimulationParams(Terminals.T1, crunchDate, defaultAirportConfig)

    val portStateActor = system.actorOf(Props(new ArrivalCrunchSimulationActor(
      simulationParams.applyPassengerWeighting(FlightsWithSplits(arrivals.map(a => ApiFlightWithSplits(a, Set())))
        ))))

    val futureResult: Future[CrunchApi.DeskRecMinutes] = Scenarios.simulationResult(
      simulationParams = simulationParams,
      simulationAirportConfig = simulationParams.applyToAirportConfig(defaultAirportConfig),
      splitsCalculator = splitsCalculator,
      flightsProvider = flightsProvider,
      liveManifestsProvider = manifestsProvider,
      historicManifestsProvider = historicManifestsProvider,
      historicManifestsPaxProvider =  historicManifestsPaxProvider,
      flightsActor = system.actorOf(Props(new AkkingActor())),
      portStateActor = portStateActor,
      redListUpdatesProvider = () => Future.successful(RedListUpdates.empty),
      egateBanksProvider = () => Future.successful(PortEgateBanksUpdates(defaultAirportConfig.eGateBankSizes.map {
        case (terminal, banks) => (terminal, EgateBanksUpdates(List(EgateBanksUpdate(0L, EgateBank.fromAirportConfig(banks)))))
      })),
    )

    val result = Await.result(futureResult, 1.second)

    result.minutes.map(_.paxLoad).sum === 100
  }

  def defaultSimulationParams(terminal: Terminal, date: LocalDate, airportConfig: AirportConfig): SimulationParams = {
    SimulationParams(
      terminal,
      date,
      1.0,
      airportConfig.terminalProcessingTimes(terminal)
        .filterNot {
          case (paxTypeAndQueue: PaxTypeAndQueue, _) =>
            paxTypeAndQueue.queueType == Queues.Transfer

        }
        .mapValues(m => (m * 60).toInt),
      airportConfig.minMaxDesksByTerminalQueue24Hrs(terminal).map {
        case (q, (min, _)) => q -> min.max
      },
      airportConfig.minMaxDesksByTerminalQueue24Hrs(terminal).map {
        case (q, (_, max)) => q -> max.max
      },
      eGateBanksSizes = airportConfig.eGateBankSizes.getOrElse(terminal, Iterable()).toIndexedSeq,
      slaByQueue = airportConfig.slaByQueue,
      crunchOffsetMinutes = 0,
      eGateOpenHours = SimulationParams.fullDay
    )
  }

}

class AkkingActor extends Actor {
  override def receive: Receive = {
    case _ => sender() ! Ack
  }
}

