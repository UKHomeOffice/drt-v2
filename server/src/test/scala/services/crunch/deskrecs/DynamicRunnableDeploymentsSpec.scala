package services.crunch.deskrecs

import actors.PartitionedPortStateActor.GetStateForDateRange
import actors.persistent.SortedActorRefSource
import akka.actor.{Actor, Props}
import akka.testkit.TestProbe
import drt.shared.CrunchApi.{CrunchMinute, MinutesContainer, PassengersMinute}
import drt.shared._
import services.crunch.desklimits.PortDeskLimits.StaffToDeskLimits
import services.crunch.desklimits.{PortDeskLimits, TerminalDeskLimitsLike}
import services.crunch.deskrecs.OptimiserMocks.MockSinkActor
import services.crunch.deskrecs.RunnableOptimisation.CrunchRequest
import services.crunch.{CrunchTestLike, MockEgatesProvider, TestDefaults}
import services.graphstages.{CrunchMocks, FlightFilter}
import services.{SDate, TryCrunchWholePax}
import uk.gov.homeoffice.drt.egates.EgateBanksUpdates
import uk.gov.homeoffice.drt.ports.AirportConfig
import uk.gov.homeoffice.drt.ports.Queues.{EGate, EeaDesk, NonEeaDesk, Queue}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal

import scala.collection.SortedSet
import scala.collection.immutable.Map
import scala.concurrent.Future
import scala.concurrent.duration._


class MockProviderActor(minutes: MinutesContainer[PassengersMinute, TQM]) extends Actor {
  override def receive: Receive = {
    case _: GetStateForDateRange => sender() ! minutes
  }
}

class DynamicRunnableDeploymentsSpec extends CrunchTestLike {
  val airportConfig: AirportConfig = TestDefaults.airportConfigWithEgates

  val egatesProvider: Terminal => Future[EgateBanksUpdates] = MockEgatesProvider.terminalProvider(airportConfig)

  val maxDesksProvider: Map[Terminal, TerminalDeskLimitsLike] = PortDeskLimits.flexed(airportConfig, egatesProvider)
  val mockCrunch: TryCrunchWholePax = CrunchMocks.mockCrunchWholePax

  val staffToDeskLimits: StaffToDeskLimits = PortDeskLimits.flexedByAvailableStaff(airportConfig, egatesProvider)
  val desksAndWaitsProvider: PortDesksAndWaitsProvider = PortDesksAndWaitsProvider(airportConfig, mockCrunch, FlightFilter.forPortConfig(airportConfig), MockEgatesProvider.portProvider(airportConfig))

  def setupGraphAndCheckQueuePax(minutes: MinutesContainer[PassengersMinute, TQM],
                                 expectedQueuePax: PartialFunction[Any, Boolean]): Any = {
    val probe = TestProbe()

    val request = CrunchRequest(SDate("2021-05-01").toLocalDate, 0, 1440)
    val sink = system.actorOf(Props(new MockSinkActor(probe.ref)))
    val mockProvider = system.actorOf(Props(new MockProviderActor(minutes)))

    val deskRecs = DynamicRunnableDeployments.crunchRequestsToDeployments(
      OptimisationProviders.passengersProvider(mockProvider),
      OptimisationProviders.staffMinutesProvider(mockProvider, airportConfig.terminals),
      staffToDeskLimits,
      desksAndWaitsProvider.loadsToSimulations)

    val crunchGraphSource = new SortedActorRefSource(TestProbe().ref, airportConfig.crunchOffsetMinutes, airportConfig.minutesToCrunch, SortedSet())

    val (queue, _) = RunnableOptimisation.createGraph(crunchGraphSource, sink, deskRecs).run()
    queue ! request

    probe.fishForMessage(5.second)(expectedQueuePax)
  }

  "Given a mock workload provider returning no workloads" >> {
    "When I ask for deployments I should see 1440 minutes for each queue" >> {
      val expected: PartialFunction[Any, Boolean] = {
        case minutes: MinutesContainer[CrunchMinute, TQM] =>
          val minuteCountByQueue: Map[Queue, Int] = minutes.minutes.groupBy(_.toMinute.queue).mapValues(_.size)
          minuteCountByQueue === Map(EeaDesk -> 1440, NonEeaDesk -> 1440, EGate -> 1440)
      }
      val noLoads = MinutesContainer.empty[PassengersMinute, TQM]

      setupGraphAndCheckQueuePax(minutes = noLoads, expectedQueuePax = expected)

      success
    }
  }
}
