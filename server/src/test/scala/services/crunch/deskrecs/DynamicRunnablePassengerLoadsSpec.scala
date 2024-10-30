package services.crunch.deskrecs

import actors.persistent.SortedActorRefSource
import akka.Done
import akka.actor.{ActorRef, Props}
import akka.pattern.StatusReply
import akka.testkit.TestProbe
import controllers.ArrivalGenerator
import drt.shared.CrunchApi.{MinutesContainer, PassengersMinute}
import drt.shared._
import manifests.queues.SplitsCalculator
import passengersplits.parsing.VoyageManifestParser.VoyageManifest
import queueus._
import services.TryCrunchWholePax
import services.crunch.deskrecs.OptimiserMocks._
import services.crunch.{CrunchTestLike, MockEgatesProvider, TestDefaults}
import services.graphstages.{CrunchMocks, FlightFilter}
import uk.gov.homeoffice.drt.actor.commands.TerminalUpdateRequest
import uk.gov.homeoffice.drt.arrivals.SplitStyle.Percentage
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.ports.Queues.{EGate, EeaDesk, Queue}
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources.{Historical, TerminalAverage}
import uk.gov.homeoffice.drt.ports.Terminals.{T1, Terminal}
import uk.gov.homeoffice.drt.ports._
import uk.gov.homeoffice.drt.redlist.RedListUpdates
import uk.gov.homeoffice.drt.time.{LocalDate, SDate}

import scala.collection.SortedSet
import scala.concurrent.Future
import scala.concurrent.duration._


class DynamicRunnablePassengerLoadsSpec extends CrunchTestLike {
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

    val request = TerminalUpdateRequest(T1, SDate(flight.apiFlight.Scheduled).toLocalDate)
    val sink = system.actorOf(Props(new MockSinkActor(probe.ref)))

    val queueMinutesProducer = DynamicRunnablePassengerLoads.crunchRequestsToQueueMinutes(
      arrivalsProvider = mockFlightsProvider(List(flight)),
      portDesksAndWaitsProvider = desksAndWaitsProvider,
      redListUpdatesProvider = () => Future.successful(RedListUpdates.empty),
      dynamicQueueStatusProvider = DynamicQueueStatusProvider(airportConfig, MockEgatesProvider.portProvider(airportConfig)),
      queuesByTerminal = airportConfig.queuesByTerminal,
      updateLiveView = _ => {
        probe.ref ! "Live view updated"
        Future.successful(StatusReply.Ack)
      },
      paxFeedSourceOrder = paxFeedSourceOrder,
      terminalSplits = _ => Option(Splits(Set(
        ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, EeaDesk, 50, None, None),
        ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, EGate, 50, None, None),
      ), TerminalAverage, None, Percentage)),
      updateCapacity = _ => {
        probe.ref ! "Capacity updated"
        Future.successful(Done)
      },
      setUpdatedAtForDay = (_, _, _) => Future.successful(Done),
    )
    val crunchGraphSource = new SortedActorRefSource(TestProbe().ref, SortedSet.empty[TerminalUpdateRequest], "passenger-loads")

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

    probe.expectMsg("Capacity updated")
    probe.expectMsg("Live view updated")
  }

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
  }
}
