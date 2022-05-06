package services.crunch.deskrecs

import actors.acking.AckingReceiver.{Ack, StreamInitialized}
import actors.persistent.SortedActorRefSource
import akka.NotUsed
import akka.actor.{Actor, ActorRef, Props}
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.testkit.TestProbe
import controllers.ArrivalGenerator
import drt.shared.CrunchApi.DeskRecMinutes
import drt.shared._
import manifests.passengers.{BestAvailableManifest, HistoricManifestPax, ManifestPaxLike}
import manifests.queues.SplitsCalculator
import manifests.queues.SplitsCalculator.SplitsForArrival
import manifests.{ManifestLookupLike, UniqueArrivalKey}
import passengersplits.parsing.VoyageManifestParser.{PassengerInfoJson, VoyageManifest, VoyageManifests}
import queueus._
import services.crunch.VoyageManifestGenerator.{euIdCard, manifestForArrival, visa, xOfPaxType}
import services.crunch.desklimits.{PortDeskLimits, TerminalDeskLimitsLike}
import services.crunch.deskrecs.DynamicRunnableDeskRecs.{HistoricManifestsPaxProvider, HistoricManifestsProvider, addManifests, updatePaxNos}
import services.crunch.deskrecs.OptimiserMocks.{MockSinkActor, mockFlightsProvider, mockHistoricManifestsPaxProvider, mockHistoricManifestsPaxProviderNoop, mockHistoricManifestsProvider, mockLiveManifestsProvider}
import services.crunch.deskrecs.RunnableOptimisation.CrunchRequest
import services.crunch.{CrunchTestLike, MockEgatesProvider, TestDefaults, VoyageManifestGenerator}
import services.graphstages.{CrunchMocks, FlightFilter}
import services.{SDate, TryCrunch}
import uk.gov.homeoffice.drt.arrivals.SplitStyle.Percentage
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.ports.PaxTypes.EeaMachineReadable
import uk.gov.homeoffice.drt.ports.Queues.{EGate, EeaDesk, NonEeaDesk, Queue}
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSource
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources.{ApiSplitsWithHistoricalEGateAndFTPercentages, Historical, TerminalAverage}
import uk.gov.homeoffice.drt.ports.Terminals.{T1, Terminal}
import uk.gov.homeoffice.drt.ports.{AirportConfig, ApiFeedSource, ApiPaxTypeAndQueueCount, LiveFeedSource, PortCode}
import uk.gov.homeoffice.drt.redlist.RedListUpdates
import uk.gov.homeoffice.drt.time.SDateLike

import scala.collection.SortedSet
import scala.collection.immutable.Map
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
        sender() ! Ack
      case somethingToReturn =>
        probe ! somethingToReturn
        sender() ! Ack
    }
  }

  def mockFlightsProvider(arrivals: List[Arrival])
                         (implicit ec: ExecutionContext): CrunchRequest => Future[Source[List[ApiFlightWithSplits], NotUsed]] =
    _ => Future.successful(Source(List(arrivals.map(a => ApiFlightWithSplits(a, Set())))))


  def mockLiveManifestsProviderNoop(implicit ec: ExecutionContext): CrunchRequest => Future[Source[VoyageManifests, NotUsed]] = {
    _ => Future.successful(Source(List()))
  }

  def mockHistoricManifestsProviderNoop(implicit ec: ExecutionContext): HistoricManifestsProvider = {
    _: Iterable[Arrival] => Source(List())
  }

  def mockHistoricManifestsPaxProviderNoop(implicit ec: ExecutionContext): HistoricManifestsPaxProvider = {
    _: Arrival => Future.successful(None)
  }

  def mockLiveManifestsProvider(arrival: Arrival, maybePax: Option[List[PassengerInfoJson]])
                               (implicit ec: ExecutionContext): CrunchRequest => Future[Source[VoyageManifests, NotUsed]] = {
    val manifests = maybePax match {
      case Some(pax) => VoyageManifests(Set(manifestForArrival(arrival, pax)))
      case None => VoyageManifests(Set())
    }

    _ => Future.successful(Source(List(manifests)))
  }

  def mockHistoricManifestsProvider(arrivalsWithMaybePax: Map[Arrival, Option[List[PassengerInfoJson]]])
                                   (implicit ec: ExecutionContext, mat: Materializer): HistoricManifestsProvider = {
    OptimisationProviders.historicManifestsProvider(
      PortCode("STN"),
      MockManifestLookupService(arrivalsWithMaybePax.map { case (arrival, maybePax) =>
        val key = UniqueArrivalKey(PortCode("STN"), arrival.Origin, arrival.VoyageNumber, SDate(arrival.Scheduled))
        val maybeManifest = maybePax.map(pax => BestAvailableManifest.historic(VoyageManifestGenerator.manifestForArrival(arrival, pax)))
        (key, maybeManifest)
      }, (arrivalsWithMaybePax.map { case (arrival, maybePax) =>
        val key = UniqueArrivalKey(PortCode("STN"), arrival.Origin, arrival.VoyageNumber, SDate(arrival.Scheduled))
        val maybeManifest = maybePax.map(pax => HistoricManifestPax.historic(VoyageManifestGenerator.manifestForArrival(arrival, pax)))
        (key, maybeManifest)
      }),PortCode("STN"))
    )
  }

  def mockHistoricManifestsPaxProvider(arrivalsWithMaybePax: Map[Arrival, Option[List[PassengerInfoJson]]])
                                   (implicit ec: ExecutionContext, mat: Materializer): HistoricManifestsPaxProvider = {
    OptimisationProviders.historicManifestsPaxProvider(
      PortCode("STN"),
      MockManifestLookupService(arrivalsWithMaybePax.map { case (arrival, maybePax) =>
        val key = UniqueArrivalKey(PortCode("STN"), arrival.Origin, arrival.VoyageNumber, SDate(arrival.Scheduled))
        val maybeManifest = maybePax.map(pax => BestAvailableManifest.historic(VoyageManifestGenerator.manifestForArrival(arrival, pax)))
        (key, maybeManifest)
      }, arrivalsWithMaybePax.map { case (arrival, maybePax) =>
        val key = UniqueArrivalKey(PortCode("STN"), arrival.Origin, arrival.VoyageNumber, SDate(arrival.Scheduled))
        val maybeManifest = maybePax.map(pax => HistoricManifestPax.historic(VoyageManifestGenerator.manifestForArrival(arrival, pax)))
        (key, maybeManifest)
      },PortCode("STN"))
    )
  }
}

