package services.crunch.deskrecs

import actors.MinuteLookups
import actors.acking.AckingReceiver.{Ack, StreamCompleted, StreamFailure, StreamInitialized}
import akka.actor.{Actor, Props}
import akka.testkit.TestProbe
import drt.shared.CrunchApi.DeskRecMinutes
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared._
import drt.shared.api.Arrival
import org.slf4j.{Logger, LoggerFactory}
import services.crunch.CrunchTestLike
import services.crunch.desklimits.PortDeskLimits
import services.graphstages.Crunch.crunchStartWithOffset
import services.{Optimiser, SDate}

import scala.concurrent.duration._


class MockPortStateActorForDeployments(probe: TestProbe, responseDelayMillis: Long = 0L) extends Actor {
  var flightsToReturn: List[ApiFlightWithSplits] = List()
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

    case getFlights: GetFlights =>
      Thread.sleep(responseDelayMillis)
      sender() ! FlightsWithSplits(flightsToReturn.map(fws => (fws.unique, fws)))
      probe.ref ! getFlights

    case drm: DeskRecMinutes =>
      sender() ! Ack
      probe.ref ! drm

    case simMins: SimulationMinutes =>
      sender() ! Ack
      probe.ref ! simMins

    case SetFlights(flightsToSet) => flightsToReturn = flightsToSet

    case message =>
      log.warn(s"Hmm got a $message")
      sender() ! Ack
      probe.ref ! message
  }
}


class RunnableDeploymentsSpec extends CrunchTestLike {
  val noDelay: Long = 0L
  val pcpPaxCalcFn: Arrival => Int = PcpPax.bestPaxEstimateWithApi

  "Given a RunnableDescRecs with a mock PortStateActor and mock crunch " +
    "When I give it a millisecond of 2019-01-01T00:00 " +
    "I should see a request for xxx for 2019-01-01 00:00 to 00:30" >> {
    val portStateProbe = TestProbe("port-state")
    val mockPortStateActor = system.actorOf(Props(new MockPortStateActorForDeployments(portStateProbe, noDelay)))
    val now = () => SDate("2020-07-17T00:00")
    val lookups = MinuteLookups(system, now, MilliTimes.oneDayMillis, defaultAirportConfig.queuesByTerminal, 1000)
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

    portStateProbe.expectMsgClass(classOf[SimulationMinutes])

    success
  }
}
