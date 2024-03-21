package services.crunch.deskrecs

import actors.persistent.SortedActorRefSource
import akka.NotUsed
import akka.actor.{Actor, ActorRef, Props}
import akka.pattern.StatusReply
import akka.stream.scaladsl.{Sink, Source}
import akka.testkit.TestProbe
import controllers.ArrivalGenerator
import drt.shared.CrunchApi.{MillisSinceEpoch, MinutesContainer, PassengersMinute}
import drt.shared._
import manifests.passengers.{BestAvailableManifest, ManifestLike, ManifestPaxCount}
import manifests.queues.SplitsCalculator
import manifests.queues.SplitsCalculator.SplitsForArrival
import manifests.{ManifestLookupLike, UniqueArrivalKey}
import passengersplits.parsing.VoyageManifestParser.{PassengerInfoJson, VoyageManifest, VoyageManifests}
import queueus._
import services.TryCrunchWholePax
import services.crunch.VoyageManifestGenerator.{euIdCard, manifestForArrival, visa, xOfPaxType}
import services.crunch.deskrecs.DynamicRunnablePassengerLoads.addManifests
import services.crunch.deskrecs.OptimiserMocks._
import services.crunch.{CrunchTestLike, MockEgatesProvider, TestDefaults, VoyageManifestGenerator}
import services.graphstages.{CrunchMocks, FlightFilter}
import uk.gov.homeoffice.drt.actor.acking.AckingReceiver.StreamInitialized
import uk.gov.homeoffice.drt.actor.commands.{CrunchRequest, ProcessingRequest}
import uk.gov.homeoffice.drt.arrivals.SplitStyle.Percentage
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.ports.PaxTypes.EeaMachineReadable
import uk.gov.homeoffice.drt.ports.Queues.{EGate, EeaDesk, NonEeaDesk, Queue}
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources.{ApiSplitsWithHistoricalEGateAndFTPercentages, Historical, TerminalAverage}
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.{SplitSource, SplitSources}
import uk.gov.homeoffice.drt.ports.Terminals.{T1, Terminal}
import uk.gov.homeoffice.drt.ports._
import uk.gov.homeoffice.drt.redlist.RedListUpdates
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, SDateLike}

