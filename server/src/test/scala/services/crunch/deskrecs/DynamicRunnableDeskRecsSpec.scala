package services.crunch.deskrecs

import actors.persistent.SortedActorRefSource
import akka.{Done, NotUsed}
import akka.actor.{Actor, ActorRef, Props}
import akka.pattern.StatusReply
import akka.stream.scaladsl.Source
import akka.testkit.TestProbe
import controllers.ArrivalGenerator
import drt.shared.CrunchApi.{MillisSinceEpoch, MinutesContainer, PassengersMinute}
import drt.shared._
import manifests.passengers.{BestAvailableManifest, ManifestLike, ManifestPaxCount}
import manifests.queues.SplitsCalculator
import manifests.{ManifestLookupLike, UniqueArrivalKey}
import passengersplits.parsing.VoyageManifestParser.{PassengerInfoJson, VoyageManifest, VoyageManifests}
import queueus._
import services.TryCrunchWholePax
import services.crunch.VoyageManifestGenerator.manifestForArrival
import services.crunch.deskrecs.OptimiserMocks._
import services.crunch.{CrunchTestLike, MockEgatesProvider, TestDefaults, VoyageManifestGenerator}
import services.graphstages.{CrunchMocks, FlightFilter}
import uk.gov.homeoffice.drt.actor.acking.AckingReceiver.StreamInitialized
import uk.gov.homeoffice.drt.actor.commands.{CrunchRequest, ProcessingRequest}
import uk.gov.homeoffice.drt.arrivals.SplitStyle.Percentage
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.ports.Queues.{EGate, EeaDesk, Queue}
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources.{ApiSplitsWithHistoricalEGateAndFTPercentages, Historical, TerminalAverage}
import uk.gov.homeoffice.drt.ports.Terminals.{T1, Terminal}
import uk.gov.homeoffice.drt.ports._
import uk.gov.homeoffice.drt.redlist.RedListUpdates
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, SDateLike}

import scala.collection.SortedSet
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}


object OptimiserMocks {
  class MockActor(somethingToReturn: List[Any]) extends Actor {
    override def receive: Receive = {
      case _ => sender() ! Source(somethingToReturn)
    }
  }

  class MockSinkActor(probe: ActorRef) extends Actor {
    override def receive: Receive = {
      case StreamInitialized =>
        sender() ! StatusReply.Ack
      case somethingToReturn =>
        probe ! somethingToReturn
        sender() ! StatusReply.Ack
    }
  }

  def getMockManifestLookupService(arrivalsWithMaybePax: Map[Arrival, Option[List[PassengerInfoJson]]],
                                   portCode: PortCode): MockManifestLookupService =
    MockManifestLookupService(arrivalsWithMaybePax.map { case (arrival, maybePax) =>
      val key = UniqueArrivalKey(portCode, arrival.Origin, arrival.VoyageNumber, SDate(arrival.Scheduled))
      val maybeManifest = maybePax.map(pax => BestAvailableManifest.historic(VoyageManifestGenerator.manifestForArrival(arrival, pax)))
      (key, maybeManifest)
    }, arrivalsWithMaybePax.map { case (arrival, maybePax) =>
      val key = UniqueArrivalKey(portCode, arrival.Origin, arrival.VoyageNumber, SDate(arrival.Scheduled))
      val maybeManifest = maybePax.map(pax => ManifestPaxCount(VoyageManifestGenerator.manifestForArrival(arrival, pax), SplitSources.Historical))
      (key, maybeManifest)
    }, portCode)

  def mockFlightsProvider(flights: List[ApiFlightWithSplits]): ProcessingRequest => Future[Source[List[ApiFlightWithSplits], NotUsed]] =
    _ => Future.successful(Source(List(flights)))

  def mockHistoricManifestsProviderNoop: Iterable[Arrival] => Source[ManifestLike, NotUsed] = {
    _: Iterable[Arrival] => Source(List())
  }

  def mockHistoricManifestsPaxProviderNoop: Arrival => Future[Option[ManifestPaxCount]] = {
    _: Arrival => Future.successful(None)
  }

  def mockLiveManifestsProvider(arrival: Arrival, maybePax: Option[List[PassengerInfoJson]]): ProcessingRequest => Future[Source[VoyageManifests, NotUsed]] = {
    val manifests = maybePax match {
      case Some(pax) => VoyageManifests(Set(manifestForArrival(arrival, pax)))
      case None => VoyageManifests(Set())
    }

    _ => Future.successful(Source(List(manifests)))
  }

