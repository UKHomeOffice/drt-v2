package services.crunch.deskrecs

import actors.PartitionedPortStateActor.GetStateForTerminalDateRange
import actors.persistent.SortedActorRefSource
import org.apache.pekko.Done
import org.apache.pekko.actor.{Actor, Props}
import org.apache.pekko.testkit.TestProbe
import drt.shared.CrunchApi.{MinutesContainer, PassengersMinute}
import services.TryCrunchWholePax
import services.crunch.desklimits.flexed.FlexedTerminalDeskLimitsFromAvailableStaff
import services.crunch.desklimits.{PortDeskLimits, TerminalDeskLimitsLike}
import services.crunch.deskrecs.OptimiserMocks.MockSinkActor
import services.crunch.{CrunchTestLike, MockEgatesProvider, TestDefaults}
import services.graphstages.{CrunchMocks, FlightFilter}
import uk.gov.homeoffice.drt.actor.commands.TerminalUpdateRequest
import uk.gov.homeoffice.drt.egates.EgateBanksUpdates
import uk.gov.homeoffice.drt.model.{CrunchMinute, TQM}
import uk.gov.homeoffice.drt.ports.AirportConfig
import uk.gov.homeoffice.drt.ports.Queues.{EGate, EeaDesk, NonEeaDesk, Queue}
import uk.gov.homeoffice.drt.ports.Terminals.{T1, Terminal}
import uk.gov.homeoffice.drt.time.{LocalDate, SDate}

import scala.collection.SortedSet
import scala.concurrent.Future
import scala.concurrent.duration._


class MockProviderActor(minutes: MinutesContainer[PassengersMinute, TQM]) extends Actor {
  override def receive: Receive = {
    case _: GetStateForTerminalDateRange => sender() ! minutes
  }
}

class DynamicRunnableDeploymentsSpec extends CrunchTestLike {
  implicit val airportConfig: AirportConfig = TestDefaults.airportConfigWithEgates

  val egatesProvider: Terminal => Future[EgateBanksUpdates] = MockEgatesProvider.terminalProvider(airportConfig)

  val maxDesksProvider: Map[Terminal, TerminalDeskLimitsLike] = PortDeskLimits.flexed(airportConfig, egatesProvider)
  val mockCrunch: TryCrunchWholePax = CrunchMocks.mockCrunchWholePax

  val staffToDeskLimits: (Terminal, List[Int]) => FlexedTerminalDeskLimitsFromAvailableStaff = PortDeskLimits.flexedByAvailableStaff(airportConfig, egatesProvider)
  val desksAndWaitsProvider: PortDesksAndWaitsProvider = PortDesksAndWaitsProvider(airportConfig, mockCrunch, FlightFilter.forPortConfig(airportConfig), paxFeedSourceOrder, (_: LocalDate, q: Queue) => Future.successful(airportConfig.slaByQueue(q)))

  def setupGraphAndCheckQueuePax(minutes: MinutesContainer[PassengersMinute, TQM],
                                 expectedQueuePax: PartialFunction[Any, Boolean]): Any = {
    val probe = TestProbe()

    val request = TerminalUpdateRequest(T1, SDate("2021-05-01").toLocalDate)
    val sink = system.actorOf(Props(new MockSinkActor(probe.ref)))
    val mockProvider = system.actorOf(Props(new MockProviderActor(minutes)))

    val deskRecs = DynamicRunnableDeployments.crunchRequestsToDeployments(
      loadsProvider = OptimisationProviders.passengersProvider(mockProvider),
      staffMinutesProvider = OptimisationProviders.staffMinutesProvider(mockProvider),
      staffToDeskLimits = staffToDeskLimits,
      loadsToQueueMinutes = desksAndWaitsProvider.loadsToSimulations,
      setUpdatedAtForDay = (_, _, _) => Future.successful(Done),
    )
    val crunchGraphSource = new SortedActorRefSource(TestProbe().ref, SortedSet(), "deployments")

    val (queue, _) = QueuedRequestProcessing.createGraph(crunchGraphSource, sink, deskRecs, "deployments").run()
    queue ! request

    probe.fishForMessage(5.second)(expectedQueuePax)
  }

  "Given a mock workload provider returning no workloads" >> {
    "When I ask for deployments I should see 1440 minutes for each queue" >> {
      val expected: PartialFunction[Any, Boolean] = {
        case minutes: MinutesContainer[CrunchMinute, TQM] =>
          val minuteCountByQueue: Map[Queue, Int] = minutes.minutes.groupBy(_.toMinute.queue).view.mapValues(_.size).toMap
          minuteCountByQueue === Map(EeaDesk -> 1440, NonEeaDesk -> 1440, EGate -> 1440)
      }
      val noLoads = MinutesContainer.empty[PassengersMinute, TQM]

      setupGraphAndCheckQueuePax(minutes = noLoads, expectedQueuePax = expected)

      success
    }
  }
}
