package services.crunch.deskrecs

import actors.acking.AckingReceiver.{Ack, StreamCompleted, StreamFailure, StreamInitialized}
import akka.actor.{Actor, ActorRef, Props}
import akka.pattern.AskableActorRef
import akka.testkit.TestProbe
import akka.util.Timeout
import controllers.ArrivalGenerator
import drt.shared.CrunchApi.{CrunchMinute, DeskRecMinute, DeskRecMinutes}
import drt.shared.FlightsApi.{Flights, FlightsWithSplits}
import drt.shared.PaxTypes.{EeaMachineReadable, VisaNational}
import drt.shared.PaxTypesAndQueues.{eeaMachineReadableToDesk, visaNationalToDesk}
import drt.shared.SplitRatiosNs.SplitSources
import drt.shared.Terminals.{T1, Terminal}
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import server.feeds.ArrivalsFeedSuccess
import services.crunch.CrunchTestLike
import services.graphstages.CrunchMocks
import services.{SDate, TryCrunch}

import scala.collection.immutable.Seq
import scala.concurrent.duration._


class MockPortStateActor(probe: TestProbe, responseDelayMillis: Long = 0L) extends Actor {
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
      sender() ! FlightsWithSplits(flightsToReturn, List())
      probe.ref ! getFlights

    case drm: DeskRecMinutes =>
      sender() ! Ack
      probe.ref ! drm

    case SetFlights(flightsToSet) => flightsToReturn = flightsToSet

    case message =>
      log.warn(s"Hmm got a $message")
      sender() ! Ack
      probe.ref ! message
  }
}

class RunnableDeskRecsSpec extends CrunchTestLike {
  implicit val timeout: Timeout = new Timeout(250 milliseconds)

