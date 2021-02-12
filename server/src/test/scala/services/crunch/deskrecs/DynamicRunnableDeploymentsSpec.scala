package services.crunch.deskrecs

import actors.PartitionedPortStateActor.GetStateForDateRange
import akka.actor.{Actor, Props}
import akka.testkit.TestProbe
import drt.shared.CrunchApi.{CrunchMinute, MinutesContainer}
import drt.shared.Queues.{EGate, EeaDesk, NonEeaDesk, Queue}
import drt.shared.Terminals.Terminal
import drt.shared._
import drt.shared.api.Arrival
import services.crunch.desklimits.PortDeskLimits.StaffToDeskLimits
import services.crunch.desklimits.{PortDeskLimits, TerminalDeskLimitsLike}
import services.crunch.deskrecs.OptimiserMocks.MockSinkActor
import services.crunch.deskrecs.RunnableOptimisation.CrunchRequest
import services.crunch.{CrunchTestLike, TestDefaults}
import services.graphstages.CrunchMocks
import services.{SDate, TryCrunch}

import scala.collection.immutable.Map
import scala.concurrent.duration._


class MockProviderActor extends Actor {
  override def receive: Receive = {
    case _: GetStateForDateRange => sender() ! MinutesContainer.empty[CrunchMinute, TQM]
    case msg => println(s"got $msg")
  }
}

class RunnableDynamicDeploymentsSpec extends CrunchTestLike {
  val airportConfig: AirportConfig = TestDefaults.airportConfigWithEgates

  val maxDesksProvider: Map[Terminal, TerminalDeskLimitsLike] = PortDeskLimits.flexed(airportConfig)
  val mockCrunch: TryCrunch = CrunchMocks.mockCrunch
  val pcpPaxCalcFn: Arrival => Int = PcpPax.bestPaxEstimateWithApi

  val staffToDeskLimits: StaffToDeskLimits = PortDeskLimits.flexedByAvailableStaff(airportConfig)
  val desksAndWaitsProvider: PortDesksAndWaitsProvider =
    PortDesksAndWaitsProvider(airportConfig, mockCrunch, pcpPaxCalcFn)

  def setupGraphAndCheckQueuePax(expectedQueuePax: PartialFunction[Any, Boolean]): Any = {
    val probe = TestProbe()

    val request = CrunchRequest(SDate("2021-05-01").toLocalDate, 0, 1440)
    val sink = system.actorOf(Props(new MockSinkActor(probe.ref)))
    val mockProvider = system.actorOf(Props(new MockProviderActor))

    val deskRecs = DynamicRunnableDeployments.crunchRequestsToDeployments(
      OptimisationProviders.loadsProvider(mockProvider),
      OptimisationProviders.staffMinutesProvider(mockProvider, airportConfig.terminals),
      staffToDeskLimits,
      desksAndWaitsProvider.loadsToSimulations)

    val (queue, _) = RunnableOptimisation.createGraph(sink, deskRecs).run()
    queue.offer(request)

    probe.fishForMessage(1 second)(expectedQueuePax)
  }

  "Given a mock workload provider returning no workloads" >> {
    "When I ask for deployments I should see 1440 minutes for each queue" >> {
      val expected: PartialFunction[Any, Boolean] = {
        case SimulationMinutes(minutes) =>
          val byQueue: Map[Queue, Int] = minutes.groupBy(_.queue).mapValues(_.size)
          byQueue === Map(EeaDesk -> 1440, NonEeaDesk -> 1440, EGate -> 1440)
      }
      setupGraphAndCheckQueuePax(expectedQueuePax = expected)

      success
    }
  }
}
