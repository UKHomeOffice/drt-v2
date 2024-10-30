package services.crunch.deskrecs

import actors.persistent.SortedActorRefSource
import akka.actor.{Actor, ActorRef, Props}
import akka.pattern.StatusReply
import akka.stream.scaladsl.Source
import akka.testkit.TestProbe
import akka.{Done, NotUsed}
import controllers.ArrivalGenerator
import drt.shared.CrunchApi._
import drt.shared._
import manifests.passengers.{BestAvailableManifest, ManifestPaxCount}
import manifests.queues.SplitsCalculator
import manifests.{ManifestLookupLike, UniqueArrivalKey}
import queueus._
import services.TryCrunchWholePax
import services.crunch.desklimits.PortDeskLimits
import services.crunch.deskrecs.OptimiserMocks._
import services.crunch.{CrunchTestLike, MockEgatesProvider, TestDefaults}
import services.graphstages.{CrunchMocks, FlightFilter}
import uk.gov.homeoffice.drt.actor.acking.AckingReceiver.StreamInitialized
import uk.gov.homeoffice.drt.actor.commands.TerminalUpdateRequest
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.ports.Queues.{EeaDesk, Queue}
import uk.gov.homeoffice.drt.ports.Terminals.{T1, Terminal}
import uk.gov.homeoffice.drt.ports._
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, SDateLike}

import scala.collection.SortedSet
import scala.concurrent.Future
import scala.concurrent.duration._


object OptimiserMocks {
  class MockSinkActor(probe: ActorRef) extends Actor {
    override def receive: Receive = {
      case StreamInitialized =>
        sender() ! StatusReply.Ack
      case somethingToReturn =>
        probe ! somethingToReturn
        sender() ! StatusReply.Ack
    }
  }

  def mockFlightsProvider(flights: List[ApiFlightWithSplits]): TerminalUpdateRequest => Future[Source[List[ApiFlightWithSplits], NotUsed]] =
    _ => Future.successful(Source(List(flights)))
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
  implicit val airportConfig: AirportConfig = TestDefaults.airportConfigWithEgates

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

    val request = TerminalUpdateRequest(T1, SDate(flight.apiFlight.Scheduled).toLocalDate)
    val sink = system.actorOf(Props(new MockSinkActor(probe.ref)))

    val minute = SDate(flight.apiFlight.Scheduled).millisSinceEpoch
    val maxDeskProviders = PortDeskLimits.flexed(airportConfig, MockEgatesProvider.terminalProvider(airportConfig))
    val queueMinutesProducer = DynamicRunnableDeskRecs.crunchRequestsToDeskRecs(
      loadsProvider = (_, _, _) => {
        Future.successful(Map(TQM(T1, EeaDesk, minute) -> PassengersMinute(T1, EeaDesk, minute, Seq(50, 50, 50), None)))
      },
      maxDesksProviders = maxDeskProviders,
      loadsToQueueMinutes = (_, mins, _, _, _) => Future.successful(DeskRecMinutes(mins.values.map(m => DeskRecMinute(m.terminal, m.queue, m.minute, m.passengers.size, m.passengers.sum, 0, 0, None)).toList)),
      setUpdatedAtForDay = (_, _, _) => Future.successful(Done),
    )

    val crunchGraphSource = new SortedActorRefSource(TestProbe().ref, SortedSet.empty[TerminalUpdateRequest], "passenger-loads")

    val (queue, _) = QueuedRequestProcessing.createGraph(crunchGraphSource, sink, queueMinutesProducer, "passenger-loads").run()
    queue ! request

    probe.fishForMessage(2.second) {
      case container: MinutesContainer[CrunchMinute, TQM] =>
        val tqPax = container.minutes
          .groupBy(pm => (pm.toMinute.terminal, pm.toMinute.queue))
          .map {
            case (tq, mins) =>
              (tq, mins.map(_.toMinute.paxLoad).sum)
          }
          .collect {
            case (tq, pax) if pax > 0 => (tq, pax)
          }
        tqPax == expectedQueuePax
    }
  }

  "Given an arrival with 100 pax " >> {
    val arrival = ArrivalGenerator.live("BA0001", schDt = s"2021-06-01T12:00", origin = PortCode("JFK"), totalPax = Option(100))
      .toArrival(LiveFeedSource)
      .copy(PcpTime = Option(SDate("2021-06-01T11:30").millisSinceEpoch))

    "When I provide no live and no historic manifests, terminal splits should be applied (50% desk, 50% egates)" >> {
      val expected: Map[(Terminal, Queue), Int] = Map((T1, EeaDesk) -> 3)
      setupGraphAndCheckQueuePax(
        flight = ApiFlightWithSplits(arrival, Set()),
        expectedQueuePax = expected)

      success
    }

  }
}