  val mockCrunch: TryCrunch = CrunchMocks.mockCrunch
  val noDelay: Long = 0L
  val longDelay = 500L
  val historicSplits = Splits(Set(
    ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 50, None),
    ApiPaxTypeAndQueueCount(VisaNational, Queues.NonEeaDesk, 50, None)),
    SplitSources.Historical, None, Percentage)

  "Given a RunnableDescRecs with a mock PortStateActor and mock crunch " +
    "When I give it a millisecond of 2019-01-01T00:00 " +
    "I should see a request for flights for 2019-01-01 00:00 to 00:30" >> {
    val portStateProbe = TestProbe("port-state")
    val mockPortStateActor = system.actorOf(Props(classOf[MockPortStateActor], portStateProbe, noDelay))
    val (millisToCrunchSourceActor: ActorRef, _) = RunnableDeskRecs(mockPortStateActor, 30, mockCrunch, airportConfig).run()
    val askableSource: AskableActorRef = millisToCrunchSourceActor

    val midnight20190101 = SDate("2019-01-01T00:00")
    askableSource ? List(midnight20190101.millisSinceEpoch)

    val expectedStart = midnight20190101.millisSinceEpoch
    val expectedEnd = midnight20190101.addMinutes(30).millisSinceEpoch

    portStateProbe.fishForMessage(1 second) {
      case GetFlights(start, end) => start == expectedStart && end == expectedEnd
    }

    success
  }

  "Given a RunnableDescRecs with a mock PortStateActor and mock crunch " +
    "When I give it a long response delay " +
    "I should not see a StreamComplete message (because the timeout exception is handled)" >> {
    val portStateProbe = TestProbe("port-state")
    val mockPortStateActor = system.actorOf(Props(classOf[MockPortStateActor], portStateProbe, longDelay))

    val (millisToCrunchSourceActor: ActorRef, _) = RunnableDeskRecs(mockPortStateActor, 30, mockCrunch, airportConfig).run()
    val askableSource: AskableActorRef = millisToCrunchSourceActor

    val midnight20190101 = SDate("2019-01-01T00:00")
    askableSource ? List(midnight20190101.millisSinceEpoch)

    val expectedStart = midnight20190101.millisSinceEpoch
    val expectedEnd = midnight20190101.addMinutes(30).millisSinceEpoch

    portStateProbe.fishForMessage(1 second) {
      case GetFlights(start, end) => start == expectedStart && end == expectedEnd
    }
    portStateProbe.fishForMessage(1 second) {
      case _: DeskRecMinutes => true
    }

    portStateProbe.expectNoMessage(500 milliseconds)

    success
  }


  "Given a flight with splits " +
    "When I ask for the workload " +
    "Then I should see the workload associated with the best splits for that flight" >> {
    val scheduled = "2019-10-10T23:05:00Z"
    val arrival = ArrivalGenerator.arrival(iata = "BA0001", schDt = scheduled, actPax = Option(25))

    val flight = List(ApiFlightWithSplits(arrival, Set(historicSplits), None))

    val portStateProbe = TestProbe("port-state")
    val mockPortStateActor = system.actorOf(Props(classOf[MockPortStateActor], portStateProbe, noDelay))
    mockPortStateActor ! SetFlights(flight)

    val procTimes: Map[Terminal, Map[PaxTypeAndQueue, Double]] = Map(T1 -> Map(eeaMachineReadableToDesk -> 30d / 60, visaNationalToDesk -> 60d / 60))
    val testAirportConfig = airportConfig.copy(terminalProcessingTimes = procTimes)

    val (actor: ActorRef, _) = RunnableDeskRecs(mockPortStateActor, 30, mockCrunch, testAirportConfig).run()
    val millisToCrunchSourceActor: AskableActorRef = actor

    val epoch = SDate(scheduled).millisSinceEpoch
    millisToCrunchSourceActor ? List(epoch)

    val expectedLoads = Set(
      (Queues.EeaDesk, 10, epoch),
      (Queues.EeaDesk, 2.5, SDate(scheduled).addMinutes(1).millisSinceEpoch),
      (Queues.NonEeaDesk, 10, epoch),
      (Queues.NonEeaDesk, 2.5, SDate(scheduled).addMinutes(1).millisSinceEpoch)
    )

    portStateProbe.fishForMessage(2 seconds) {
      case DeskRecMinutes(drms) =>
        val result = drms.filterNot(_.paxLoad == 0).map(drm => (drm.queueName, drm.paxLoad, drm.minute)).toSet
        result == expectedLoads
      case _ => false
    }

    success
  }

  "Given a flight with splits and some transit pax " +
    "When I ask for the workload " +
    "Then I should see the workload associated with the best splits for that flight" >> {
    val scheduled = "2019-10-10T23:05:00Z"
    val arrival = ArrivalGenerator.arrival(iata = "BA0001", schDt = scheduled, actPax = Option(75), tranPax = Option(50))

    val flight = List(ApiFlightWithSplits(arrival, Set(historicSplits), None))

    val portStateProbe = TestProbe("port-state")
    val mockPortStateActor = system.actorOf(Props(classOf[MockPortStateActor], portStateProbe, noDelay))
    mockPortStateActor ! SetFlights(flight)

    val procTimes: Map[Terminal, Map[PaxTypeAndQueue, Double]] = Map(T1 -> Map(eeaMachineReadableToDesk -> 30d / 60, visaNationalToDesk -> 60d / 60))
    val testAirportConfig = airportConfig.copy(terminalProcessingTimes = procTimes)

    val (actor: ActorRef, _) = RunnableDeskRecs(mockPortStateActor, 30, mockCrunch, testAirportConfig).run()
    val millisToCrunchSourceActor: AskableActorRef = actor

    val epoch = SDate(scheduled).millisSinceEpoch
    millisToCrunchSourceActor ? List(epoch)

    val expectedLoads = Set(
      (Queues.EeaDesk, 10, epoch),
      (Queues.EeaDesk, 2.5, SDate(scheduled).addMinutes(1).millisSinceEpoch),
      (Queues.NonEeaDesk, 10, epoch),
      (Queues.NonEeaDesk, 2.5, SDate(scheduled).addMinutes(1).millisSinceEpoch)
    )

    portStateProbe.fishForMessage(2 seconds) {
      case DeskRecMinutes(drms) =>
        val result = drms.filterNot(_.paxLoad == 0).map(drm => (drm.queueName, drm.paxLoad, drm.minute)).toSet
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
    val arrival = ArrivalGenerator.arrival(iata = "BA0001", schDt = scheduled, actPax = Option(25))
    val arrival2 = ArrivalGenerator.arrival(iata = "BA0002", schDt = scheduled2, actPax = Option(25))

    val flight = List(ApiFlightWithSplits(arrival, Set(historicSplits), None), ApiFlightWithSplits(arrival2, Set(historicSplits), None))

    val portStateProbe = TestProbe("port-state")
    val mockPortStateActor = system.actorOf(Props(classOf[MockPortStateActor], portStateProbe, noDelay))
    mockPortStateActor ! SetFlights(flight)

    val procTimes: Map[Terminal, Map[PaxTypeAndQueue, Double]] = Map(T1 -> Map(eeaMachineReadableToDesk -> 30d / 60, visaNationalToDesk -> 60d / 60))
    val testAirportConfig = airportConfig.copy(terminalProcessingTimes = procTimes)

    val (actor: ActorRef, _) = RunnableDeskRecs(mockPortStateActor, 30, mockCrunch, testAirportConfig).run()
    val millisToCrunchSourceActor: AskableActorRef = actor

    val scheduledSd = SDate(scheduled)
    millisToCrunchSourceActor ? List(scheduledSd.millisSinceEpoch)

    val expectedLoads = Set(
      (Queues.EeaDesk, 10, scheduledSd.addMinutes(0).millisSinceEpoch),
      (Queues.EeaDesk, 2.5 + 10, scheduledSd.addMinutes(1).millisSinceEpoch),
      (Queues.EeaDesk, 0 + 2.5, scheduledSd.addMinutes(2).millisSinceEpoch),
      (Queues.NonEeaDesk, 10, scheduledSd.addMinutes(0).millisSinceEpoch),
      (Queues.NonEeaDesk, 2.5 + 10, scheduledSd.addMinutes(1).millisSinceEpoch),
      (Queues.NonEeaDesk, 0 + 2.5, scheduledSd.addMinutes(2).millisSinceEpoch)
    )

    portStateProbe.fishForMessage(2 seconds) {
      case DeskRecMinutes(drms) =>
        val result = drms.filterNot(_.paxLoad == 0).map(drm => (drm.queueName, drm.paxLoad, drm.minute)).toSet
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
    val arrival = ArrivalGenerator.arrival(iata = "BA0001", schDt = pcpOne, actPax = Option(25))

    val historicSplits = Splits(
      Set(ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 100, None)),
      SplitSources.Historical, None, Percentage)

    val portStateProbe = TestProbe("port-state")
    val mockPortStateActor = system.actorOf(Props(classOf[MockPortStateActor], portStateProbe, noDelay))

    val procTimes: Map[Terminal, Map[PaxTypeAndQueue, Double]] = Map(T1 -> Map(eeaMachineReadableToDesk -> 30d / 60))
    val testAirportConfig = airportConfig.copy(terminalProcessingTimes = procTimes)

    val (actor: ActorRef, _) = RunnableDeskRecs(mockPortStateActor, 30, mockCrunch, testAirportConfig).run()
    val millisToCrunchSourceActor: AskableActorRef = actor

    val noonMillis = SDate(pcpOne).millisSinceEpoch
    val onePmMillis = SDate(pcpUpdated).millisSinceEpoch
    mockPortStateActor ! SetFlights(List(ApiFlightWithSplits(arrival.copy(PcpTime = Option(noonMillis)), Set(historicSplits), None)))
    millisToCrunchSourceActor ? List(noonMillis)

    portStateProbe.fishForMessage(2 seconds) {
      case DeskRecMinutes(drms) =>
        drms.exists {
          case DeskRecMinute(T1, Queues.EeaDesk, m, p, w, _, _) => m == noonMillis && p > 0
          case _ => false
        }
      case _ => false
    }

    mockPortStateActor ! SetFlights(List(ApiFlightWithSplits(arrival.copy(PcpTime = Option(onePmMillis)), Set(historicSplits), None)))
    millisToCrunchSourceActor ? List(onePmMillis)

    portStateProbe.fishForMessage(2 seconds) {
      case DeskRecMinutes(drms) =>
        val zeroAtNoon = drms.exists {
          case DeskRecMinute(T1, Queues.EeaDesk, m, p, w, _, _) => m == noonMillis && p == 0
          case _ => false
        }
        val nonZeroAtOne = drms.exists {
          case DeskRecMinute(T1, Queues.EeaDesk, m, p, w, _, _) => m == onePmMillis && p > 0
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
    val testAirportConfig = airportConfig.copy(
      terminalProcessingTimes = procTimes,
      terminals = Seq(T1),
      queues = Map(T1 -> Seq(Queues.EeaDesk))
    )
    val arrival = ArrivalGenerator.arrival(iata = "BA0001", schDt = noon, actPax = Option(25))

    val historicSplits = Splits(
      Set(ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 100, None)),
      SplitSources.Historical, None, Percentage)

    val noonMillis = SDate(noon).millisSinceEpoch
    val noon30Millis = SDate(noon30).millisSinceEpoch

    val flight1 = ApiFlightWithSplits(arrival.copy(PcpTime = Option(noonMillis)), Set(historicSplits), None)

    val initialPortState = PortState(
      flightsWithSplits = List(flight1),
      crunchMinutes = List(
        CrunchMinute(T1, Queues.EeaDesk, noonMillis, 30, 10, 0, 0),
        CrunchMinute(T1, Queues.EeaDesk, noonMillis + 60000, 5, 2.5, 0, 0),
        CrunchMinute(T1, Queues.EeaDesk, noonMillis + 120000, 1, 1, 0, 0),
        CrunchMinute(T1, Queues.EeaDesk, noonMillis + 180000, 1, 1, 0, 0)
      ),
      staffMinutes = List()
    )
    val crunch = runCrunchGraph(
      airportConfig = testAirportConfig,
      now = () => SDate(noon),
      pcpArrivalTime = pcpForFlightFromBest,
      initialPortState = Option(initialPortState)
    )

    offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Flights(Seq(arrival.copy(Estimated = Option(noon30Millis))))))

    crunch.portStateTestProbe.fishForMessage(2 seconds) {
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
    val testAirportConfig = airportConfig.copy(
      terminalProcessingTimes = procTimes,
      terminals = Seq(T1),
      queues = Map(T1 -> Seq(Queues.EeaDesk))
    )
    val crunch = runCrunchGraph(
      airportConfig = testAirportConfig,
      now = () => SDate(noon),
      pcpArrivalTime = pcpForFlightFromBest
    )

    val arrival = ArrivalGenerator.arrival(iata = "BA0001", schDt = noon, actPax = Option(25), origin = "JFK")
    val arrival2 = ArrivalGenerator.arrival(iata = "BA0002", schDt = noon, actPax = Option(25), origin = "AAA")

    val noonMillis = SDate(noon).millisSinceEpoch
    val noon30Millis = SDate(noon30).millisSinceEpoch

    offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Flights(Seq(arrival, arrival2))))

    crunch.portStateTestProbe.fishForMessage(2 seconds) {
      case PortState(_, cms, _) =>
        val nonZeroAtNoon = cms.get(TQM(T1, Queues.EeaDesk, noonMillis)) match {
          case None => false
          case Some(cm) => cm.paxLoad == 40 && cm.workLoad == 20
        }
        nonZeroAtNoon
    }

    offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Flights(Seq(arrival.copy(Estimated = Option(noon30Millis))))))

    crunch.portStateTestProbe.fishForMessage(2 seconds) {
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

  "Given a flight with splits for the EEA and NonEEA queues and the NonEEA queue is diverted to the EEA queue " +
    "When I ask for the workload " +
    "Then I should see the combined workload associated with the best splits for that flight only in the EEA queue " >> {
    val scheduled = "2018-01-01T00:05"
    val arrival = ArrivalGenerator.arrival(iata = "BA0001", schDt = scheduled, actPax = Option(25))

    val flight = List(ApiFlightWithSplits(arrival, Set(historicSplits), None))

    val portStateProbe = TestProbe("port-state")
    val mockPortStateActor = system.actorOf(Props(classOf[MockPortStateActor], portStateProbe, noDelay))
    mockPortStateActor ! SetFlights(flight)

    val procTimes: Map[Terminal, Map[PaxTypeAndQueue, Double]] = Map(T1 -> Map(eeaMachineReadableToDesk -> 30d / 60, visaNationalToDesk -> 60d / 60))
    val testAirportConfig = airportConfig.copy(
      terminalProcessingTimes = procTimes,
      divertedQueues = Map(Queues.NonEeaDesk -> Queues.EeaDesk)
    )

    val (actor: ActorRef, _) = RunnableDeskRecs(mockPortStateActor, 30, mockCrunch, testAirportConfig).run()
    val millisToCrunchSourceActor: AskableActorRef = actor

    val epoch = SDate(scheduled).millisSinceEpoch
    millisToCrunchSourceActor ? List(epoch)

    val expectedLoads = Set(
      (Queues.EeaDesk, 20, epoch),
      (Queues.EeaDesk, 5, SDate(scheduled).addMinutes(1).millisSinceEpoch)
    )

    portStateProbe.fishForMessage(2 seconds) {
      case DeskRecMinutes(drms) =>
        val result = drms.filterNot(_.paxLoad == 0).map(drm => (drm.queueName, drm.paxLoad, drm.minute)).toSet
        println(s"result: ${result.mkString("\n")}")
        result == expectedLoads
      case _ => false
    }

    success
  }

  "Given a flight with splits which is subsequently removed " +
    "When I ask for the output loads " +
    "Then I should see zero loads for the minutes affected by the flight in question" >> {
    val noon = "2018-01-01T00:00"
    val procTimes: Map[Terminal, Map[PaxTypeAndQueue, Double]] = Map(T1 -> Map(eeaMachineReadableToDesk -> 30d / 60))
    val testAirportConfig = airportConfig.copy(
      terminalProcessingTimes = procTimes,
      terminals = Seq(T1),
      queues = Map(T1 -> Seq(Queues.EeaDesk))
    )
    val crunch = runCrunchGraph(
      airportConfig = testAirportConfig,
      now = () => SDate(noon),
      pcpArrivalTime = pcpForFlightFromBest
    )

    val arrival = ArrivalGenerator.arrival(iata = "BA0001", schDt = noon, actPax = Option(25), origin = "JFK")
    val arrival2 = ArrivalGenerator.arrival(iata = "BA0002", schDt = noon, actPax = Option(25), origin = "AAA")

    val noonMillis = SDate(noon).millisSinceEpoch

    offerAndWait(crunch.baseArrivalsInput, ArrivalsFeedSuccess(Flights(Seq(arrival, arrival2))))

    crunch.portStateTestProbe.fishForMessage(2 seconds) {
      case PortState(_, cms, _) =>
        val nonZeroAtNoon = cms.get(TQM(T1, Queues.EeaDesk, noonMillis)) match {
          case None => false
          case Some(cm) => cm.paxLoad == 40 && cm.workLoad == 20
        }
        nonZeroAtNoon
    }

    offerAndWait(crunch.baseArrivalsInput, ArrivalsFeedSuccess(Flights(Seq(arrival))))

    crunch.portStateTestProbe.fishForMessage(2 seconds) {
      case PortState(_, cms, _) =>
        val fewerAtNoon = cms.get(TQM(T1, Queues.EeaDesk, noonMillis)) match {
          case None => false
          case Some(cm) => cm.paxLoad == 20 && cm.workLoad == 10
        }
        fewerAtNoon
    }

    success
  }

  "Given loads for a set of minutes within a day for 2 queues at one terminal " +
    "When I ask for crunch result " +
    "Then I should see a full day's worth (1440) of crunch minutes per queue crunched - a total of 2880" >> {
    val scheduled = "2018-01-01T00:05"
    val arrival = ArrivalGenerator.arrival(iata = "BA0001", schDt = scheduled, actPax = Option(25))
    val flight = List(ApiFlightWithSplits(arrival, Set(historicSplits), None))

    val portStateProbe = TestProbe("port-state")
    val mockPortStateActor = system.actorOf(Props(classOf[MockPortStateActor], portStateProbe, noDelay))
    mockPortStateActor ! SetFlights(flight)

    val minsInADay = 1440
    val (actor: ActorRef, _) = RunnableDeskRecs(mockPortStateActor, minsInADay, mockCrunch, airportConfig).run()
    val millisToCrunchSourceActor: AskableActorRef = actor

    val epoch = SDate(scheduled).millisSinceEpoch
    millisToCrunchSourceActor ? List(epoch)

    portStateProbe.fishForMessage(2 seconds) {
      case DeskRecMinutes(drms) => drms.length === airportConfig.queues(T1).size * minsInADay
      case _ => false
    }

    success
  }

}

case class SetFlights(flightsToSet: List[ApiFlightWithSplits])
