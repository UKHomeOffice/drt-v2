package services.crunch.deskrecs

import actors.acking.AckingReceiver.{Ack, StreamInitialized}
import akka.NotUsed
import akka.actor.{Actor, ActorRef, Props}
import akka.stream.scaladsl.Source
import akka.testkit.TestProbe
import controllers.ArrivalGenerator
import drt.shared.CrunchApi.{DeskRecMinutes, MillisSinceEpoch}
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.PaxTypes.EeaMachineReadable
import drt.shared.Queues.{EGate, EeaDesk, NonEeaDesk, Queue}
import drt.shared.SplitRatiosNs.SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages
import drt.shared.Terminals.{T1, Terminal}
import drt.shared._
import drt.shared.api.Arrival
import manifests.queues.SplitsCalculator
import manifests.queues.SplitsCalculator.SplitsForArrival
import passengersplits.parsing.VoyageManifestParser.{PassengerInfoJson, VoyageManifest, VoyageManifests}
import queueus.{AdjustmentsNoop, B5JPlusTypeAllocator, PaxTypeQueueAllocation, TerminalQueueAllocator}
import services.TryCrunch
import services.crunch.VoyageManifestGenerator.{euIdCard, manifestForArrival, visa}
import services.crunch.desklimits.{PortDeskLimits, TerminalDeskLimitsLike}
import services.crunch.deskrecs.Mocks.{MockSinkActor, mockFlightsProvider, mockHistoricManifestsProvider, mockLiveManifestsProvider}
import services.crunch.deskrecs.DynamicRunnableDeskRecs.{addManifests, createGraph}
import services.crunch.{CrunchTestLike, TestDefaults, VoyageManifestGenerator}
import services.graphstages.CrunchMocks

import scala.collection.immutable.Map
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}


object Mocks {

  class MockActor(somethingToReturn: List[Any]) extends Actor {
    override def receive: Receive = {
      case _ => sender() ! Source(somethingToReturn)
    }
  }

  class MockSinkActor(probe: ActorRef) extends Actor {
    override def receive: Receive = {
      case StreamInitialized =>
        sender() ! Ack
      case somethingToReturn =>
        probe ! somethingToReturn
        sender() ! Ack
    }
  }

  def mockFlightsProvider(arrivals: List[Arrival])
                         (implicit ec: ExecutionContext): MillisSinceEpoch => Future[Source[FlightsWithSplits, NotUsed]] =
    (_: MillisSinceEpoch) => Future(Source(List(FlightsWithSplits(arrivals.map(ApiFlightWithSplits(_, Set()))))))

  def mockLiveManifestsProvider(arrival: Arrival, maybePax: Option[List[PassengerInfoJson]])
                               (implicit ec: ExecutionContext): MillisSinceEpoch => Future[Source[VoyageManifests, NotUsed]] = {
    val manifests = maybePax match {
      case Some(pax) => VoyageManifests(Set(manifestForArrival(arrival, pax)))
      case None => VoyageManifests(Set())
    }

    (_: MillisSinceEpoch) => Future(Source(List(manifests)))
  }

  def mockHistoricManifestsProvider(maybePax: Option[List[PassengerInfoJson]])
                                   (implicit ec: ExecutionContext): Iterable[Arrival] => Future[Map[ArrivalKey, VoyageManifest]] =
    maybePax match {
      case Some(pax) =>
        (arrivals: Iterable[Arrival]) => Future(arrivals.map(arrival => (ArrivalKey(arrival), manifestForArrival(arrival, pax))).toMap)
      case None => _ => Future(Map())
    }
}

class RunnableDynamicDeskRecsSpec extends CrunchTestLike {
  val airportConfig: AirportConfig = TestDefaults.airportConfigWithEgates

  val maxDesksProvider: Map[Terminal, TerminalDeskLimitsLike] = PortDeskLimits.flexed(airportConfig)
  val mockCrunch: TryCrunch = CrunchMocks.mockCrunch
  val pcpPaxCalcFn: Arrival => Int = PcpPax.bestPaxEstimateWithApi

  val ptqa: PaxTypeQueueAllocation = PaxTypeQueueAllocation(
    B5JPlusTypeAllocator,
    TerminalQueueAllocator(airportConfig.terminalPaxTypeQueueAllocation))
  val splitsCalculator: SplitsCalculator = manifests.queues.SplitsCalculator(ptqa, airportConfig.terminalPaxSplits, AdjustmentsNoop())

  val desksAndWaitsProvider: DesksAndWaitsPortProvider = DesksAndWaitsPortProvider(airportConfig, mockCrunch, pcpPaxCalcFn)