case class MockManifestLookupService(bestAvailableManifests: Map[UniqueArrivalKey, Option[BestAvailableManifest]], historicManifestsPax: Map[UniqueArrivalKey, Option[HistoricManifestPax]] ,destinationPort: PortCode)
                                    (implicit mat: Materializer) extends ManifestLookupLike {
  override def maybeBestAvailableManifest(arrivalPort: PortCode,
                                          departurePort: PortCode,
                                          voyageNumber: VoyageNumber,
                                          scheduled: SDateLike): Future[(UniqueArrivalKey, Option[BestAvailableManifest])] = {
    val key = UniqueArrivalKey(arrivalPort, departurePort, voyageNumber, scheduled)
    Future.successful((key, bestAvailableManifests.get(key).flatten))
  }

  override def historicManifestPax(arrivalPort: PortCode, departurePort: PortCode, voyageNumber: VoyageNumber, scheduled: SDateLike): Future[(UniqueArrivalKey, Option[HistoricManifestPax])] = {
    val key = UniqueArrivalKey(arrivalPort, departurePort, voyageNumber, scheduled)
    Future.successful((key, historicManifestsPax.get(key).flatten))
  }

}

class RunnableDynamicDeskRecsSpec extends CrunchTestLike {
  val airportConfig: AirportConfig = TestDefaults.airportConfigWithEgates

  val maxDesksProvider: Map[Terminal, TerminalDeskLimitsLike] = PortDeskLimits.flexed(airportConfig, MockEgatesProvider.terminalProvider(airportConfig))
  val mockCrunch: TryCrunch = CrunchMocks.mockCrunch

  val ptqa: PaxTypeQueueAllocation = PaxTypeQueueAllocation(
    B5JPlusTypeAllocator,
    TerminalQueueAllocator(airportConfig.terminalPaxTypeQueueAllocation))
  val splitsCalculator: SplitsCalculator = manifests.queues.SplitsCalculator(ptqa, airportConfig.terminalPaxSplits, AdjustmentsNoop)

  val desksAndWaitsProvider: PortDesksAndWaitsProvider = PortDesksAndWaitsProvider(airportConfig, mockCrunch, FlightFilter.forPortConfig(airportConfig), MockEgatesProvider.portProvider(airportConfig))
  val mockSplitsSink: ActorRef = system.actorOf(Props(new MockSplitsSinkActor))

