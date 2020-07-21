package services.crunch.deskrecs

import actors.MinuteLookupsLike
import actors.acking.AckingReceiver.{Ack, StreamCompleted, StreamFailure, StreamInitialized}
import actors.minutes.MinutesActorLike.{MinutesLookup, MinutesUpdate}
import actors.minutes.{QueueMinutesActor, StaffMinutesActor}
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.testkit.TestProbe
import drt.shared.CrunchApi.CrunchMinute
import drt.shared.Queues.Queue
import drt.shared.Terminals.Terminal
import drt.shared._
import drt.shared.api.Arrival
import org.slf4j.{Logger, LoggerFactory}
import services.crunch.CrunchTestLike
import services.crunch.desklimits.PortDeskLimits
import services.graphstages.Crunch.crunchStartWithOffset
import services.{Optimiser, SDate}

import scala.collection.immutable.Map
import scala.concurrent.ExecutionContext


class MockPortStateActorForDeployments(probe: TestProbe, responseDelayMillis: Long = 0L) extends Actor {
  val log: Logger = LoggerFactory.getLogger(getClass)

  override def receive: Receive = {
    case StreamInitialized =>
      sender() ! Ack

    case StreamCompleted =>
      log.info(s"Completed")
      probe.ref ! StreamCompleted

    case StreamFailure(t) =>
      log.error(s"Failed", t)
      probe.ref ! StreamFailure

    case simMins: SimulationMinutes =>
      sender() ! Ack
      probe.ref ! simMins
  }
}

class TestQueueMinutesActor(probe: ActorRef,
                            now: () => SDateLike,
                            terminals: Iterable[Terminal],
                            lookup: MinutesLookup[CrunchMinute, TQM],
                            lookupLegacy: MinutesLookup[CrunchMinute, TQM],
                            updateMinutes: MinutesUpdate[CrunchMinute, TQM]) extends QueueMinutesActor(now, terminals, lookup, lookupLegacy, updateMinutes) {

  override def receive: Receive = testReceives

  def testReceives: Receive = {
    case msg =>
      probe ! msg
      super.receive(msg)
  }
}

case class TestMinuteLookups(queueProbe: ActorRef,
                             system: ActorSystem,
                             now: () => SDateLike,
                             expireAfterMillis: Int,
                             queuesByTerminal: Map[Terminal, Seq[Queue]],
                             override val replayMaxCrunchStateMessages: Int)
                            (implicit val ec: ExecutionContext) extends MinuteLookupsLike {
  override val queueMinutesActor: ActorRef = system.actorOf(Props(new TestQueueMinutesActor(queueProbe, now, queuesByTerminal.keys, queuesLookup, legacyQueuesLookup, updateCrunchMinutes)))

  override val staffMinutesActor: ActorRef = system.actorOf(Props(new StaffMinutesActor(now, queuesByTerminal.keys, staffLookup, legacyStaffLookup, updateStaffMinutes)))
}


class RunnableDeploymentsSpec extends CrunchTestLike {
  val noDelay: Long = 0L
  val pcpPaxCalcFn: Arrival => Int = PcpPax.bestPaxEstimateWithApi

  "Given a RunnableDescRecs with a mock PortStateActor and mock crunch " +
    "When I give it a millisecond of 2019-01-01T00:00 " +
    "The I should see a queues actor request for GetStateForDateRange followed by a SimulationMinutes message to the port state actor" >> {
    val queuesProbe = TestProbe("queues")
    val portStateProbe = TestProbe("port-state")
    val mockPortStateActor = system.actorOf(Props(new MockPortStateActorForDeployments(portStateProbe, noDelay)))
    val now = () => SDate("2020-07-17T00:00")
    val lookups = TestMinuteLookups(queuesProbe.ref, system, now, MilliTimes.oneDayMillis, defaultAirportConfig.queuesByTerminal, 1000)
    val terminalToIntsToTerminalToStaff = PortDeskLimits.flexedByAvailableStaff(defaultAirportConfig) _
    val crunchStartDateProvider: SDateLike => SDateLike = crunchStartWithOffset(defaultAirportConfig.crunchOffsetMinutes)
    val (daysQueueSource, _) = RunnableDeployments(
      mockPortStateActor,
      lookups.queueMinutesActor,
      lookups.staffMinutesActor,
      terminalToIntsToTerminalToStaff,
      crunchStartDateProvider,
      PortDeskLimits.fixed(defaultAirportConfig),
      defaultAirportConfig.minutesToCrunch,
      DesksAndWaitsPortProvider(defaultAirportConfig, Optimiser.crunch, pcpPaxCalcFn)
    ).run()

    val midnight20190101 = SDate("2019-01-01T00:00")
    daysQueueSource.offer(midnight20190101.millisSinceEpoch)

    queuesProbe.expectMsg(GetStateForDateRange(midnight20190101.millisSinceEpoch, midnight20190101.addMinutes(defaultAirportConfig.minutesToCrunch - 1).millisSinceEpoch))

    portStateProbe.expectMsgClass(classOf[SimulationMinutes])

    success
  }
}
