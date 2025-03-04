package services.crunch.deskrecs

import actors.PartitionedPortStateActor.GetFlightsForTerminalDateRange
import actors.persistent.SortedActorRefSource
import akka.Done
import akka.actor.{Actor, ActorRef, Props}
import akka.pattern.StatusReply
import akka.stream.UniqueKillSwitch
import akka.stream.scaladsl.Source
import akka.testkit.TestProbe
import akka.util.Timeout
import controllers.ArrivalGenerator
import drt.server.feeds.ArrivalsFeedSuccess
import drt.shared.CrunchApi._
import drt.shared.FlightsApi.PaxForArrivals
import drt.shared._
import manifests.queues.SplitsCalculator
import org.slf4j.{Logger, LoggerFactory}
import queueus._
import services.TryCrunchWholePax
import services.crunch.{CrunchTestLike, MockEgatesProvider, TestConfig, TestDefaults}
import services.graphstages.{CrunchMocks, FlightFilter}
import uk.gov.homeoffice.drt.actor.acking.AckingReceiver.{StreamCompleted, StreamFailure, StreamInitialized}
import uk.gov.homeoffice.drt.actor.commands.TerminalUpdateRequest
import uk.gov.homeoffice.drt.arrivals.SplitStyle.Percentage
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.model.{CrunchMinute, TQM}
import uk.gov.homeoffice.drt.ports.PaxTypes.{EeaMachineReadable, VisaNational}
import uk.gov.homeoffice.drt.ports.PaxTypesAndQueues.eeaMachineReadableToDesk
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources
import uk.gov.homeoffice.drt.ports.Terminals.{T1, Terminal}
import uk.gov.homeoffice.drt.ports._
import uk.gov.homeoffice.drt.redlist.RedListUpdates
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, SDateLike, UtcDate}

import scala.collection.SortedSet
import scala.collection.immutable.{Map, Seq, SortedMap}
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}


class MockPortStateActor(probe: TestProbe, responseDelayMillis: Long) extends Actor {
  var flightsToReturn: List[ApiFlightWithSplits] = List()
  val log: Logger = LoggerFactory.getLogger(getClass)

  override def receive: Receive = {
    case StreamInitialized =>
      sender() ! StatusReply.Ack

    case StreamCompleted =>
      log.info(s"Completed")
      probe.ref ! StreamCompleted

    case StreamFailure(t) =>
      log.error(s"Failed", t)
      probe.ref ! StreamFailure

    case getFlights: GetFlightsForTerminalDateRange =>
      implicit val ec: ExecutionContextExecutor = context.dispatcher
      val replyTo = sender()
      context.system.scheduler.scheduleOnce(responseDelayMillis.milliseconds) {
        replyTo ! Source(List((UtcDate(2021, 8, 8), FlightsWithSplits(flightsToReturn))))
        probe.ref ! getFlights
      }

    case drm: DeskRecMinutes =>
      sender() ! StatusReply.Ack
      probe.ref ! drm

    case SetFlights(flightsToSet) => flightsToReturn = flightsToSet

    case minutes: MinutesContainer[_, _] =>
      sender() ! StatusReply.Ack
      probe.ref ! minutes

    case message =>
      log.warn(s"Hmm got a $message")
      sender() ! StatusReply.Ack
      probe.ref ! message
  }
}

class MockSplitsSinkActor() extends Actor {
  override def receive: Receive = {
    case _: SplitsForArrivals => sender() ! Set.empty[Long]
    case _: PaxForArrivals => sender() ! Set.empty[Long]
    case _: ArrivalsDiff => sender() ! Set.empty[Long]
  }
}