  def mockHistoricManifestsProvider(arrivalsWithMaybePax: Map[Arrival, Option[List[PassengerInfoJson]]])
                                   (implicit ec: ExecutionContext): Iterable[Arrival] => Source[ManifestLike, NotUsed] = {
    val portCode = PortCode("STN")

    val mockCacheLookup: Arrival => Future[Option[ManifestLike]] = _ => Future.successful(None)
    val mockCacheStore: (Arrival, ManifestLike) => Future[Any] = (_: Arrival, _: ManifestLike) => Future.successful(StatusReply.Ack)

    OptimisationProviders.historicManifestsProvider(
      portCode,
      getMockManifestLookupService(arrivalsWithMaybePax, portCode),
      mockCacheLookup,
      mockCacheStore,
    )
  }

  def mockHistoricManifestsPaxProvider(arrivalsWithMaybePax: Map[Arrival, Option[List[PassengerInfoJson]]])
                                      (implicit ec: ExecutionContext): Arrival => Future[Option[ManifestPaxCount]] = {
    val portCode = PortCode("STN")
    OptimisationProviders.historicManifestsPaxProvider(
      portCode,
      getMockManifestLookupService(arrivalsWithMaybePax, portCode)
    )
  }
}

case class MockManifestLookupService(bestAvailableManifests: Map[UniqueArrivalKey, Option[BestAvailableManifest]],
                                     historicManifestsPax: Map[UniqueArrivalKey, Option[ManifestPaxCount]],
                                     destinationPort: PortCode)
  extends ManifestLookupLike {
  override def maybeBestAvailableManifest(arrivalPort: PortCode,
                                          departurePort: PortCode,
                                          voyageNumber: VoyageNumber,
                                          scheduled: SDateLike): Future[(UniqueArrivalKey, Option[BestAvailableManifest])] = {
    val key = UniqueArrivalKey(arrivalPort, departurePort, voyageNumber, scheduled)
    Future.successful((key, bestAvailableManifests.get(key).flatten))
  }

  override def maybeHistoricManifestPax(arrivalPort: PortCode,
                                        departurePort: PortCode,
                                        voyageNumber: VoyageNumber,
                                        scheduled: SDateLike,
                                  ): Future[(UniqueArrivalKey, Option[ManifestPaxCount])] = {
    val key = UniqueArrivalKey(arrivalPort, departurePort, voyageNumber, scheduled)
    Future.successful((key, historicManifestsPax.get(key).flatten))
  }
}

class RunnableDynamicDeskRecsSpec extends CrunchTestLike {
  val airportConfig: AirportConfig = TestDefaults.airportConfigWithEgates

  val mockCrunch: TryCrunchWholePax = CrunchMocks.mockCrunchWholePax

  val ptqa: PaxTypeQueueAllocation = PaxTypeQueueAllocation(
    B5JPlusTypeAllocator,
    TerminalQueueAllocator(airportConfig.terminalPaxTypeQueueAllocation))
  val splitsCalculator: SplitsCalculator = manifests.queues.SplitsCalculator(ptqa, airportConfig.terminalPaxSplits, AdjustmentsNoop)

  val desksAndWaitsProvider: PortDesksAndWaitsProvider = PortDesksAndWaitsProvider(airportConfig, mockCrunch, FlightFilter.forPortConfig(airportConfig), paxFeedSourceOrder, (_: LocalDate, q: Queue) => Future.successful(airportConfig.slaByQueue(q)))
  val mockSplitsSink: ActorRef = system.actorOf(Props(new MockSplitsSinkActor))