import scala.collection.{SortedSet, immutable}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}


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

  def mockFlightsProvider(arrivals: List[Arrival]): ProcessingRequest => Future[Source[List[ApiFlightWithSplits], NotUsed]] =
    _ => Future.successful(Source(List(arrivals.map(a => ApiFlightWithSplits(a, Set())))))

  def mockLiveManifestsProviderNoop: ProcessingRequest => Future[Source[VoyageManifests, NotUsed]] = {
    _ => Future.successful(Source(List()))
  }

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

  override def historicManifestPax(arrivalPort: PortCode,
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

  def setupGraphAndCheckQueuePax(arrival: Arrival,
                                 livePax: Option[List[PassengerInfoJson]],
                                 historicPax: Option[List[PassengerInfoJson]],
                                 expectedQueuePax: Map[(Terminal, Queue), Int]): Any = {
    val probe = TestProbe()

    val request = CrunchRequest(SDate(arrival.Scheduled).toLocalDate, 0, 1440)
    val sink = system.actorOf(Props(new MockSinkActor(probe.ref)))

    val queueMinutesProducer = DynamicRunnablePassengerLoads.crunchRequestsToQueueMinutes(
      arrivalsProvider = mockFlightsProvider(List(arrival)),
      liveManifestsProvider = mockLiveManifestsProvider(arrival, livePax),
      historicManifestsProvider = mockHistoricManifestsProvider(Map(arrival -> historicPax)),
      historicManifestsPaxProvider = mockHistoricManifestsPaxProvider(Map(arrival -> historicPax)),
      splitsCalculator = splitsCalculator,
      splitsSink = mockSplitsSink,
      portDesksAndWaitsProvider = desksAndWaitsProvider,
      redListUpdatesProvider = () => Future.successful(RedListUpdates.empty),
      dynamicQueueStatusProvider = DynamicQueueStatusProvider(airportConfig, MockEgatesProvider.portProvider(airportConfig)),
      queuesByTerminal = airportConfig.queuesByTerminal,
      updateLiveView = _ => Future.successful(StatusReply.Ack),
      paxFeedSourceOrder = paxFeedSourceOrder,
    )
    val crunchRequest: MillisSinceEpoch => CrunchRequest =
      (millis: MillisSinceEpoch) => CrunchRequest(millis, airportConfig.crunchOffsetMinutes, airportConfig.minutesToCrunch)
    val crunchGraphSource = new SortedActorRefSource(TestProbe().ref, crunchRequest, SortedSet(), "passenger-loads")

    val (queue, _) = QueuedRequestProcessing.createGraph(crunchGraphSource, sink, queueMinutesProducer, "passenger-loads").run()
    queue ! request

    probe.fishForMessage(5.second) {
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

  "Given a flight and a mock splits calculator" >> {
    val arrival = ArrivalGenerator.arrival(origin = PortCode("JFK"), totalPax = Option(100)).toArrival(LiveFeedSource)
    val flights = Seq(ApiFlightWithSplits(arrival, Set()))
    val splits = Splits(Set(ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 1.0, None, None)), ApiSplitsWithHistoricalEGateAndFTPercentages, None, Percentage)
    val mockSplits: SplitsForArrival = (_, _) => splits

    "addManifests" >> {
      "When I have a manifest matching the arrival I should get the mock splits added to the arrival" >> {
        val manifest = VoyageManifestGenerator.manifestForArrival(arrival, List(euIdCard))
        val manifestsForArrival = manifestsByKey(manifest)
        val withLiveManifests = addManifests(flights, manifestsForArrival, mockSplits)

        withLiveManifests === Seq(ApiFlightWithSplits(arrival.copy(FeedSources = arrival.FeedSources + ApiFeedSource,
          PassengerSources = arrival.PassengerSources.updated(ApiFeedSource, Passengers(Option(1), Option(0)))
        ), Set(splits)))
      }

      "When I have no manifests matching the arrival I should get no splits added to the arrival" >> {
        val manifest = VoyageManifestGenerator.voyageManifest(portCode = PortCode("AAA"))
        val manifestsForDifferentArrival = manifestsByKey(manifest)
        val withLiveManifests = addManifests(flights, manifestsForDifferentArrival, mockSplits)

        withLiveManifests === Seq(ApiFlightWithSplits(arrival, Set()))
      }
    }

    "addSplits" >> {
      "When I have live manifests matching the arrival where the live manifest is within the trust threshold I should get the live splits" >> {
        checkSplitsSource(arrival, Option(xOfPaxType(100, visa)), Map(), Set(ApiSplitsWithHistoricalEGateAndFTPercentages))
      }

      "When I have live and historic manifests matching the arrival where the live manifest isn't within the trust threshold I should get the fallback historic splits" >> {
        checkSplitsSource(arrival, Option(xOfPaxType(10, visa)), Map(arrival -> Option(xOfPaxType(10, visa))), Set(ApiSplitsWithHistoricalEGateAndFTPercentages, Historical))
      }

      "When I have live manifests matching the arrival where the live manifest isn't within the trust threshold, and no historical manifest, I should get the fallback terminal average splits" >> {
        checkSplitsSource(arrival, Option(xOfPaxType(10, visa)), Map(), Set(TerminalAverage))
      }

      "When I have live manifests matching the arrival where the live manifest isn't within the trust threshold, and no historical manifest, I should get the fallback terminal average splits" >> {
        checkSplitsSource(arrival, None, Map(), Set(TerminalAverage))
      }
    }

    "add historic API pax" >> {
      "When I have no Feed I should get some pax from historic API" >> {
        val arrival = ArrivalGenerator.arrival(origin = PortCode("JFK")).toArrival(LiveFeedSource)
        checkPaxSource(arrival, Map(arrival -> Option(xOfPaxType(10, visa))), Map(HistoricApiFeedSource -> Passengers(Option(10), None)))
      }
    }
  }

  private def checkSplitsSource(arrival: Arrival,
                                maybeLiveManifestPax: Option[List[PassengerInfoJson]],
                                maybeHistoricArrivalManifestPax: Map[Arrival, Option[List[PassengerInfoJson]]],
                                expectedSplitsSources: Set[SplitSource]) = {
    val flow = DynamicRunnablePassengerLoads.addSplits(
      mockLiveManifestsProvider(arrival, maybeLiveManifestPax),
      mockHistoricManifestsProvider(maybeHistoricArrivalManifestPax),
      splitsCalculator)

    val value1 = Source(List((CrunchRequest(SDate(arrival.Scheduled).toLocalDate, 0, 1440), List(ApiFlightWithSplits(arrival, Set())))))
    val result = Await.result(value1.via(flow).runWith(Sink.seq), 1.second)

    result.head._2.exists(_.bestSplits.nonEmpty) && result.head._2.exists(_.splits.map(_.source) === expectedSplitsSources)
  }

  private def checkPaxSource(arrival: Arrival,
                             maybeHistoricArrivalManifestPax: Map[Arrival, Option[List[PassengerInfoJson]]],
                             expectedPaxSources: Map[FeedSource, Passengers]) = {
    val flow = DynamicRunnablePassengerLoads.addPax(mockHistoricManifestsPaxProvider(maybeHistoricArrivalManifestPax))

    val crunchRequestSource = Source(List((CrunchRequest(SDate(arrival.Scheduled).toLocalDate, 0, 1440), List(ApiFlightWithSplits(arrival, Set())))))
    val result: immutable.Seq[(ProcessingRequest, List[ApiFlightWithSplits])] = Await.result(crunchRequestSource.via(flow).runWith(Sink.seq), 1.second)
    result.head._2.exists(_.apiFlight.PassengerSources === expectedPaxSources)
  }

  def manifestsByKey(manifest: VoyageManifest): Map[ArrivalKey, VoyageManifest] =
    List(manifest)
      .map { vm => vm.maybeKey.map(k => (k, vm)) }
      .collect { case Some(k) => k }
      .toMap

  "Given an arrival with 100 pax " >> {

    val arrival = ArrivalGenerator.arrival("BA0001", schDt = s"2021-06-01T12:00", origin = PortCode("JFK"), totalPax = Option(100)).toArrival(LiveFeedSource)

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
        livePax = Option(xOfPaxType(100, euIdCard)),
        historicPax = None,
        expectedQueuePax = expected)

      success
    }

    "When I provide live (id card) and historic (visa) splits, all pax should arrive at the eea desk as per the live splits" >> {
      val expected: Map[(Terminal, Queue), Int] = Map((T1, EeaDesk) -> 100)
      setupGraphAndCheckQueuePax(
        arrival = arrival,
        livePax = Option(xOfPaxType(100, euIdCard)),
        historicPax = Option(List(visa)),
        expectedQueuePax = expected)

      success
    }

    "When I provide live (visa) and historic (id card) splits, all pax should arrive at the non-eea desk as per the live splits" >> {
      val expected: Map[(Terminal, Queue), Int] = Map((T1, NonEeaDesk) -> 100)
      setupGraphAndCheckQueuePax(
        arrival = arrival,
        livePax = Option(xOfPaxType(100, visa)),
        historicPax = Option(List(euIdCard)),
        expectedQueuePax = expected)

      success
    }
  }

  "validApiPercentage" >> {
    val validApi = ApiFlightWithSplits(
      ArrivalGenerator.arrival(totalPax = Option(100)).toArrival(LiveFeedSource),
        Set(Splits(
          Set(ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, EeaDesk, 100, None, None)),
          ApiSplitsWithHistoricalEGateAndFTPercentages, Option(EventTypes.DC))))
    val invalidApi = ApiFlightWithSplits(
      ArrivalGenerator.arrival(totalPax = Option(100)).toArrival(LiveFeedSource),
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
