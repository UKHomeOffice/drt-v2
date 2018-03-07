package services.crunch

import akka.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source, SourceQueueWithComplete}
import akka.stream.{ClosedShape, OverflowStrategy}
import akka.testkit.TestProbe
import controllers.ArrivalGenerator
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.PaxTypes.{EeaMachineReadable, EeaNonMachineReadable}
import drt.shared.SplitRatiosNs.SplitSources
import drt.shared._
import services.SDate
import services.graphstages.Crunch.Loads
import services.graphstages.WorkloadGraphStage

import scala.concurrent.duration._


object TestableWorkloadStage {
  val oneDayMillis: Int = 60 * 60 * 24 * 1000

  def apply(testProbe: TestProbe,
            now: () => SDateLike,
            airportConfig: AirportConfig,
            workloadStart: (SDateLike) => SDateLike,
            workloadEnd: (SDateLike) => SDateLike,
            earliestAndLatestAffectedPcpTime: (Set[ApiFlightWithSplits], Set[ApiFlightWithSplits]) => Option[(SDateLike, SDateLike)]
           ): RunnableGraph[SourceQueueWithComplete[FlightsWithSplits]] = {
    def groupByCodeShares(flights: Seq[ApiFlightWithSplits]) = flights.map(f => (f, Set(f.apiFlight)))
    val workloadStage = new WorkloadGraphStage(
      optionalInitialFlights = None,
      airportConfig = airportConfig,
      natProcTimes = Map(),
      groupFlightsByCodeShares = groupByCodeShares,
      earliestAndLatestAffectedPcpTime = earliestAndLatestAffectedPcpTime,
      expireAfterMillis = oneDayMillis,
      now = now,
      warmUpMinutes = 0,
      useNationalityBasedProcessingTimes = false,
      workloadStartFromFirstPcp = workloadStart,
      workloadEndFromLastPcp = workloadEnd
    )

    val flightsWithSplitsSource = Source.queue[FlightsWithSplits](1, OverflowStrategy.backpressure)

    import akka.stream.scaladsl.GraphDSL.Implicits._

    val graph = GraphDSL.create(flightsWithSplitsSource.async) {

      implicit builder => (flights) =>
        val workload = builder.add(workloadStage.async)
        val sink = builder.add(Sink.actorRef(testProbe.ref, "complete"))

        flights.out ~> workload ~> sink

        ClosedShape
    }

    RunnableGraph.fromGraph(graph)
  }
}

class WorkloadStageSpec extends CrunchTestLike {
  "Given a flight with splits " +
    "When I ask for the workload " +
    "Then I should see the workload associated with the best splits for that flight" >> {

    val probe = TestProbe("workload")
    val scheduled = "2018-01-01T00:05"
    val workloadStart = (_: SDateLike) => SDate(scheduled)
    val workloadEnd = (_: SDateLike) => SDate(scheduled).addMinutes(30)
    val workloadWindow = (_: Set[ApiFlightWithSplits], _: Set[ApiFlightWithSplits]) => Option((workloadStart(SDate(scheduled)), workloadEnd(SDate(scheduled))))
    val flightsWithSplits = TestableWorkloadStage(probe, () => SDate(scheduled), airportConfig, workloadStart, workloadEnd, workloadWindow).run

    val arrival = ArrivalGenerator.apiFlight(iata = "BA0001", schDt = scheduled, actPax = 25)
    val historicSplits = ApiSplits(
      Set(
        ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 50.0, None),
        ApiPaxTypeAndQueueCount(EeaNonMachineReadable, Queues.EeaDesk, 50.0, None)),
      SplitSources.Historical, None, Percentage)

    val flight = FlightsWithSplits(Seq(ApiFlightWithSplits(arrival, Set(historicSplits), None)))

    flightsWithSplits.offer(flight)

    probe.fishForMessage(10 seconds) {
      case Loads(loadMinutes) =>
        println(s"loadMinutes: ${loadMinutes.toSeq.sortBy(_.minute)}")
        loadMinutes == Loads(Set())
    }

    true
  }
}