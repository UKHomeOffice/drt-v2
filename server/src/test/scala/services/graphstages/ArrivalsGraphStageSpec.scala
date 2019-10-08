package services.graphstages

import akka.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source, SourceQueueWithComplete}
import akka.stream.{ClosedShape, OverflowStrategy}
import akka.testkit.TestProbe
import drt.shared.FlightsApi.Flights
import drt.shared.{Arrival, ArrivalsDiff, MilliDate, UniqueArrival}
import server.feeds.{ArrivalsFeedResponse, ArrivalsFeedSuccess}
import services.SDate
import services.crunch.CrunchTestLike

import scala.collection.mutable
import scala.concurrent.duration._

object TestableArrivalsGraphStage {
  def apply(testProbe: TestProbe,
            arrivalsGraphStage: ArrivalsGraphStage
           ): RunnableGraph[
    (SourceQueueWithComplete[ArrivalsFeedResponse],
      SourceQueueWithComplete[ArrivalsFeedResponse],
      SourceQueueWithComplete[ArrivalsFeedResponse],
      SourceQueueWithComplete[ArrivalsFeedResponse])
  ] = {

    val liveArrivalsSource = Source.queue[ArrivalsFeedResponse](1, OverflowStrategy.backpressure)
    val liveBaseArrivalsSource = Source.queue[ArrivalsFeedResponse](1, OverflowStrategy.backpressure)
    val forecastArrivalsSource = Source.queue[ArrivalsFeedResponse](1, OverflowStrategy.backpressure)
    val forecastBaseArrivalsSource = Source.queue[ArrivalsFeedResponse](1, OverflowStrategy.backpressure)
    import akka.stream.scaladsl.GraphDSL.Implicits._

    val graph = GraphDSL.create(
      liveArrivalsSource.async,
      liveBaseArrivalsSource.async,
      forecastArrivalsSource.async,
      forecastBaseArrivalsSource.async
    )((_, _, _, _)) {

      implicit builder =>
        (live, liveBase, forecast, forecastBase) =>

          val arrivals = builder.add(arrivalsGraphStage.async)
          val sink = builder.add(Sink.actorRef(testProbe.ref, "complete"))

          forecastBase ~> arrivals.in0
          forecast ~> arrivals.in1
          liveBase ~> arrivals.in2
          live ~> arrivals.in3
          arrivals.out ~> sink

          ClosedShape
    }

    RunnableGraph.fromGraph(graph)
  }
}

class ArrivalsGraphStageSpec extends CrunchTestLike {

  "Given a live arrival and a base live arrival I should get a merged arrival" >> {
    val probe = TestProbe("arrivals")
    val arrivalsGraphStage = new ArrivalsGraphStage("",
      mutable.SortedMap[UniqueArrival, Arrival](),
      mutable.SortedMap[UniqueArrival, Arrival](),
      mutable.SortedMap[UniqueArrival, Arrival](),
      mutable.SortedMap[UniqueArrival, Arrival](),
      mutable.SortedMap[UniqueArrival, Arrival](),
      a => MilliDate(a.Scheduled),
      Set("T1"),
      Crunch.oneDayMillis,
      () => SDate(2019, 10, 1, 16, 0)
    )
    val (liveSource, liveBaseSource, _, _) = TestableArrivalsGraphStage(probe, arrivalsGraphStage).run
    val liveEstimated = Option(SDate(2019, 10, 1, 16, 0).millisSinceEpoch)
    val liveBaseActual = Option(SDate(2019, 10, 1, 16, 0).millisSinceEpoch)

    liveSource.offer(ArrivalsFeedSuccess(Flights(List(arrival(estimated = liveBaseActual)))))
    liveBaseSource.offer(ArrivalsFeedSuccess(Flights(List(arrival(actual = liveBaseActual)))))

    probe.fishForMessage(10 seconds) {
      case ArrivalsDiff(toUpdate, _) =>
        toUpdate.exists {
          case (_, a) => a.Estimated == liveEstimated && a.Actual == liveBaseActual
        }
    }

    success
  }

  "Given a base live arrival only we should not get anything back" >> {
    val probe = TestProbe("arrivals")
    val arrivalsGraphStage = new ArrivalsGraphStage("",
      mutable.SortedMap[UniqueArrival, Arrival](),
      mutable.SortedMap[UniqueArrival, Arrival](),
      mutable.SortedMap[UniqueArrival, Arrival](),
      mutable.SortedMap[UniqueArrival, Arrival](),
      mutable.SortedMap[UniqueArrival, Arrival](),
      a => MilliDate(a.Scheduled),
      Set("T1"),
      Crunch.oneDayMillis,
      () => SDate(2019, 10, 1, 16, 0)
    )
    val (_, liveBaseSource, _, _) = TestableArrivalsGraphStage(probe, arrivalsGraphStage).run
    val liveBaseActual = Option(SDate(2019, 10, 1, 16, 0).millisSinceEpoch)

    liveBaseSource.offer(ArrivalsFeedSuccess(Flights(List(arrival(actual = liveBaseActual)))))

    probe.fishForMessage(10 seconds) {
      case a: ArrivalsDiff if a.toUpdate.isEmpty => true
    }
    success
  }

  "Given a base live arrival with an estimated time and a base forecast arrival we should get the arrival with estimated time on it" >> {
    val probe = TestProbe("arrivals")
    val arrivalsGraphStage = new ArrivalsGraphStage("",
      mutable.SortedMap[UniqueArrival, Arrival](),
      mutable.SortedMap[UniqueArrival, Arrival](),
      mutable.SortedMap[UniqueArrival, Arrival](),
      mutable.SortedMap[UniqueArrival, Arrival](),
      mutable.SortedMap[UniqueArrival, Arrival](),
      a => MilliDate(a.Scheduled),
      Set("T1"),
      Crunch.oneDayMillis,
      () => SDate(2019, 10, 1, 16, 0)
    )
    val (_, liveBaseSource, _, forecastBaseSource) = TestableArrivalsGraphStage(probe, arrivalsGraphStage).run
    val baseLiveEstimated = Option(SDate(2019, 10, 1, 16, 0).millisSinceEpoch)

    forecastBaseSource.offer(ArrivalsFeedSuccess(Flights(List(arrival()))))
    liveBaseSource.offer(ArrivalsFeedSuccess(Flights(List(arrival(estimated = baseLiveEstimated)))))

    probe.fishForMessage(10 seconds) {
      case ArrivalsDiff(toUpdate, _) =>
        toUpdate.exists {
          case (_, a) => a.Estimated == baseLiveEstimated
        }
    }
    success
  }

  def arrival(
               estimated: Option[Long] = None,
               actual: Option[Long] = None,
               estChox: Option[Long] = None,
               actChox: Option[Long] = None,
               gate: Option[String] = None,
               status: String = "test"
             ): Arrival =
    Arrival(
      None,
      status,
      estimated,
      actual,
      estChox,
      actChox,
      gate,
      None,
      None,
      None,
      None,
      None,
      None,
      None,
      "STN",
      "T1",
      "TST100",
      "TST100",
      "TST",
      SDate(2019,
        10,
        1,
        16,
        0
      ).millisSinceEpoch,
      None,
      Set()
    )
}
