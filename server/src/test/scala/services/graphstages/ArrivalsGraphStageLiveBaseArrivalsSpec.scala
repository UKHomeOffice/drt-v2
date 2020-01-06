package services.graphstages

import actors.acking.AckingReceiver.StreamCompleted
import akka.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source, SourceQueueWithComplete}
import akka.stream.{ClosedShape, OverflowStrategy}
import akka.testkit.TestProbe
import drt.shared.Terminals.T1
import drt.shared.{Arrival, ArrivalStatus, ArrivalsDiff, PortCode, UniqueArrival}
import services.arrivals.ArrivalDataSanitiser
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

  val scheduled = SDate(2019, 10, 1, 16, 0)

  "Given a live arrival and a base live arrival I should get a merged arrival" >> {
    val probe = TestProbe("arrivals")
    val (_, _, liveBaseSource, liveSource) = TestableArrivalsGraphStage(probe, buildArrivalsGraphStage).run
    val liveEstimated = Option(scheduled.millisSinceEpoch)
    val liveBaseActual = Option(scheduled.millisSinceEpoch)

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
    val liveBaseActual = Option(scheduled.millisSinceEpoch)

    liveSource.offer(List(arrival(estimated = liveBaseActual, status = ArrivalStatus("UNK"))))
    liveBaseSource.offer(List(arrival(actual = liveBaseActual, status = ArrivalStatus("Scheduled"))))

    probe.fishForMessage(2 seconds) {
      case ArrivalsDiff(toUpdate, _) =>
        toUpdate.exists {
          case (_, a) => a.Status.description == "Scheduled"
        }
    }

    success
  }


  "Given a base live arrival with an estimated time that is outside the defined acceptable data threshold " +
    "Then the estimated times should be ignored" >> {
    val probe = TestProbe("arrivals")
    val (forecastBaseSource, _, liveBaseSource, _) = TestableArrivalsGraphStage(
      probe, buildArrivalsGraphStageWithSanitiser(ArrivalDataSanitiser(Option(4), None))
    ).run

    forecastBaseSource.offer(List(arrival()))
    liveBaseSource.offer(List(arrival(estimated = Option(scheduled.addHours(5).millisSinceEpoch))))

    probe.fishForMessage(2 seconds) {
      case ArrivalsDiff(toUpdate, _) =>
        toUpdate.exists {
          case (_, a) =>
            a.Estimated.isEmpty
        }
    }

    success
  }

  "Given a port live arrival with an estimated time that is outside the defined acceptable data threshold " +
    "Then the estimated times should still be used" >> {
    val probe = TestProbe("arrivals")
    val (forecastBaseSource, _, _, liveSource) = TestableArrivalsGraphStage(
      probe, buildArrivalsGraphStageWithSanitiser(ArrivalDataSanitiser(Option(4), None))
    ).run

    forecastBaseSource.offer(List(arrival()))
    val lateEstimatedTime = scheduled.addHours(5)
    liveSource.offer(List(arrival(estimated = Option(lateEstimatedTime.millisSinceEpoch))))

    probe.fishForMessage(2 seconds) {
      case ArrivalsDiff(toUpdate, _) =>
        toUpdate.exists {
          case (_, a) =>

            a.Estimated == Option(lateEstimatedTime.millisSinceEpoch)
        }
    }

    success
  }

  "Given a port live arrival with an estimated time and no est chox " +
    "And a Base Live arrival with an estimated chox that is before the port live est time " +
    "Then the EstChox field should be ignored" >> {
    val probe = TestProbe("arrivals")
    val (forecastBaseSource, _, liveBaseSource, liveSource) = TestableArrivalsGraphStage(
      probe, buildArrivalsGraphStageWithSanitiser(ArrivalDataSanitiser(Option(4), None))
    ).run

    forecastBaseSource.offer(List(arrival()))
    val estTime = scheduled.addHours(5)
    liveSource.offer(List(arrival(estimated = Option(estTime.millisSinceEpoch))))
    liveBaseSource.offer(List(arrival(estChox = Option(estTime.addHours(-1).millisSinceEpoch))))

    probe.fishForMessage(2 seconds) {
      case ArrivalsDiff(toUpdate, _) =>
        toUpdate.exists {
          case (_, a) =>
            a.EstimatedChox.isEmpty
        }
    }

    success
  }

  "Given a port live arrival with an estimated time and no est chox " +
    "And a Base Live arrival with an estimated chox that is the same as the live est time " +
    "Then the EstChox field should be ignored" >> {
    val probe = TestProbe("arrivals")
    val (forecastBaseSource, _, liveBaseSource, liveSource) = TestableArrivalsGraphStage(
      probe, buildArrivalsGraphStageWithSanitiser(ArrivalDataSanitiser(Option(4), None))
    ).run

    forecastBaseSource.offer(List(arrival()))
    val estTime = scheduled.addHours(5)
    liveSource.offer(List(arrival(estimated = Option(estTime.millisSinceEpoch))))
    liveBaseSource.offer(List(arrival(estChox = Option(estTime.millisSinceEpoch))))

    probe.fishForMessage(2 seconds) {
      case ArrivalsDiff(toUpdate, _) =>
        toUpdate.exists {
          case (_, a) =>
            a.EstimatedChox.isEmpty
        }
    }

    success
  }

  "Given a base live arrival only we should not get anything back" >> {
    val probe = TestProbe("arrivals")
    val (_, _, liveBaseSource, _) = TestableArrivalsGraphStage(probe, buildArrivalsGraphStage).run
    val liveBaseActual = Option(scheduled.millisSinceEpoch)

    liveBaseSource.offer(List(arrival(actual = liveBaseActual)))

    val updateCounts: Seq[Int] = probe.receiveWhile(5 seconds) {
      case ad: ArrivalsDiff => ad.toUpdate.size
    }

    updateCounts.sum == 0
  }

  "Given a base live arrival with an estimated time and a base forecast arrival we should get the arrival with estimated time on it" >> {
    val probe = TestProbe("arrivals")
    val (forecastBaseSource, _, liveBaseSource, _) = TestableArrivalsGraphStage(probe, buildArrivalsGraphStage).run
    val baseLiveEstimated = Option(scheduled.millisSinceEpoch)

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

  private def buildArrivalsGraphStageWithSanitiser(sanitiser: ArrivalDataSanitiser) = new ArrivalsGraphStage("",
    mutable.SortedMap[UniqueArrival, Arrival](),
    mutable.SortedMap[UniqueArrival, Arrival](),
    mutable.SortedMap[UniqueArrival, Arrival](),
    mutable.SortedMap[UniqueArrival, Arrival](),
    mutable.SortedMap[UniqueArrival, Arrival](),
    pcpTimeCalc,
    Set(T1),
    sanitiser,
    Crunch.oneDayMillis,
    () => scheduled
  )

  private def buildArrivalsGraphStage = new ArrivalsGraphStage("",
    mutable.SortedMap[UniqueArrival, Arrival](),
    mutable.SortedMap[UniqueArrival, Arrival](),
    mutable.SortedMap[UniqueArrival, Arrival](),
    mutable.SortedMap[UniqueArrival, Arrival](),
    mutable.SortedMap[UniqueArrival, Arrival](),
    pcpTimeCalc,
    Set(T1),
    ArrivalDataSanitiser(None, None),
    Crunch.oneDayMillis,
    () => scheduled
  )

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
               status: ArrivalStatus = ArrivalStatus("test")
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
      PortCode("STN"),
      T1,
      "TST100",
      "TST100",
      PortCode("TST"),
      scheduled.millisSinceEpoch,
      None,
      Set()
    )
}
