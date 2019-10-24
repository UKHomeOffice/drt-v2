package services.graphstages

import actors.acking.AckingReceiver.StreamCompleted
import akka.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source, SourceQueueWithComplete}
import akka.stream.{ClosedShape, OverflowStrategy}
import akka.testkit.TestProbe
import drt.shared.{Arrival, ArrivalsDiff, UniqueArrival}
import services.crunch.CrunchTestLike
import services.{PcpArrival, SDate}

import scala.collection.mutable
import scala.concurrent.duration._

object TestableArrivalsGraphStage {
  def apply(testProbe: TestProbe,
            arrivalsGraphStage: ArrivalsGraphStage
           ): RunnableGraph[
    (SourceQueueWithComplete[List[Arrival]],
      SourceQueueWithComplete[List[Arrival]],
      SourceQueueWithComplete[List[Arrival]],
      SourceQueueWithComplete[List[Arrival]])
  ] = {

    val liveArrivalsSource = Source.queue[List[Arrival]](1, OverflowStrategy.backpressure)
    val liveBaseArrivalsSource = Source.queue[List[Arrival]](1, OverflowStrategy.backpressure)
    val forecastArrivalsSource = Source.queue[List[Arrival]](1, OverflowStrategy.backpressure)
    val forecastBaseArrivalsSource = Source.queue[List[Arrival]](1, OverflowStrategy.backpressure)
    import akka.stream.scaladsl.GraphDSL.Implicits._

    val graph = GraphDSL.create(
      forecastBaseArrivalsSource.async,
      forecastArrivalsSource.async,
      liveBaseArrivalsSource.async,
      liveArrivalsSource.async
    )((_, _, _, _)) {

      implicit builder =>
        (forecastBase, forecast, liveBase, live) =>

          val arrivals = builder.add(arrivalsGraphStage.async)
          val sink = builder.add(Sink.actorRef(testProbe.ref, StreamCompleted))

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

class ArrivalsGraphStageLiveBaseArrivalsSpec extends CrunchTestLike {
  sequential
  isolated

  "Given a live arrival and a base live arrival I should get a merged arrival" >> {
    val probe = TestProbe("arrivals")
    val (_, _, liveBaseSource, liveSource) = TestableArrivalsGraphStage(probe, buildArrivalsGraphStage).run
    val liveEstimated = Option(SDate(2019, 10, 1, 16, 0).millisSinceEpoch)
    val liveBaseActual = Option(SDate(2019, 10, 1, 16, 0).millisSinceEpoch)

    liveSource.offer(List(arrival(estimated = liveBaseActual)))
    liveBaseSource.offer(List(arrival(actual = liveBaseActual)))

    probe.fishForMessage(10 seconds) {
      case ArrivalsDiff(toUpdate, _) =>
        toUpdate.exists {
          case (_, a) => a.Estimated == liveEstimated && a.Actual == liveBaseActual
        }
    }

    success
  }

  "Given a live arrival with a status of UNK and a base live arrival with a status of Scheduled " +
    "Then I should get the base live arrival status" >> {
    val probe = TestProbe("arrivals")
    val (_, _, liveBaseSource, liveSource) = TestableArrivalsGraphStage(probe, buildArrivalsGraphStage).run
    val liveBaseActual = Option(SDate(2019, 10, 1, 16, 0).millisSinceEpoch)

    liveSource.offer(List(arrival(estimated = liveBaseActual, status = "UNK")))
    liveBaseSource.offer(List(arrival(actual = liveBaseActual, status = "Scheduled")))

    probe.fishForMessage(2 seconds) {
      case ArrivalsDiff(toUpdate, _) =>
        toUpdate.exists {
          case (_, a) => a.Status == "Scheduled"
        }
    }

    success
  }

  "Given a base live arrival only we should not get anything back" >> {
    val probe = TestProbe("arrivals")
    val (_, _, liveBaseSource, _) = TestableArrivalsGraphStage(probe, buildArrivalsGraphStage).run
    val liveBaseActual = Option(SDate(2019, 10, 1, 16, 0).millisSinceEpoch)

    liveBaseSource.offer(List(arrival(actual = liveBaseActual)))

    val updateCounts: Seq[Int] = probe.receiveWhile(5 seconds) {
      case ad: ArrivalsDiff => ad.toUpdate.size
    }

    updateCounts.sum == 0
  }

  "Given a base live arrival with an estimated time and a base forecast arrival we should get the arrival with estimated time on it" >> {
    val probe = TestProbe("arrivals")
    val (forecastBaseSource, _, liveBaseSource, _) = TestableArrivalsGraphStage(probe, buildArrivalsGraphStage).run
    val baseLiveEstimated = Option(SDate(2019, 10, 1, 16, 0).millisSinceEpoch)

    liveBaseSource.offer(List(arrival(estimated = baseLiveEstimated)))
    forecastBaseSource.offer(List(arrival()))

    probe.fishForMessage(2 seconds) {
      case ArrivalsDiff(toUpdate, _) =>
        toUpdate.exists {
          case (_, a) => a.Estimated == baseLiveEstimated
        }
    }
    success
  }

  private def buildArrivalsGraphStage = {
    new ArrivalsGraphStage("",
      mutable.SortedMap[UniqueArrival, Arrival](),
      mutable.SortedMap[UniqueArrival, Arrival](),
      mutable.SortedMap[UniqueArrival, Arrival](),
      mutable.SortedMap[UniqueArrival, Arrival](),
      mutable.SortedMap[UniqueArrival, Arrival](),
      pcpTimeCalc,
      Set("T1"),
      Crunch.oneDayMillis,
      () => SDate(2019, 10, 1, 16, 0)
    )
  }

  def pcpTimeCalc(a: Arrival) = PcpArrival.pcpFrom(0, 0, _ => 0)(a)

  "Given a base live arrival with an estimated time and a port live arrival with only scheduled time " +
  "Then PCP time should be calculated from the base live arrival time." >> {
    val probe = TestProbe("arrivals")
    val (_, _, liveBaseSource, liveSource) = TestableArrivalsGraphStage(probe, buildArrivalsGraphStage).run
    val baseLiveEstimated = Option(SDate("2019-10-22T14:00:00Z").millisSinceEpoch)

    liveBaseSource.offer(List(arrival(estimated = baseLiveEstimated)))
    liveSource.offer(List(arrival()))

    probe.fishForMessage(2 seconds) {
      case ArrivalsDiff(toUpdate, _) =>
      toUpdate.exists {
          case (_, a) => a.PcpTime == baseLiveEstimated
        }
    }
    success
  }

  "Given a base live arrival with an estimated time and an ACL arrival" +
    " Then we should calculate PCP time from the base live arrival." >> {
    val probe = TestProbe("arrivals")
    val (aclSource, _, liveBaseSource, _) = TestableArrivalsGraphStage(probe, buildArrivalsGraphStage).run
    val baseLiveEstimated = Option(SDate("2019-10-22T14:00:00Z").millisSinceEpoch)

    liveBaseSource.offer(List(arrival(estimated = baseLiveEstimated)))
    aclSource.offer(List(arrival()))

    probe.fishForMessage(2 seconds) {
      case ArrivalsDiff(toUpdate, _) =>
        toUpdate.exists {
          case (_, a) => a.PcpTime == baseLiveEstimated
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