  def setupGraphAndCheckQueuePax(arrival: Arrival,
                                 livePax: Option[List[PassengerInfoJson]],
                                 historicPax: Option[List[PassengerInfoJson]],
                                 expectedQueuePax: Map[(Terminal, Queue), Int]): Any = {
    val probe = TestProbe()

    val request = CrunchRequest(SDate(arrival.Scheduled).toLocalDate, 0, 1440)
    val sink = system.actorOf(Props(new MockSinkActor(probe.ref)))

    val deskRecs: Flow[CrunchRequest, PortStateQueueMinutes, NotUsed] = DynamicRunnableDeskRecs.crunchRequestsToQueueMinutes(
      arrivalsProvider = mockFlightsProvider(List(arrival)),
      liveManifestsProvider = mockLiveManifestsProvider(arrival, livePax),
      historicManifestsProvider = mockHistoricManifestsProvider(Map(arrival -> historicPax)),
      historicManifestsPaxProvider = mockHistoricManifestsPaxProvider(Map(arrival -> historicPax)),
      splitsCalculator = splitsCalculator,
      splitsSink = mockSplitsSink,
      portDesksAndWaitsProvider = desksAndWaitsProvider,
      maxDesksProviders = maxDesksProvider,
      redListUpdatesProvider = () => Future.successful(RedListUpdates.empty),
      DynamicQueueStatusProvider(airportConfig, MockEgatesProvider.portProvider(airportConfig)),
    )

    val crunchGraphSource = new SortedActorRefSource(TestProbe().ref, airportConfig.crunchOffsetMinutes, airportConfig.minutesToCrunch, SortedSet())

    val (queue, _) = RunnableOptimisation.createGraph(crunchGraphSource, sink, deskRecs).run()
    queue ! request

    probe.fishForMessage(1.second) {
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
    val arrival = ArrivalGenerator.arrival(actPax = Option(100), origin = PortCode("JFK"), feedSources = Set(LiveFeedSource))
    val flights = Seq(ApiFlightWithSplits(arrival, Set()))
    val splits = Splits(Set(ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 1.0, None, None)), ApiSplitsWithHistoricalEGateAndFTPercentages, None, Percentage)
    val mockSplits: SplitsForArrival = (_, _) => splits

    "addManifests" >> {
      "When I have a manifest matching the arrival I should get the mock splits added to the arrival" >> {
        val manifest = VoyageManifestGenerator.manifestForArrival(arrival, List(euIdCard))
        val manifestsForArrival = manifestsByKey(manifest)
        val withLiveManifests = addManifests(flights, manifestsForArrival, mockSplits)

        withLiveManifests === Seq(ApiFlightWithSplits(arrival.copy(ApiPax = Option(1), FeedSources = arrival.FeedSources + ApiFeedSource), Set(splits)))
      }

      "When I have no manifests matching the arrival I should get no splits added to the arrival" >> {
        val manifest = VoyageManifestGenerator.voyageManifest(portCode = PortCode("AAA"))
        val manifestsForDifferentArrival = manifestsByKey(manifest)
        val withLiveManifests = addManifests(flights, manifestsForDifferentArrival, mockSplits)

        withLiveManifests === Seq(ApiFlightWithSplits(arrival, Set()))
      }
    }

    "updateArrival" >> {
      "When I have a manifest matching the arrival I should get the mock splits added to the arrival" >> {
        val manifest = VoyageManifestGenerator.manifestForArrival(arrival, List(euIdCard))
        val manifestsForArrival = manifestsByKey(manifest)
        val withLiveManifests = addManifests(flights, manifestsForArrival, mockSplits)
        val updatePax = updatePaxNos(mockSplitsSink)

        withLiveManifests === Seq(ApiFlightWithSplits(arrival.copy(ApiPax = Option(1), FeedSources = arrival.FeedSources + ApiFeedSource), Set(splits)))
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
  }

  private def checkSplitsSource(arrival: Arrival,
                                maybeLiveManifestPax: Option[List[PassengerInfoJson]],
                                maybeHistoricArrivalManifestPax: Map[Arrival, Option[List[PassengerInfoJson]]],
                                expectedSplitsSources: Set[SplitSource]) = {
    val flow = DynamicRunnableDeskRecs.addSplits(
      mockLiveManifestsProvider(arrival, maybeLiveManifestPax),
      mockHistoricManifestsProvider(maybeHistoricArrivalManifestPax),
      splitsCalculator)

    val value1 = Source(List((CrunchRequest(SDate(arrival.Scheduled).toLocalDate, 0, 1440), List(ApiFlightWithSplits(arrival, Set())))))
    val result = Await.result(value1.via(flow).runWith(Sink.seq), 1.second)

    result.head._2.exists(_.bestSplits.nonEmpty) && result.head._2.exists(_.splits.map(_.source) === expectedSplitsSources)
  }

  def manifestsByKey(manifest: VoyageManifest): Map[ArrivalKey, VoyageManifest] =
    List(manifest)
      .map { vm => vm.maybeKey.map(k => (k, vm)) }
      .collect { case Some(k) => k }
      .toMap

  "Given an arrival with 100 pax " >> {

    val arrival = ArrivalGenerator.arrival("BA0001", actPax = Option(100), schDt = s"2021-06-01T12:00", origin = PortCode("JFK"), feedSources = Set(LiveFeedSource))

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
}
