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
import services.{SDate, TryRenjin}
import services.graphstages.Crunch.{LoadMinute, Loads}
import services.graphstages.CrunchLoadGraphStage

import scala.concurrent.duration._

object TestableCrunchLoadStage {
  val oneDayMillis: Int = 60 * 60 * 24 * 1000

  def apply(testProbe: TestProbe,
            now: () => SDateLike,
            airportConfig: AirportConfig,
            workloadStart: (SDateLike) => SDateLike,
            workloadEnd: (SDateLike) => SDateLike,
            earliestAndLatestAffectedPcpTime: (Set[ApiFlightWithSplits], Set[ApiFlightWithSplits]) => Option[(SDateLike, SDateLike)]
           ): RunnableGraph[SourceQueueWithComplete[Loads]] = {
    val crunchLoadStage = new CrunchLoadGraphStage(
      optionalInitialCrunchMinutes = None,
      airportConfig = airportConfig,
      expireAfterMillis = oneDayMillis,
      now = now,
      TryRenjin.crunch
    )

    val loadSource = Source.queue[Loads](1, OverflowStrategy.backpressure)

    import akka.stream.scaladsl.GraphDSL.Implicits._

    val graph = GraphDSL.create(loadSource.async) {

      implicit builder =>
        (load) =>
          val crunch = builder.add(crunchLoadStage.async)
          val sink = builder.add(Sink.actorRef(testProbe.ref, "complete"))

          load ~> crunch ~> sink

          ClosedShape
    }

    RunnableGraph.fromGraph(graph)
  }
}

class CrunchLoadStageSpec extends CrunchTestLike {
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

    val arrival = ArrivalGenerator.apiFlight(iata = "BA0001", schDt = scheduled, actPax = 25)
    val historicSplits = ApiSplits(
      Set(
        ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 50, None),
        ApiPaxTypeAndQueueCount(VisaNational, Queues.NonEeaDesk, 50, None)),
      SplitSources.Historical, None, Percentage)

    val flight = FlightsWithSplits(Seq(ApiFlightWithSplits(arrival, Set(historicSplits), None)))

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
}