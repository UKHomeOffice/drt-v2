package services.crunch

import akka.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source, SourceQueueWithComplete}
import akka.stream.{ClosedShape, OverflowStrategy}
import akka.testkit.TestProbe
import controllers.ArrivalGenerator
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.PaxTypes.{EeaMachineReadable, VisaNational}
import drt.shared.PaxTypesAndQueues.{eeaMachineReadableToDesk, visaNationalToDesk}
import drt.shared.SplitRatiosNs.SplitSources
import drt.shared._
import services.SDate
import services.graphstages.Crunch.{LoadMinute, Loads}
import services.graphstages.{Crunch, WorkloadGraphStage}

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
          val sink = builder.add(Sink.actorRef(testProbe.ref, "complete"))

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

    val arrival = ArrivalGenerator.apiFlight(iata = "BA0001", schDt = scheduled, actPax = Option(25))
    val historicSplits = Splits(
      Set(
        ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 50, None),
        ApiPaxTypeAndQueueCount(VisaNational, Queues.NonEeaDesk, 50, None)),
      SplitSources.Historical, None, Percentage)

    val flight = FlightsWithSplits(Seq(ApiFlightWithSplits(arrival, Set(historicSplits), None)), Set())

    flightsWithSplits.offer(flight)

    val expectedLoads = Set(
      LoadMinute("T1", Queues.EeaDesk, 10, 5, SDate(scheduled).millisSinceEpoch),
      LoadMinute("T1", Queues.EeaDesk, 2.5, 1.25, SDate(scheduled).addMinutes(1).millisSinceEpoch),
      LoadMinute("T1", Queues.NonEeaDesk, 10, 10, SDate(scheduled).millisSinceEpoch),
      LoadMinute("T1", Queues.NonEeaDesk, 2.5, 2.5, SDate(scheduled).addMinutes(1).millisSinceEpoch)
    )

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

    val arrival = ArrivalGenerator.apiFlight(iata = "BA0001", schDt = scheduled, actPax = Option(75), tranPax = Option(50))
    val historicSplits = Splits(
      Set(
        ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 50, None),
        ApiPaxTypeAndQueueCount(VisaNational, Queues.NonEeaDesk, 50, None)),
      SplitSources.Historical, None, Percentage)

    val flight = FlightsWithSplits(Seq(ApiFlightWithSplits(arrival, Set(historicSplits), None)), Set())

    flightsWithSplits.offer(flight)

    val expectedLoads = Set(
      LoadMinute("T1", Queues.EeaDesk, 10, 5, SDate(scheduled).millisSinceEpoch),
      LoadMinute("T1", Queues.EeaDesk, 2.5, 1.25, SDate(scheduled).addMinutes(1).millisSinceEpoch),
      LoadMinute("T1", Queues.NonEeaDesk, 10, 10, SDate(scheduled).millisSinceEpoch),
      LoadMinute("T1", Queues.NonEeaDesk, 2.5, 2.5, SDate(scheduled).addMinutes(1).millisSinceEpoch)
    )

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

    val arrival = ArrivalGenerator.apiFlight(iata = "BA0001", schDt = scheduled, actPax = Option(25))
    val arrival2 = ArrivalGenerator.apiFlight(iata = "BA0002", schDt = scheduled2, actPax = Option(25))
    val historicSplits = Splits(Set(
      ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 50, None),
      ApiPaxTypeAndQueueCount(VisaNational, Queues.NonEeaDesk, 50, None)),
      SplitSources.Historical, None, Percentage)

    val flight = FlightsWithSplits(Seq(ApiFlightWithSplits(arrival, Set(historicSplits), None)), Set())
    val flight2 = FlightsWithSplits(Seq(ApiFlightWithSplits(arrival2, Set(historicSplits), None)), Set())

    flightsWithSplits.offer(flight)
    flightsWithSplits.offer(flight2)

    val expectedLoads = Set(
      LoadMinute("T1", Queues.EeaDesk, 2.5 + 10, 1.25 + 5, SDate(scheduled).addMinutes(1).millisSinceEpoch),
      LoadMinute("T1", Queues.EeaDesk, 0 + 2.5, 0 + 1.25, SDate(scheduled).addMinutes(2).millisSinceEpoch),
      LoadMinute("T1", Queues.NonEeaDesk, 2.5 + 10, 2.5 + 10, SDate(scheduled).addMinutes(1).millisSinceEpoch),
      LoadMinute("T1", Queues.NonEeaDesk, 0 + 2.5, 0 + 2.5, SDate(scheduled).addMinutes(2).millisSinceEpoch)
    )

    val result = probe.receiveN(2, 2 seconds).reverse.head match {
      case Loads(loadMinutes) => loadMinutes
    }

    result === expectedLoads
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

    val arrival = ArrivalGenerator.apiFlight(iata = "BA0001", schDt = scheduled, actPax = Option(25))
    val arrival2 = ArrivalGenerator.apiFlight(iata = "BA0002", schDt = scheduled2, actPax = Option(25))
    val historicSplits = Splits(Set(
        ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 50, None),
        ApiPaxTypeAndQueueCount(VisaNational, Queues.NonEeaDesk, 50, None)),
      SplitSources.Historical, None, Percentage)

    val flight1 = FlightsWithSplits(Seq(ApiFlightWithSplits(arrival, Set(historicSplits), None)), Set())
    val flight2 = FlightsWithSplits(Seq(ApiFlightWithSplits(arrival2, Set(historicSplits), None)), Set())
    val flight1Update = FlightsWithSplits(Seq(ApiFlightWithSplits(arrival.copy(PcpTime = arrival.PcpTime.map(_+ 60000)), Set(historicSplits), None)), Set())

    Await.ready(flightsWithSplits.offer(flight1), 1 second)
    Await.ready(flightsWithSplits.offer(flight2), 1 second)
    Await.ready(flightsWithSplits.offer(flight1Update), 1 second)

    val expectedLoads = Set(
      LoadMinute("T1", Queues.EeaDesk, 0, 0, SDate(scheduled).addMinutes(0).millisSinceEpoch),
      LoadMinute("T1", Queues.EeaDesk, 10 + 10, 5 + 5, SDate(scheduled).addMinutes(1).millisSinceEpoch),
      LoadMinute("T1", Queues.EeaDesk, 2.5 + 2.5, 1.25 + 1.25, SDate(scheduled).addMinutes(2).millisSinceEpoch),
      LoadMinute("T1", Queues.NonEeaDesk, 0, 0, SDate(scheduled).addMinutes(0).millisSinceEpoch),
      LoadMinute("T1", Queues.NonEeaDesk, 10 + 10, 10 + 10, SDate(scheduled).addMinutes(1).millisSinceEpoch),
      LoadMinute("T1", Queues.NonEeaDesk, 2.5 + 2.5, 2.5 + 2.5, SDate(scheduled).addMinutes(2).millisSinceEpoch)
    )

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

    val arrival = ArrivalGenerator.apiFlight(iata = "BA0001", schDt = scheduled, actPax = Option(25))
    val historicSplits = Splits(
      Set(
        ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 50, None),
        ApiPaxTypeAndQueueCount(VisaNational, Queues.NonEeaDesk, 50, None)),
      SplitSources.Historical, None, Percentage)

    val flight = FlightsWithSplits(Seq(ApiFlightWithSplits(arrival, Set(historicSplits), None)), Set())

    flightsWithSplits.offer(flight)

    val expectedLoads = Set(
      LoadMinute("T1", Queues.EeaDesk, 20, 15, SDate(scheduled).millisSinceEpoch),
      LoadMinute("T1", Queues.EeaDesk, 5, 3.75, SDate(scheduled).addMinutes(1).millisSinceEpoch)
    )

    val result = probe.receiveOne(2 seconds) match {
      case Loads(loadMinutes) => loadMinutes
    }

    result === expectedLoads
  }
}