class RunnableDeskRecsSpec extends CrunchTestLike {
  val mockCrunch: TryCrunchWholePax = CrunchMocks.mockCrunchWholePax
  val noDelay = 10L
  val longDelay = 250L
  val historicSplits: Splits = Splits(Set(
    ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 50, None, None),
    ApiPaxTypeAndQueueCount(VisaNational, Queues.NonEeaDesk, 50, None, None)),
    SplitSources.Historical, None, Percentage)
  val minutesToCrunch = 30
  val nowFromSDate: () => SDateLike = () => SDate.now()

  val flexDesks = false
  val mockSplitsSink: ActorRef = system.actorOf(Props(new MockSplitsSinkActor))

  private def getDeskRecsGraph(mockPortStateActor: ActorRef,
                               airportConfig: AirportConfig = defaultAirportConfig): (ActorRef, UniqueKillSwitch) = {
    val paxAllocation = PaxTypeQueueAllocation(
      B5JPlusTypeAllocator,
      TerminalQueueAllocator(airportConfig.terminalPaxTypeQueueAllocation))

    val splitsCalc = SplitsCalculator(paxAllocation, airportConfig.terminalPaxSplits, AdjustmentsNoop)
    val desksAndWaitsProvider = PortDesksAndWaitsProvider(airportConfig, mockCrunch, FlightFilter.forPortConfig(airportConfig), paxFeedSourceOrder, (_: LocalDate, q: Queue) => Future.successful(airportConfig.slaByQueue(q)))

    implicit val timeout: Timeout = new Timeout(50.milliseconds)
    val deskRecsProducer = DynamicRunnablePassengerLoads.crunchRequestsToQueueMinutes(
      arrivalsProvider = OptimisationProviders.flightsWithSplitsProvider(mockPortStateActor),
      portDesksAndWaitsProvider = desksAndWaitsProvider,
      redListUpdatesProvider = () => Future.successful(RedListUpdates.empty),
      dynamicQueueStatusProvider = DynamicQueueStatusProvider(airportConfig, MockEgatesProvider.portProvider(airportConfig)),
      queuesByTerminal = airportConfig.queuesByTerminal,
      updateLiveView = _ => Future.successful(StatusReply.Ack),
      paxFeedSourceOrder = paxFeedSourceOrder,
      terminalSplits = splitsCalc.terminalSplits,
      updateCapacity = _ => Future.successful(Done),
      setUpdatedAtForDay = (_, _, _) => Future.successful(Done),
    )
    val crunchGraphSource = new SortedActorRefSource(TestProbe().ref, SortedSet.empty[TerminalUpdateRequest], "desk-recs")

    QueuedRequestProcessing.createGraph(crunchGraphSource, mockPortStateActor, deskRecsProducer, "desk-recs").run()
  }

  "Given a RunnableDescRecs with a mock PortStateActor and mock crunch " +
    "When I give it a millisecond of 2019-01-01T00:00 " +
    "I should see a request for flights for 2019-01-01 00:00 to 00:30" >> {
    val portStateProbe = TestProbe("port-state")
    val mockPortStateActor = system.actorOf(Props(new MockPortStateActor(portStateProbe, noDelay)))
    val (daysQueueSource, _) = getDeskRecsGraph(mockPortStateActor)

    val midnight20190101 = SDate("2019-01-01T00:00")
    daysQueueSource ! TerminalUpdateRequest(T1, midnight20190101.toLocalDate)
    val expectedStart = midnight20190101.millisSinceEpoch
    val expectedEnd = midnight20190101.addDays(1).addMinutes(-1).millisSinceEpoch

    portStateProbe.fishForMessage(5.seconds) {
      case GetFlightsForTerminalDateRange(start, end, t) =>
        start == expectedStart && end == expectedEnd && t == T1
      case _ => false
    }

    success
  }

  "Given a RunnableDescRecs with a mock PortStateActor and mock crunch " +
    "When I give it a long response delay " +
    "I should not see a StreamComplete message (because the timeout exception is handled)" >> {
    val portStateProbe = TestProbe("port-state")
    val mockPortStateActor = system.actorOf(Props(new MockPortStateActor(portStateProbe, longDelay)))

    val (daysQueueSource, _) = getDeskRecsGraph(mockPortStateActor)

    val midnight20190101 = SDate("2019-01-01T00:00")
    daysQueueSource ! TerminalUpdateRequest(T1, midnight20190101.toLocalDate)

    portStateProbe.expectNoMessage(200.milliseconds)

    success
  }

  "Given a flight with splits, when I ask for the workload" >> {
    "Then I should see the workload associated with the best splits for that flight" >> {
      val scheduled = "2019-10-10T23:05:00Z"
      val arrival = ArrivalGenerator.arrival(iata = "BA0001", schDt = scheduled, totalPax = Option(25), feedSource = LiveFeedSource)

      val flight = List(ApiFlightWithSplits(arrival, Set(historicSplits), None))

      val portStateProbe = TestProbe("port-state")
      val mockPortStateActor = system.actorOf(Props(new MockPortStateActor(portStateProbe, noDelay)))
      mockPortStateActor ! SetFlights(flight)

      val (daysQueueSource, _) = getDeskRecsGraph(mockPortStateActor)

      daysQueueSource ! TerminalUpdateRequest(T1, SDate(scheduled).toLocalDate)

      val pcpTime = SDate(scheduled).addMinutes(Arrival.defaultMinutesToChox)

      val expectedLoads = Set(
        (Queues.EeaDesk, 10, pcpTime.millisSinceEpoch),
        (Queues.EeaDesk, 3, pcpTime.addMinutes(1).millisSinceEpoch),
        (Queues.NonEeaDesk, 10, pcpTime.millisSinceEpoch),
        (Queues.NonEeaDesk, 2, pcpTime.addMinutes(1).millisSinceEpoch)
      )

      portStateProbe.fishForMessage(2.seconds) {
        case minutes: MinutesContainer[PassengersMinute, TQM] =>
          val result = minutes.minutes.filterNot(_.toMinute.passengers.isEmpty).map(drm => (drm.toMinute.queue, drm.toMinute.passengers.size, drm.minute)).toSet
          result == expectedLoads
        case _ => false
      }

      success
    }
  }

  "Given a flight with splits and some transit pax " +
    "When I ask for the workload " +
    "Then I should see the workload associated with the best splits for that flight" >> {
    val scheduled = "2019-10-10T23:05:00Z"
    val arrival = ArrivalGenerator.arrival(iata = "BA0001", schDt = scheduled, totalPax = Option(75), transPax = Option(50), feedSource = LiveFeedSource)

    val flight = List(ApiFlightWithSplits(arrival, Set(historicSplits), None))

    val portStateProbe = TestProbe("port-state")
    val mockPortStateActor = system.actorOf(Props(new MockPortStateActor(portStateProbe, noDelay)))
    mockPortStateActor ! SetFlights(flight)

    val (daysQueueSource, _) = getDeskRecsGraph(mockPortStateActor)

    daysQueueSource ! TerminalUpdateRequest(T1, SDate(scheduled).toLocalDate)

    val pcpTime = SDate(scheduled).addMinutes(Arrival.defaultMinutesToChox)

    val expectedLoads = Set(
      (Queues.EeaDesk, 10, pcpTime.millisSinceEpoch),
      (Queues.EeaDesk, 3, pcpTime.addMinutes(1).millisSinceEpoch),
      (Queues.NonEeaDesk, 10, pcpTime.millisSinceEpoch),
      (Queues.NonEeaDesk, 2, pcpTime.addMinutes(1).millisSinceEpoch)
    )

    portStateProbe.fishForMessage(2.seconds) {
      case minutes: MinutesContainer[PassengersMinute, TQM] =>
        val result = minutes.minutes.filterNot(_.toMinute.passengers.isEmpty).map(drm => (drm.toMinute.queue, drm.toMinute.passengers.size, drm.minute)).toSet
        result == expectedLoads
      case _ => false
    }

    success
  }

  "Given two flights with splits with overlapping pax " +
    "When I ask for the workload " +
    "Then I should see the combined workload for those flights" >> {
    val scheduled = "2018-01-01T00:05"
    val scheduled2 = "2018-01-01T00:06"
    val arrival = ArrivalGenerator.arrival(iata = "BA0001", schDt = scheduled, totalPax = Option(25), feedSource = LiveFeedSource)
    val arrival2 = ArrivalGenerator.arrival(iata = "BA0002", schDt = scheduled2, totalPax = Option(25), feedSource = LiveFeedSource)

    val flight = List(ApiFlightWithSplits(arrival, Set(historicSplits), None), ApiFlightWithSplits(arrival2, Set(historicSplits), None))

    val portStateProbe = TestProbe("port-state")
    val mockPortStateActor = system.actorOf(Props(new MockPortStateActor(portStateProbe, noDelay)))
    mockPortStateActor ! SetFlights(flight)

    val (daysQueueSource, _) = getDeskRecsGraph(mockPortStateActor)

    val pcpTime = SDate(scheduled).addMinutes(Arrival.defaultMinutesToChox)
    daysQueueSource ! TerminalUpdateRequest(T1, pcpTime.toLocalDate)

    val expectedLoads = Set(
      (Queues.EeaDesk, 10, pcpTime.addMinutes(0).millisSinceEpoch),
      (Queues.EeaDesk, 3 + 10, pcpTime.addMinutes(1).millisSinceEpoch),
      (Queues.EeaDesk, 0 + 3, pcpTime.addMinutes(2).millisSinceEpoch),
      (Queues.NonEeaDesk, 10, pcpTime.addMinutes(0).millisSinceEpoch),
      (Queues.NonEeaDesk, 2 + 10, pcpTime.addMinutes(1).millisSinceEpoch),
      (Queues.NonEeaDesk, 0 + 2, pcpTime.addMinutes(2).millisSinceEpoch)
    )

    portStateProbe.fishForMessage(2.seconds) {
      case minutes: MinutesContainer[PassengersMinute, TQM] =>
        val result = minutes.minutes.filterNot(_.toMinute.passengers.isEmpty).map(drm => (drm.toMinute.queue, drm.toMinute.passengers.size, drm.minute)).toSet
        result == expectedLoads
      case _ => false
    }

    success
  }

  "Given a single flight with pax arriving at PCP at 00:15 and going to a single queue " +
    "When the PCP time updates to 00:05  " +
    "Then the workload from noon should disappear and move to 00:05" >> {

    val pcpOne = "2018-01-01T00:15"
    val pcpUpdated = "2018-01-01T00:05"
    val arrival = ArrivalGenerator.live(iata = "BA0001", schDt = pcpOne, totalPax = Option(25)).toArrival(LiveFeedSource)

    val historicSplits = Splits(
      Set(ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 100, None, None)),
      SplitSources.Historical, None, Percentage)

    val portStateProbe = TestProbe("port-state")
    val mockPortStateActor = system.actorOf(Props(new MockPortStateActor(portStateProbe, noDelay)))

    val (daysQueueSource, _) = getDeskRecsGraph(mockPortStateActor)

    val noonMillis = SDate(pcpOne).millisSinceEpoch
    val onePmMillis = SDate(pcpUpdated).millisSinceEpoch
    mockPortStateActor ! SetFlights(List(ApiFlightWithSplits(arrival.copy(PcpTime = Option(noonMillis)), Set(historicSplits), None)))
    daysQueueSource ! TerminalUpdateRequest(T1, SDate(pcpOne).toLocalDate)

    portStateProbe.fishForMessage(2.seconds) {
      case minutes: MinutesContainer[PassengersMinute, TQM] =>
        val cms = minutes.minutes.filterNot(_.toMinute.passengers.isEmpty).map(_.toMinute)
        cms.exists {
          case PassengersMinute(T1, Queues.EeaDesk, m, p, _) => m == noonMillis && p.nonEmpty
          case _ => false
        }
      case _ => false
    }

    mockPortStateActor ! SetFlights(List(ApiFlightWithSplits(arrival.copy(PcpTime = Option(onePmMillis)), Set(historicSplits), None)))
    daysQueueSource ! TerminalUpdateRequest(T1, SDate(pcpUpdated).toLocalDate)

    portStateProbe.fishForMessage(2.seconds) {
      case minutes: MinutesContainer[PassengersMinute, TQM] =>
        val cms = minutes.minutes.map(_.toMinute)
        val zeroAtNoon = cms.exists {
          case PassengersMinute(T1, Queues.EeaDesk, m, p, _) => m == noonMillis && p.isEmpty
          case _ => false
        }
        val nonZeroAtOne = cms.exists {
          case PassengersMinute(T1, Queues.EeaDesk, m, p, _) => m == onePmMillis && p.nonEmpty
          case _ => false
        }
        zeroAtNoon && nonZeroAtOne
      case _ => false
    }

    success
  }

  "Given an existing single flight with pax arriving at PCP at midnight " +
    "When the PCP time updates to 00:30  " +
    "Then the workload in the PortState from noon should disappear and move to 00:30" >> {

    val noon = "2018-01-01T00:00"
    val noon30 = "2018-01-01T00:30"
    val procTimes: Map[Terminal, Map[PaxTypeAndQueue, Double]] = Map(T1 -> Map(eeaMachineReadableToDesk -> 30d / 60))
    val arrival = ArrivalGenerator.live(iata = "BA0001", schDt = noon, totalPax = Option(25))

    val historicSplits = Splits(
      Set(ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 100, None, None)),
      SplitSources.Historical, None, Percentage)

    val noonMillis = SDate(noon).millisSinceEpoch
    val noon30Millis = SDate(noon30).millisSinceEpoch

    val flight1 = ApiFlightWithSplits(arrival.toArrival(LiveFeedSource).copy(PcpTime = Option(noonMillis)), Set(historicSplits), None)

    val initialPortState = PortState(
      flightsWithSplits = List(flight1),
      crunchMinutes = List(
        CrunchMinute(T1, Queues.EeaDesk, noonMillis, 30, 10, 0, 0, None),
        CrunchMinute(T1, Queues.EeaDesk, noonMillis + 60000, 5, 2.5, 0, 0, None),
        CrunchMinute(T1, Queues.EeaDesk, noonMillis + 120000, 1, 1, 0, 0, None),
        CrunchMinute(T1, Queues.EeaDesk, noonMillis + 180000, 1, 1, 0, 0, None)
      ),
      staffMinutes = List()
    )
    val crunch = runCrunchGraph(TestConfig(
      airportConfig = defaultAirportConfig.copy(
        terminalProcessingTimes = procTimes,
        queuesByTerminal = SortedMap(T1 -> Seq(Queues.EeaDesk)),
        minutesToCrunch = 60
      ),
      now = () => SDate(noon),
      setPcpTimes = TestDefaults.setPcpFromBest,
      initialPortState = Option(initialPortState)
    ))

    offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Seq(arrival.copy(estimated = Option(noon30Millis)))))

    crunch.portStateTestProbe.fishForMessage(2.seconds) {
      case PortState(_, cms, _) =>
        val zeroAtNoon = cms.get(TQM(T1, Queues.EeaDesk, noonMillis)) match {
          case None => false
          case Some(cm) => cm.paxLoad == 0 && cm.workLoad == 0
        }
        val nonZeroAtOnePm = cms.get(TQM(T1, Queues.EeaDesk, noon30Millis)) match {
          case None => false
          case Some(cm) => cm.paxLoad == 20 && cm.workLoad == 10
        }
        zeroAtNoon && nonZeroAtOnePm
    }

    success
  }

  "Given a two flights with pax arriving at PCP at midnight " +
    "When the PCP time updates to 00:30 for one " +
    "Then the workload in the PortState from noon should spread between 00:00 and 00:30" >> {

    val noon = "2018-01-01T00:00"
    val noon30 = "2018-01-01T00:30"
    val procTimes: Map[Terminal, Map[PaxTypeAndQueue, Double]] = Map(T1 -> Map(eeaMachineReadableToDesk -> 30d / 60))
    val crunch = runCrunchGraph(TestConfig(
      airportConfig = defaultAirportConfig.copy(
        terminalProcessingTimes = procTimes,
        queuesByTerminal = SortedMap(T1 -> Seq(Queues.EeaDesk)),
        minutesToCrunch = 60
      ),
      now = () => SDate(noon),
      setPcpTimes = TestDefaults.setPcpFromBest
    ))

    val arrival = ArrivalGenerator.live(iata = "BA0001", schDt = noon, totalPax = Option(25), origin = PortCode("JFK"))
    val arrival2 = ArrivalGenerator.live(iata = "BA0002", schDt = noon, totalPax = Option(25), origin = PortCode("AAA"))

    val noonMillis = SDate(noon).millisSinceEpoch
    val noon30Millis = SDate(noon30).millisSinceEpoch

    offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Seq(arrival, arrival2)))

    crunch.portStateTestProbe.fishForMessage(2.seconds) {
      case PortState(_, cms, _) =>
        val nonZeroAtNoon = cms.get(TQM(T1, Queues.EeaDesk, noonMillis)) match {
          case None => false
          case Some(cm) => cm.paxLoad == 40 && cm.workLoad == 20
        }
        nonZeroAtNoon
    }

    offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Seq(arrival.copy(estimated = Option(noon30Millis)))))

    crunch.portStateTestProbe.fishForMessage(2.seconds) {
      case PortState(_, cms, _) =>
        val zeroAtNoon = cms.get(TQM(T1, Queues.EeaDesk, noonMillis)) match {
          case None => false
          case Some(cm) => cm.paxLoad == 20 && cm.workLoad == 10
        }
        val nonZeroAtOnePm = cms.get(TQM(T1, Queues.EeaDesk, noon30Millis)) match {
          case None => false
          case Some(cm) => cm.paxLoad == 20 && cm.workLoad == 10
        }
        zeroAtNoon && nonZeroAtOnePm
    }

    success
  }

  "Given a flight with splits which is subsequently removed " +
    "When I ask for the output loads " +
    "Then I should see zero loads for the minutes affected by the flight in question" >> {
    val noon = "2018-01-01T00:00"
    val procTimes: Map[Terminal, Map[PaxTypeAndQueue, Double]] = Map(T1 -> Map(eeaMachineReadableToDesk -> 30d / 60))
    val testAirportConfig = defaultAirportConfig.copy(
      terminalProcessingTimes = procTimes,
      queuesByTerminal = SortedMap(T1 -> Seq(Queues.EeaDesk))
    )
    val crunch = runCrunchGraph(TestConfig(
      airportConfig = testAirportConfig,
      now = () => SDate(noon),
      setPcpTimes = TestDefaults.setPcpFromBest
    ))

    val arrival = ArrivalGenerator.forecast(iata = "BA0001", schDt = noon, totalPax = Option(25), origin = PortCode("JFK"))
    val arrival2 = ArrivalGenerator.forecast(iata = "BA0002", schDt = noon, totalPax = Option(25), origin = PortCode("AAA"))

    val noonMillis = SDate(noon).millisSinceEpoch

    offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(Seq(arrival, arrival2)))

    crunch.portStateTestProbe.fishForMessage(2.seconds) {
      case PortState(_, cms, _) =>
        val nonZeroAtNoon = cms.get(TQM(T1, Queues.EeaDesk, noonMillis)) match {
          case None => false
          case Some(cm) => cm.paxLoad == 40 && cm.workLoad == 20
        }
        nonZeroAtNoon
    }

    offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(Seq(arrival)))

    crunch.portStateTestProbe.fishForMessage(2.seconds) {
      case PortState(_, cms, _) =>
        val fewerAtNoon = cms.get(TQM(T1, Queues.EeaDesk, noonMillis)) match {
          case None => false
          case Some(cm) => cm.paxLoad == 20 && cm.workLoad == 10
        }
        fewerAtNoon
    }

    success
  }

  "Given loads for a set of minutes within a day for 2 queues " +
    "When I ask for crunch result " +
    "Then I should see a full day's worth (1440) of crunch minutes per queue crunched - a total of 1440 * 2" >> {
    val scheduled = "2018-01-01T00:05"
    val arrival = ArrivalGenerator.live(iata = "BA0001", schDt = scheduled, totalPax = Option(25))
    val flight = List(ApiFlightWithSplits(arrival.toArrival(LiveFeedSource), Set(historicSplits), None))

    val portStateProbe = TestProbe("port-state")
    val mockPortStateActor = system.actorOf(Props(new MockPortStateActor(portStateProbe, noDelay)))
    mockPortStateActor ! SetFlights(flight)

    val minsInADay = 1440
    val airportConfig = defaultAirportConfig.copy(minutesToCrunch = minsInADay)
    val (daysQueueSource, _) = getDeskRecsGraph(mockPortStateActor, airportConfig)

    daysQueueSource ! TerminalUpdateRequest(T1, SDate(scheduled).toLocalDate)

    portStateProbe.fishForMessage(2.seconds) {
      case mins: MinutesContainer[CrunchMinute, TQM] => mins.minutes.size === defaultAirportConfig.queuesByTerminal(T1).size * minsInADay
      case _ => false
    }

    success
  }
}

case class SetFlights(flightsToSet: List[ApiFlightWithSplits])
