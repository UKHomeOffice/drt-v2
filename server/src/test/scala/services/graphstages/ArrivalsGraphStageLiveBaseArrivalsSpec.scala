package services.graphstages

import actors.acking.AckingReceiver.StreamCompleted
import akka.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source, SourceQueueWithComplete}
import akka.stream.{ClosedShape, OverflowStrategy}
import akka.testkit.{TestKit, TestProbe}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.MilliTimes.oneDayMillis
import drt.shared.Terminals.T1
import drt.shared._
import drt.shared.api.Arrival
import org.specs2.specification.AfterEach
import services.arrivals.{ArrivalDataSanitiser, ArrivalsAdjustmentsNoop}
import services.crunch.CrunchTestLike
import services.{PcpArrival, SDate}

import scala.collection.immutable.SortedMap
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

class ArrivalsGraphStageLiveBaseArrivalsSpec extends CrunchTestLike with AfterEach {
  sequential
  isolated

  val scheduled: SDateLike = SDate(2019, 10, 1, 16, 0)

  override def after: Unit = TestKit.shutdownActorSystem(system)

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

  "Given a live arrival with a scheduled arrival time and a base live arrival within 60 minutes scheduled arrival time" +
    "Then they should get merged and have scheduled Departure" >> {
    val probe = TestProbe("arrivals")
    val (_, _, liveBaseSource, liveSource) = TestableArrivalsGraphStage(probe, buildArrivalsGraphStage).run

    val scheduledDeparture = scheduled.addHours(-4).millisSinceEpoch
    liveSource.offer(List(arrival()))

    probe.fishForMessage(2 seconds) {
      case ArrivalsDiff(toUpdate, _) =>
        toUpdate.exists {
          case (_, _) => true
        }
    }

    liveBaseSource.offer(List(arrival(scheduledDate = scheduled.addMinutes(59).millisSinceEpoch, scheduledDepartureDate = Some(scheduledDeparture))))

    probe.fishForMessage(2 seconds) {
      case ArrivalsDiff(toUpdate, _) =>
        toUpdate.exists {
          case (_, a) =>
            a.ScheduledDeparture.get == scheduledDeparture &&
            a.Scheduled == scheduled.millisSinceEpoch
        }
    }

    success
  }

  "Given a forecast Base arrival with a scheduled arrival time and a base live arrival with in 60 minutes scheduled arrival time " +
    "Then they should get merged and have scheduled Departure" >> {
    val probe = TestProbe("arrivals")
    val (forecastBaseArrivalsSource, _, liveBaseSource, _) = TestableArrivalsGraphStage(probe, buildArrivalsGraphStage).run

    val scheduledDeparture = scheduled.addHours(-4).millisSinceEpoch
    forecastBaseArrivalsSource.offer(List(arrival()))

    probe.fishForMessage(2 seconds) {
      case ArrivalsDiff(toUpdate, _) =>
        toUpdate.exists {
          case (_, _) => true
        }
    }

    liveBaseSource.offer(List(arrival(scheduledDate = scheduled.addMinutes(59).millisSinceEpoch, scheduledDepartureDate = Some(scheduledDeparture))))

    probe.fishForMessage(2 seconds) {
      case ArrivalsDiff(toUpdate, _) =>
        toUpdate.exists {
          case (_, a) =>
            a.ScheduledDeparture.get == scheduledDeparture &&
            a.Scheduled == scheduled.millisSinceEpoch
        }
    }

    success
  }

  "Given a forecast arrival with a scheduled arrival time and a live arrival with in 60 minutes scheduled arrival time" +
    "Then they should not merged and scheduled Departure not present" >> {
    val probe = TestProbe("arrivals")
    val (forecastBaseArrivalsSource, _, _, liveSource) = TestableArrivalsGraphStage(probe, buildArrivalsGraphStage).run

    val scheduledDeparture = scheduled.addHours(-4).millisSinceEpoch
    forecastBaseArrivalsSource.offer(List(arrival(scheduledDate = scheduled.addMinutes(59).millisSinceEpoch, scheduledDepartureDate = Some(scheduledDeparture))))

    probe.fishForMessage(2 seconds) {
      case ArrivalsDiff(toUpdate, _) =>
        toUpdate.exists {
          case (_, _) => true
        }
    }

    liveSource.offer(List(arrival()))

    probe.fishForMessage(2 seconds) {
      case ArrivalsDiff(toUpdate, _) =>
        toUpdate.exists {
          case (_, a) => a.ScheduledDeparture == None
        }
    }

    success
  }