  def setupGraphAndCheckQueuePax(flight: ApiFlightWithSplits,
                                 expectedQueuePax: Map[(Terminal, Queue), Int]): Any = {
    val probe = TestProbe()

    val request = CrunchRequest(SDate(flight.apiFlight.Scheduled).toLocalDate, 0, 1440)
    val sink = system.actorOf(Props(new MockSinkActor(probe.ref)))

    val queueMinutesProducer = DynamicRunnablePassengerLoads.crunchRequestsToQueueMinutes(
      arrivalsProvider = mockFlightsProvider(List(flight)),
      portDesksAndWaitsProvider = desksAndWaitsProvider,
      redListUpdatesProvider = () => Future.successful(RedListUpdates.empty),
      dynamicQueueStatusProvider = DynamicQueueStatusProvider(airportConfig, MockEgatesProvider.portProvider(airportConfig)),
      queuesByTerminal = airportConfig.queuesByTerminal,
      updateLiveView = _ => Future.successful(StatusReply.Ack),
      paxFeedSourceOrder = paxFeedSourceOrder,
      terminalSplits = _ => Option(Splits(Set(
        ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, EeaDesk, 50, None, None),
        ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, EGate, 50, None, None),
      ), TerminalAverage, None, Percentage)),
      updateCapacity = _ => Future.successful(Done),
      setUpdatedAtForDay = (_, _, _) => Future.successful(Done),
    )
    val crunchRequest: MillisSinceEpoch => CrunchRequest =
      (millis: MillisSinceEpoch) => CrunchRequest(millis, airportConfig.crunchOffsetMinutes, airportConfig.minutesToCrunch)
    val crunchGraphSource = new SortedActorRefSource(TestProbe().ref, crunchRequest, SortedSet(), "passenger-loads")

    val (queue, _) = QueuedRequestProcessing.createGraph(crunchGraphSource, sink, queueMinutesProducer, "passenger-loads").run()
    queue ! request

    probe.fishForMessage(2.second) {
      case container: MinutesContainer[PassengersMinute, TQM] =>
        val tqPax = container.minutes
          .groupBy(pm => (pm.toMinute.terminal, pm.toMinute.queue))
          .map {
            case (tq, mins) =>
              (tq, mins.map(_.toMinute.passengers.size).sum)
          }
          .collect {
            case (tq, pax) if pax > 0 => (tq, pax)
          }
        tqPax == expectedQueuePax
    }
  }

  def manifestsByKey(manifest: VoyageManifest): Map[ArrivalKey, VoyageManifest] =
    List(manifest)
      .map { vm => vm.maybeKey.map(k => (k, vm)) }
      .collect { case Some(k) => k }
      .toMap

  "Given an arrival with 100 pax " >> {

    val arrival = ArrivalGenerator.live("BA0001", schDt = s"2021-06-01T12:00", origin = PortCode("JFK"), totalPax = Option(100))
      .toArrival(LiveFeedSource)
      .copy(PcpTime = Option(SDate("2021-06-01T11:30").millisSinceEpoch))

    "When I provide no live and no historic manifests, terminal splits should be applied (50% desk, 50% egates)" >> {
      val expected: Map[(Terminal, Queue), Int] = Map((T1, EGate) -> 50, (T1, EeaDesk) -> 50)
      setupGraphAndCheckQueuePax(
        flight = ApiFlightWithSplits(arrival, Set()),
        expectedQueuePax = expected)

      success
    }

    "When I provide only historic splits with 100% to eea desk, all pax should arrive at the eea desk " >> {
      val expected: Map[(Terminal, Queue), Int] = Map((T1, EeaDesk) -> 100)
      val historicSplits = Splits(Set(ApiPaxTypeAndQueueCount(PaxTypes.GBRNational, EeaDesk, 1, None, None)), Historical, None, Percentage)
      setupGraphAndCheckQueuePax(
        flight = ApiFlightWithSplits(arrival, Set(historicSplits)),
        expectedQueuePax = expected
      )

      success
    }
  }

  "validApiPercentage" >> {
    val validApi = ApiFlightWithSplits(
      ArrivalGenerator.live(totalPax = Option(100)).toArrival(LiveFeedSource),
        Set(Splits(
          Set(ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, EeaDesk, 100, None, None)),
          ApiSplitsWithHistoricalEGateAndFTPercentages, Option(EventTypes.DC))))
    val invalidApi = ApiFlightWithSplits(
      ArrivalGenerator.live(totalPax = Option(100)).toArrival(LiveFeedSource),
        Set(Splits(
          Set(ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, EeaDesk, 50, None, None)),
          ApiSplitsWithHistoricalEGateAndFTPercentages, Option(EventTypes.DC))))
    "Given no flights, then validApiPercentage should give 100%" >> {
      DynamicRunnablePassengerLoads.validApiPercentage(Seq()) === 100d
    }
    "Given 1 flight with live api splits, when it is valid, then validApiPercentage should give 100%" >> {
      DynamicRunnablePassengerLoads.validApiPercentage(Seq(validApi)) === 100d
    }
    "Given 4 flights with live api splits, when 3 are categorised as valid, then validApiPercentage should give 75%" >> {
      DynamicRunnablePassengerLoads.validApiPercentage(Seq(validApi, validApi, validApi, invalidApi)) === 75d
    }
  }
}
