package services.graphstages

import actors.acking.AckingReceiver.StreamCompleted
import akka.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source, SourceQueueWithComplete}
import akka.stream.{ClosedShape, OverflowStrategy}
import akka.testkit.TestProbe
import controllers.ArrivalGenerator
import drt.shared.CrunchApi.{CrunchMinute, PortState}
import drt.shared.FlightsApi.{Flights, FlightsWithSplits}
import drt.shared.PaxTypes.{EeaMachineReadable, VisaNational}
import drt.shared.PaxTypesAndQueues.{eeaMachineReadableToDesk, visaNationalToDesk}
import drt.shared.SplitRatiosNs.SplitSources
import drt.shared._
import server.feeds.ArrivalsFeedSuccess
import services.SDate
import services.crunch.CrunchTestLike
import services.graphstages.Crunch.{LoadMinute, Loads}

import scala.collection.immutable.Seq
import scala.concurrent.Await
import scala.concurrent.duration._


object TestableWorkloadStage {
  val oneDayMillis: Int = 60 * 60 * 24 * 1000

  def apply(testProbe: TestProbe,
            now: () => SDateLike,
            airportConfig: AirportConfig,
            workloadStart: SDateLike => SDateLike,
            workloadEnd: SDateLike => SDateLike,
            earliestAndLatestAffectedPcpTime: (Set[ApiFlightWithSplits], Set[ApiFlightWithSplits]) => Option[(SDateLike, SDateLike)]
           ): RunnableGraph[SourceQueueWithComplete[FlightsWithSplits]] = {
    val workloadStage = new WorkloadGraphStage(
      optionalInitialLoads = None,
      optionalInitialFlightsWithSplits = None,
      airportConfig = airportConfig,
      natProcTimes = Map(),
      expireAfterMillis = oneDayMillis,
      now = now,
      useNationalityBasedProcessingTimes = false
    )

    val flightsWithSplitsSource = Source.queue[FlightsWithSplits](1, OverflowStrategy.backpressure)

    import akka.stream.scaladsl.GraphDSL.Implicits._

    val graph = GraphDSL.create(flightsWithSplitsSource.async) {

      implicit builder =>
        flights =>
          val workload = builder.add(workloadStage.async)
          val sink = builder.add(Sink.actorRef(testProbe.ref, StreamCompleted))

          flights ~> workload ~> sink

          ClosedShape
    }

    RunnableGraph.fromGraph(graph)
  }
}

class WorkloadGraphStageSpec extends CrunchTestLike {
  "Given a flight with splits " +
    "When I ask for the workload " +
    "Then I should see the workload associated with the best splits for that flight" >> {

    val probe = TestProbe("workload")
    val scheduled = "2018-01-01T00:05"
    val workloadStart = (_: SDateLike) => SDate(scheduled)
    val workloadEnd = (_: SDateLike) => SDate(scheduled).addMinutes(30)
    val workloadWindow = (_: Set[ApiFlightWithSplits], _: Set[ApiFlightWithSplits]) => Option((workloadStart(SDate(scheduled)), workloadEnd(SDate(scheduled))))
    val procTimes = Map("T1" -> Map(eeaMachineReadableToDesk -> 30d / 60, visaNationalToDesk -> 60d / 60))
    val testAirportConfig = airportConfig.copy(defaultProcessingTimes = procTimes)
    val flightsWithSplits = TestableWorkloadStage(probe, () => SDate(scheduled), testAirportConfig, workloadStart, workloadEnd, workloadWindow).run

    val arrival = ArrivalGenerator.arrival(iata = "BA0001", schDt = scheduled, actPax = Option(25))
    val historicSplits = Splits(
      Set(
        ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 50, None),
        ApiPaxTypeAndQueueCount(VisaNational, Queues.NonEeaDesk, 50, None)),
      SplitSources.Historical, None, Percentage)

    val flight = FlightsWithSplits(List(ApiFlightWithSplits(arrival, Set(historicSplits), None)), List())

    flightsWithSplits.offer(flight)