  "Given a forecast base arrival with a scheduled arrival time and a live  base arrival with in 60 minutes scheduled arrival time and forecast arrival with scheduled same as forecast base" +
    "Then they should  merged and scheduled Departure present and actual pax count should exists" >> {
    val probe = TestProbe("arrivals")
    val (forecastBaseArrivalsSource, forecastArrivalSource, liveBaseSource, _) = TestableArrivalsGraphStage(probe, buildArrivalsGraphStage).run

    val scheduledDeparture = scheduled.addHours(-4).millisSinceEpoch

    forecastBaseArrivalsSource.offer(List(arrival()))

    probe.fishForMessage(2 seconds) {
      case ArrivalsDiff(toUpdate, _) =>
        toUpdate.exists {
          case (_, _) => true
        }
    }

    liveBaseSource.offer(List(arrival(scheduledDate = scheduled.addMinutes(59).millisSinceEpoch, scheduledDepartureDate = Some(scheduledDeparture))))

    probe.fishForMessage(2 seconds) {
      case ArrivalsDiff(toUpdate, _) =>
        toUpdate.exists {
          case (_, a) => a.ScheduledDeparture.get == scheduledDeparture
            a.Scheduled == scheduled.millisSinceEpoch
        }
    }

    forecastArrivalSource.offer(List(arrival(actPax=Some(90))))

    probe.fishForMessage(2 seconds) {
      case ArrivalsDiff(toUpdate, _) =>
        toUpdate.exists {
          case (_, a) => a.ScheduledDeparture.get == scheduledDeparture && a.ActPax.get == 90
        }
    }

    success
  }

  "Given a live arrival with a scheduled arrival time and a live base arrival with in 60 minutes scheduled arrival time and forecast arrival with scheduled same as forecast base" +
    "Then they should merged and scheduled Departure present" >> {
    val probe = TestProbe("arrivals")
    val (_, forecastArrivalSource, liveBaseSource, liveArrivalSource) = TestableArrivalsGraphStage(probe, buildArrivalsGraphStage).run

    val scheduledDeparture = scheduled.addHours(-4).millisSinceEpoch

    liveArrivalSource.offer(List(arrival()))

    probe.fishForMessage(2 seconds) {
      case ArrivalsDiff(toUpdate, _) =>
        toUpdate.exists {
          case (_, _) => true
        }
    }

    liveBaseSource.offer(List(arrival(scheduledDate = scheduled.addMinutes(59).millisSinceEpoch, scheduledDepartureDate = Some(scheduledDeparture))))

    probe.fishForMessage(2 seconds) {
      case ArrivalsDiff(toUpdate, _) =>
        toUpdate.exists {
          case (_, a) => a.ScheduledDeparture.get == scheduledDeparture &&
            a.Scheduled == scheduled.millisSinceEpoch
        }
    }

    forecastArrivalSource.offer(List(arrival(actPax=Some(90))))

    probe.fishForMessage(2 seconds) {
      case ArrivalsDiff(toUpdate, _) =>
        toUpdate.exists {
          case (_, a) => a.ScheduledDeparture.get == scheduledDeparture &&
            a.FeedSources.contains(ForecastFeedSource) &&
            a.Scheduled == scheduled.millisSinceEpoch
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

  private def buildArrivalsGraphStageWithSanitiser(sanitiser: ArrivalDataSanitiser) = new ArrivalsGraphStage(
    "",
    SortedMap[UniqueArrival, Arrival](),
    SortedMap[UniqueArrival, Arrival](),
    SortedMap[UniqueArrival, Arrival](),
    SortedMap[UniqueArrival, Arrival](),
    SortedMap[UniqueArrival, Arrival](),
    pcpTimeCalc,
    Set(T1),
    sanitiser,
    ArrivalsAdjustmentsNoop,
    oneDayMillis,
    () => scheduled
  )

  private def buildArrivalsGraphStage = new ArrivalsGraphStage(
    "",
    SortedMap[UniqueArrival, Arrival](),
    SortedMap[UniqueArrival, Arrival](),
    SortedMap[UniqueArrival, Arrival](),
    SortedMap[UniqueArrival, Arrival](),
    SortedMap[UniqueArrival, Arrival](),
    pcpTimeCalc,
    Set(T1),
    ArrivalDataSanitiser(None, None),
    ArrivalsAdjustmentsNoop,
    oneDayMillis,
    () => scheduled
  )

  def pcpTimeCalc(a: Arrival): MilliDate = PcpArrival.pcpFrom(0, 0, _ => 0)(a)


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
               status: ArrivalStatus = ArrivalStatus("test"),
               scheduledDate: MillisSinceEpoch = scheduled.millisSinceEpoch,
               scheduledDepartureDate: Option[MillisSinceEpoch] = None,
               actPax :Option[Int] = None
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
      actPax,
      None,
      None,
      None,
      PortCode("STN"),
      T1,
      "TST100",
      "TST100",
      PortCode("TST"),
      scheduledDate,
      None,
      Set(),
      None,
      None,
      scheduledDepartureDate
    )
}