  def setupGraphAndCheckQueuePax(arrival: Arrival,
                                 livePax: Option[List[PassengerInfoJson]],
                                 historicPax: Option[List[PassengerInfoJson]],
                                 expectedQueuePax: Map[(Terminal, Queue), Int]): Any = {
    val crunchPeriodStartMillis: SDateLike => SDateLike = day => day
    val probe = TestProbe()

    val daysSourceQueue = Source(List(arrival.Scheduled))
    val sink = system.actorOf(Props(new MockSinkActor(probe.ref)))

    val deskRecs = DynamicRunnableDeskRecs.daysToDeskRecs(
      mockFlightsProvider(List(arrival)),
      mockLiveManifestsProvider(arrival, livePax),
      mockHistoricManifestsProvider(historicPax),
      crunchPeriodStartMillis,
      splitsCalculator,
      desksAndWaitsProvider,
      maxDesksProvider) _

    createGraph(sink, daysSourceQueue, deskRecs).run()

    probe.fishForMessage(1 second) {
      case DeskRecMinutes(drms) =>
        val tqPax = drms
          .groupBy(drm => (drm.terminal, drm.queue))
          .map {
            case (tq, minutes) => (tq, minutes.map(_.paxLoad).sum)
          }
          .collect {
            case (tq, pax) if pax > 0 => (tq, pax)
          }

        tqPax === expectedQueuePax
    }
  }

  "Given a flight and a mock splits calculator" >> {
    val arrival = ArrivalGenerator.arrival(actPax = Option(100), origin = PortCode("JFK"))
    val flights = Seq(ApiFlightWithSplits(arrival, Set()))
    val splits = Splits(Set(ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 1.0, None, None)), ApiSplitsWithHistoricalEGateAndFTPercentages, None, Percentage)
    val mockSplits: SplitsForArrival = (_, _) => splits

    "When I have a manifest matching the arrival I should get the mock splits added to the arrival" >> {
      val manifest = VoyageManifestGenerator.manifestForArrival(arrival, List(euIdCard))
      val manifestsForArrival = manifestsByKey(manifest)
      val withLiveManifests = addManifests(flights, manifestsForArrival, mockSplits)

      withLiveManifests === Seq(ApiFlightWithSplits(arrival, Set(splits)))
    }

    "When I have no manifests matching the arrival I should get no splits added to the arrival" >> {
      val manifest = VoyageManifestGenerator.voyageManifest(portCode = PortCode("AAA"))
      val manifestsForDifferentArrival = manifestsByKey(manifest)
      val withLiveManifests = addManifests(flights, manifestsForDifferentArrival, mockSplits)

      withLiveManifests === Seq(ApiFlightWithSplits(arrival, Set()))
    }
  }

   def manifestsByKey(manifest: VoyageManifest): Map[ArrivalKey, VoyageManifest] =
    List(manifest)
      .map { vm => vm.maybeKey.map(k => (k, vm)) }
      .collect { case Some(k) => k }
      .toMap

  "Given an arrival with 100 pax " >> {

    val arrival = ArrivalGenerator.arrival("BA0001", actPax = Option(100), schDt = s"2021-06-01T12:00", origin = PortCode("JFK"))

    "When I provide no live and no historic manifests, terminal splits should be applied (50% desk, 50% egates)" >> {
      val expected: Map[(Terminal, Queue), Int] = Map((T1, EGate) -> 50, (T1, EeaDesk) -> 50)
      setupGraphAndCheckQueuePax(
        arrival = arrival,
        livePax = None,
        historicPax = None,
        expectedQueuePax = expected)

      success
    }

    "When I provide only historic splits with an id card pax, all pax should arrive at the eea desk " >> {
      val expected: Map[(Terminal, Queue), Int] = Map((T1, EeaDesk) -> 100)
      setupGraphAndCheckQueuePax(
        arrival = arrival,
        livePax = None,
        historicPax = Option(List(euIdCard)),
        expectedQueuePax = expected)

      success
    }

    "When I provide only live splits with an id card pax, all pax should arrive at the eea desk " >> {
      val expected: Map[(Terminal, Queue), Int] = Map((T1, EeaDesk) -> 100)
      setupGraphAndCheckQueuePax(
        arrival = arrival,
        livePax = Option(List(euIdCard)),
        historicPax = None,
        expectedQueuePax = expected)

      success
    }

    "When I provide live (id card) and historic (visa) splits, all pax should arrive at the eea desk as per the live splits" >> {
      val expected: Map[(Terminal, Queue), Int] = Map((T1, EeaDesk) -> 100)
      setupGraphAndCheckQueuePax(
        arrival = arrival,
        livePax = Option(List(euIdCard)),
        historicPax = Option(List(visa)),
        expectedQueuePax = expected)

      success
    }

    "When I provide live (visa) and historic (id card) splits, all pax should arrive at the non-eea desk as per the live splits" >> {
      val expected: Map[(Terminal, Queue), Int] = Map((T1, NonEeaDesk) -> 100)
      setupGraphAndCheckQueuePax(
        arrival = arrival,
        livePax = Option(List(visa)),
        historicPax = Option(List(euIdCard)),
        expectedQueuePax = expected)

      success
    }

  }
}