    val expectedLoads = Loads(Seq(
      LoadMinute("T1", Queues.EeaDesk, 10, 5, SDate(scheduled).millisSinceEpoch),
      LoadMinute("T1", Queues.EeaDesk, 2.5, 1.25, SDate(scheduled).addMinutes(1).millisSinceEpoch),
      LoadMinute("T1", Queues.NonEeaDesk, 10, 10, SDate(scheduled).millisSinceEpoch),
      LoadMinute("T1", Queues.NonEeaDesk, 2.5, 2.5, SDate(scheduled).addMinutes(1).millisSinceEpoch)
    )).loadMinutes

    val result = probe.receiveOne(2 seconds) match {
      case Loads(loadMinutes) => loadMinutes
    }

    result === expectedLoads
  }

  "Given a flight with splits and some transit pax " +
    "When I ask for the workload " +
    "Then I should see the workload associated with the best splits for that flight" >> {

    val probe = TestProbe("workload")
    val scheduled = "2018-01-01T00:05"
    val workloadStart = (_: SDateLike) => SDate(scheduled)
    val workloadEnd = (_: SDateLike) => SDate(scheduled).addMinutes(30)
    val workloadWindow = (_: Set[ApiFlightWithSplits], _: Set[ApiFlightWithSplits]) => Option((workloadStart(SDate(scheduled)), workloadEnd(SDate(scheduled))))
    val procTimes = Map("T1" -> Map(eeaMachineReadableToDesk -> 30d / 60, visaNationalToDesk -> 60d / 60))
    val testAirportConfig = airportConfig.copy(
      terminalNames = Seq("T1"),
      queues = Map("T1" -> Seq(Queues.EeaDesk, Queues.NonEeaDesk, Queues.Transfer)),
      defaultProcessingTimes = procTimes
    )
    val flightsWithSplits = TestableWorkloadStage(probe, () => SDate(scheduled), testAirportConfig, workloadStart, workloadEnd, workloadWindow).run

    val arrival = ArrivalGenerator.arrival(iata = "BA0001", schDt = scheduled, actPax = Option(75), tranPax = Option(50))
    val historicSplits = Splits(
      Set(
        ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 50, None),
        ApiPaxTypeAndQueueCount(VisaNational, Queues.NonEeaDesk, 50, None)),
      SplitSources.Historical, None, Percentage)

    val flight = FlightsWithSplits(List(ApiFlightWithSplits(arrival, Set(historicSplits), None)), List())

    flightsWithSplits.offer(flight)

    val expectedLoads = Loads(Seq(
      LoadMinute("T1", Queues.EeaDesk, 10, 5, SDate(scheduled).millisSinceEpoch),
      LoadMinute("T1", Queues.EeaDesk, 2.5, 1.25, SDate(scheduled).addMinutes(1).millisSinceEpoch),
      LoadMinute("T1", Queues.NonEeaDesk, 10, 10, SDate(scheduled).millisSinceEpoch),
      LoadMinute("T1", Queues.NonEeaDesk, 2.5, 2.5, SDate(scheduled).addMinutes(1).millisSinceEpoch)
    )).loadMinutes

    val result = probe.receiveOne(2 seconds) match {
      case Loads(loadMinutes) => loadMinutes
    }

    result === expectedLoads
  }

  "Given two flights with splits with overlapping pax " +
    "When I ask for the workload " +
    "Then I should see the combined workload for those flights" >> {

    val probe = TestProbe("workload")
    val scheduled = "2018-01-01T00:05"
    val scheduled2 = "2018-01-01T00:06"
    val workloadStart = (t: SDateLike) => Crunch.getLocalLastMidnight(t)
    val workloadEnd = (t: SDateLike) => Crunch.getLocalNextMidnight(t)
    val workloadWindow = (_: Set[ApiFlightWithSplits], _: Set[ApiFlightWithSplits]) => Option((workloadStart(SDate(scheduled)), workloadEnd(SDate(scheduled))))
    val procTimes = Map("T1" -> Map(eeaMachineReadableToDesk -> 30d / 60, visaNationalToDesk -> 60d / 60))
    val testAirportConfig = airportConfig.copy(defaultProcessingTimes = procTimes)
    val flightsWithSplits = TestableWorkloadStage(probe, () => SDate(scheduled), testAirportConfig, workloadStart, workloadEnd, workloadWindow).run

    val arrival = ArrivalGenerator.arrival(iata = "BA0001", schDt = scheduled, actPax = Option(25))
    val arrival2 = ArrivalGenerator.arrival(iata = "BA0002", schDt = scheduled2, actPax = Option(25))
    val historicSplits = Splits(Set(
      ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 50, None),
      ApiPaxTypeAndQueueCount(VisaNational, Queues.NonEeaDesk, 50, None)),
      SplitSources.Historical, None, Percentage)

    val flight = FlightsWithSplits(List(ApiFlightWithSplits(arrival, Set(historicSplits), None)), List())
    val flight2 = FlightsWithSplits(List(ApiFlightWithSplits(arrival2, Set(historicSplits), None)), List())

    flightsWithSplits.offer(flight)
    flightsWithSplits.offer(flight2)

    val expectedLoads = Loads(Seq(
      LoadMinute("T1", Queues.EeaDesk, 2.5 + 10, 1.25 + 5, SDate(scheduled).addMinutes(1).millisSinceEpoch),
      LoadMinute("T1", Queues.EeaDesk, 0 + 2.5, 0 + 1.25, SDate(scheduled).addMinutes(2).millisSinceEpoch),
      LoadMinute("T1", Queues.NonEeaDesk, 2.5 + 10, 2.5 + 10, SDate(scheduled).addMinutes(1).millisSinceEpoch),
      LoadMinute("T1", Queues.NonEeaDesk, 0 + 2.5, 0 + 2.5, SDate(scheduled).addMinutes(2).millisSinceEpoch)
    )).loadMinutes

    val result = probe.receiveN(2, 2 seconds).reverse.head match {
      case Loads(loadMinutes) => loadMinutes
    }

    result === expectedLoads
  }

  "Given a single flight with pax arriving at PCP at noon and going to a single queue " +
    "When the PCP time updates to 1pm  " +
    "Then the workload from noon should disappear and move to 1pm" >> {

    val probe = TestProbe("workload")
    val noon = "2018-01-01T12:00"
    val onePm = "2018-01-01T00:06"
    val workloadStart = (t: SDateLike) => Crunch.getLocalLastMidnight(t)
    val workloadEnd = (t: SDateLike) => Crunch.getLocalNextMidnight(t)
    val workloadWindow = (_: Set[ApiFlightWithSplits], _: Set[ApiFlightWithSplits]) => Option((workloadStart(SDate(noon)), workloadEnd(SDate(noon))))
    val procTimes = Map("T1" -> Map(eeaMachineReadableToDesk -> 30d / 60))
    val testAirportConfig = airportConfig.copy(defaultProcessingTimes = procTimes)
    val flightsWithSplits = TestableWorkloadStage(probe, () => SDate(noon), testAirportConfig, workloadStart, workloadEnd, workloadWindow).run

    val arrival = ArrivalGenerator.arrival(iata = "BA0001", schDt = noon, actPax = Option(25))
    val historicSplits = Splits(
      Set(ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 100, None)),
      SplitSources.Historical, None, Percentage)

    val noonMillis = SDate(noon).millisSinceEpoch
    val onePmMillis = SDate(onePm).millisSinceEpoch

    val flight1 = FlightsWithSplits(List(ApiFlightWithSplits(arrival.copy(PcpTime = Option(noonMillis)), Set(historicSplits), None)), List())
    val flight1Update = FlightsWithSplits(List(ApiFlightWithSplits(arrival.copy(PcpTime = Option(onePmMillis)), Set(historicSplits), None)), List())

    Await.ready(flightsWithSplits.offer(flight1), 1 second)

    probe.fishForMessage(2 seconds) {
      case Loads(loadMinutes) =>
        val nonZeroAtNoon = loadMinutes.get(TQM("T1", Queues.EeaDesk, noonMillis)) match {
          case Some(cm) => cm.paxLoad > 0 && cm.workLoad > 0
          case _ => false
        }
        nonZeroAtNoon
    }

    Await.ready(flightsWithSplits.offer(flight1Update), 1 second)

    probe.fishForMessage(2 seconds) {
      case Loads(loadMinutes) =>
        val zeroAtNoon = loadMinutes.get(TQM("T1", Queues.EeaDesk, noonMillis)) match {
          case Some(cm) => cm.paxLoad == 0 && cm.workLoad == 0
          case _ => false
        }
        val nonZeroAtOnePm = loadMinutes.get(TQM("T1", Queues.EeaDesk, onePmMillis)) match {
          case Some(cm) => cm.paxLoad > 0 && cm.workLoad > 0
          case _ => false
        }
        zeroAtNoon && nonZeroAtOnePm
    }

    success
  }

  "Given a single flight with pax arriving at PCP at midnight " +
    "When the PCP time updates to 00:30  " +
    "Then the workload in the PortState from noon should disappear and move to 00:30" >> {

    val noon = "2018-01-01T00:00"
    val noon30 = "2018-01-01T00:30"
    val procTimes = Map("T1" -> Map(eeaMachineReadableToDesk -> 30d / 60))
    val testAirportConfig = airportConfig.copy(
      defaultProcessingTimes = procTimes,
      terminalNames = Seq("T1"),
      queues = Map("T1" -> Seq(Queues.EeaDesk))
    )
    val crunch = runCrunchGraph(
      airportConfig = testAirportConfig,
      now = () => SDate(noon),
      pcpArrivalTime = pcpForFlightFromBest
    )

    val arrival = ArrivalGenerator.arrival(iata = "BA0001", schDt = noon, actPax = Option(25))

    val noonMillis = SDate(noon).millisSinceEpoch
    val noon30Millis = SDate(noon30).millisSinceEpoch

    offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Flights(Seq(arrival.copy(Estimated = Option(noonMillis))))))

    crunch.liveTestProbe.fishForMessage(2 seconds) {
      case PortState(_, cms, _) =>
        val nonZeroAtNoon = cms.get(TQM("T1", Queues.EeaDesk, noonMillis)) match {
          case None => false
          case Some(cm) => cm.paxLoad == 20 && cm.workLoad == 10
        }
        nonZeroAtNoon
    }

    offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Flights(Seq(arrival.copy(Estimated = Option(noon30Millis))))))

    crunch.liveTestProbe.fishForMessage(2 seconds) {
      case PortState(_, cms, _) =>
        val zeroAtNoon = cms.get(TQM("T1", Queues.EeaDesk, noonMillis)) match {
          case None => false
          case Some(cm) => cm.paxLoad == 0 && cm.workLoad == 0
        }
        val nonZeroAtOnePm = cms.get(TQM("T1", Queues.EeaDesk, noon30Millis)) match {
          case None => false
          case Some(cm) => cm.paxLoad == 20 && cm.workLoad == 10
        }
        zeroAtNoon && nonZeroAtOnePm
    }

    success
  }

  "Given an existing single flight with pax arriving at PCP at midnight " +
    "When the PCP time updates to 00:30  " +
    "Then the workload in the PortState from noon should disappear and move to 00:30" >> {

    val noon = "2018-01-01T00:00"
    val noon30 = "2018-01-01T00:30"
    val procTimes = Map("T1" -> Map(eeaMachineReadableToDesk -> 30d / 60))
    val testAirportConfig = airportConfig.copy(
      defaultProcessingTimes = procTimes,
      terminalNames = Seq("T1"),
      queues = Map("T1" -> Seq(Queues.EeaDesk))
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
        CrunchMinute("T1", Queues.EeaDesk, noonMillis, 30, 10, 0, 0),
        CrunchMinute("T1", Queues.EeaDesk, noonMillis + 60000, 5, 2.5, 0, 0),
        CrunchMinute("T1", Queues.EeaDesk, noonMillis + 120000, 1, 1, 0, 0),
        CrunchMinute("T1", Queues.EeaDesk, noonMillis + 180000, 1, 1, 0, 0)
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

    crunch.liveTestProbe.fishForMessage(2 seconds) {
      case PortState(_, cms, _) =>
        val zeroAtNoon = cms.get(TQM("T1", Queues.EeaDesk, noonMillis)) match {
          case None => false
          case Some(cm) => cm.paxLoad == 0 && cm.workLoad == 0
        }
        val nonZeroAtOnePm = cms.get(TQM("T1", Queues.EeaDesk, noon30Millis)) match {
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
    val procTimes = Map("T1" -> Map(eeaMachineReadableToDesk -> 30d / 60))
    val testAirportConfig = airportConfig.copy(
      defaultProcessingTimes = procTimes,
      terminalNames = Seq("T1"),
      queues = Map("T1" -> Seq(Queues.EeaDesk))
    )
    val crunch = runCrunchGraph(
      airportConfig = testAirportConfig,
      now = () => SDate(noon),
      pcpArrivalTime = pcpForFlightFromBest
    )

    val arrival = ArrivalGenerator.arrival(iata = "BA0001", origin = "JFK", schDt = noon, actPax = Option(25))
    val arrival2 = ArrivalGenerator.arrival(iata = "BA0002", origin = "AAA", schDt = noon, actPax = Option(25))

    val noonMillis = SDate(noon).millisSinceEpoch
    val noon30Millis = SDate(noon30).millisSinceEpoch

    offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Flights(Seq(arrival, arrival2))))

    crunch.liveTestProbe.fishForMessage(2 seconds) {
      case PortState(_, cms, _) =>
        val nonZeroAtNoon = cms.get(TQM("T1", Queues.EeaDesk, noonMillis)) match {
          case None => false
          case Some(cm) => cm.paxLoad == 40 && cm.workLoad == 20
        }
        nonZeroAtNoon
    }

    offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Flights(Seq(arrival.copy(Estimated = Option(noon30Millis))))))

    crunch.liveTestProbe.fishForMessage(2 seconds) {
      case PortState(_, cms, _) =>
        val zeroAtNoon = cms.get(TQM("T1", Queues.EeaDesk, noonMillis)) match {
          case None => false
          case Some(cm) => cm.paxLoad == 20 && cm.workLoad == 10
        }
        val nonZeroAtOnePm = cms.get(TQM("T1", Queues.EeaDesk, noon30Millis)) match {
          case None => false
          case Some(cm) => cm.paxLoad == 20 && cm.workLoad == 10
        }
        zeroAtNoon && nonZeroAtOnePm
    }

    success
  }

  "Given two flights with splits with overlapping pax " +
    "When the first flight receives an estimated arrival time update and I ask for the workload " +
    "Then I should see the combined workload for those flights" >> {

    val probe = TestProbe("workload")
    val scheduled = "2018-01-01T00:05"
    val scheduled2 = "2018-01-01T00:06"
    val workloadStart = (t: SDateLike) => Crunch.getLocalLastMidnight(t)
    val workloadEnd = (t: SDateLike) => Crunch.getLocalNextMidnight(t)
    val workloadWindow = (_: Set[ApiFlightWithSplits], _: Set[ApiFlightWithSplits]) => Option((workloadStart(SDate(scheduled)), workloadEnd(SDate(scheduled))))
    val procTimes = Map("T1" -> Map(eeaMachineReadableToDesk -> 30d / 60, visaNationalToDesk -> 60d / 60))
    val testAirportConfig = airportConfig.copy(defaultProcessingTimes = procTimes)
    val flightsWithSplits = TestableWorkloadStage(probe, () => SDate(scheduled), testAirportConfig, workloadStart, workloadEnd, workloadWindow).run

    val arrival = ArrivalGenerator.arrival(iata = "BA0001", schDt = scheduled, actPax = Option(25))
    val arrival2 = ArrivalGenerator.arrival(iata = "BA0002", schDt = scheduled2, actPax = Option(25))
    val historicSplits = Splits(Set(
      ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 50, None),
      ApiPaxTypeAndQueueCount(VisaNational, Queues.NonEeaDesk, 50, None)),
      SplitSources.Historical, None, Percentage)

    val flight1 = FlightsWithSplits(List(ApiFlightWithSplits(arrival, Set(historicSplits), None)), List())
    val flight2 = FlightsWithSplits(List(ApiFlightWithSplits(arrival2, Set(historicSplits), None)), List())
    val flight1Update = FlightsWithSplits(List(ApiFlightWithSplits(arrival.copy(PcpTime = arrival.PcpTime.map(_ + 60000)), Set(historicSplits), None)), List())

    Await.ready(flightsWithSplits.offer(flight1), 1 second)
    Await.ready(flightsWithSplits.offer(flight2), 1 second)
    Await.ready(flightsWithSplits.offer(flight1Update), 1 second)

    val expectedLoads = Loads(Seq(
      LoadMinute("T1", Queues.EeaDesk, 0, 0, SDate(scheduled).addMinutes(0).millisSinceEpoch),
      LoadMinute("T1", Queues.EeaDesk, 10 + 10, 5 + 5, SDate(scheduled).addMinutes(1).millisSinceEpoch),
      LoadMinute("T1", Queues.EeaDesk, 2.5 + 2.5, 1.25 + 1.25, SDate(scheduled).addMinutes(2).millisSinceEpoch),
      LoadMinute("T1", Queues.NonEeaDesk, 0, 0, SDate(scheduled).addMinutes(0).millisSinceEpoch),
      LoadMinute("T1", Queues.NonEeaDesk, 10 + 10, 10 + 10, SDate(scheduled).addMinutes(1).millisSinceEpoch),
      LoadMinute("T1", Queues.NonEeaDesk, 2.5 + 2.5, 2.5 + 2.5, SDate(scheduled).addMinutes(2).millisSinceEpoch)
    )).loadMinutes

    val result = probe.receiveN(3, 2 seconds).reverse.head match {
      case Loads(loadMinutes) => loadMinutes
    }

    result === expectedLoads
  }

  "Given a flight with splits for the EEA and NonEEA queues and the NonEEA queue is diverted to the EEA queue " +
    "When I ask for the workload " +
    "Then I should see the combined workload associated with the best splits for that flight only in the EEA queue " >> {

    val probe = TestProbe("workload")
    val scheduled = "2018-01-01T00:05"
    val workloadStart = (_: SDateLike) => SDate(scheduled)
    val workloadEnd = (_: SDateLike) => SDate(scheduled).addMinutes(30)
    val workloadWindow = (_: Set[ApiFlightWithSplits], _: Set[ApiFlightWithSplits]) => Option((workloadStart(SDate(scheduled)), workloadEnd(SDate(scheduled))))
    val procTimes = Map("T1" -> Map(eeaMachineReadableToDesk -> 30d / 60, visaNationalToDesk -> 60d / 60))
    val testAirportConfig = airportConfig.copy(
      defaultProcessingTimes = procTimes,
      divertedQueues = Map(Queues.NonEeaDesk -> Queues.EeaDesk)
    )
    val flightsWithSplits = TestableWorkloadStage(probe, () => SDate(scheduled), testAirportConfig, workloadStart, workloadEnd, workloadWindow).run

    val arrival = ArrivalGenerator.arrival(iata = "BA0001", schDt = scheduled, actPax = Option(25))
    val historicSplits = Splits(
      Set(
        ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 50, None),
        ApiPaxTypeAndQueueCount(VisaNational, Queues.NonEeaDesk, 50, None)),
      SplitSources.Historical, None, Percentage)

    val flight = FlightsWithSplits(List(ApiFlightWithSplits(arrival, Set(historicSplits), None)), List())

    flightsWithSplits.offer(flight)

    val expectedLoads = Loads(Seq(
      LoadMinute("T1", Queues.EeaDesk, 20, 15, SDate(scheduled).millisSinceEpoch),
      LoadMinute("T1", Queues.EeaDesk, 5, 3.75, SDate(scheduled).addMinutes(1).millisSinceEpoch)
    )).loadMinutes

    val result = probe.receiveOne(2 seconds) match {
      case Loads(loadMinutes) => loadMinutes
    }

    result === expectedLoads
  }
}
