package services.graphstages

import actors.acking.AckingReceiver.StreamCompleted
import akka.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source, SourceQueueWithComplete}
import akka.stream.{ClosedShape, OverflowStrategy}
import akka.testkit.{TestKit, TestProbe}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.MilliTimes.oneDayMillis
import drt.shared._
import drt.shared.api.Arrival
import org.specs2.specification.AfterEach
import services.arrivals.{ArrivalDataSanitiser, ArrivalsAdjustmentsNoop}
import services.crunch.CrunchTestLike
import services.{PcpArrival, SDate}
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.ports.{ForecastFeedSource, PortCode}
import uk.gov.homeoffice.drt.redlist.{RedListUpdateCommand, RedListUpdates}

import scala.collection.immutable.SortedMap
import scala.concurrent.duration._


object TestableArrivalsGraphStage {
  def apply(testProbe: TestProbe,
            arrivalsGraphStage: ArrivalsGraphStage
           ): RunnableGraph[
    (SourceQueueWithComplete[List[Arrival]],
      SourceQueueWithComplete[List[Arrival]],
      SourceQueueWithComplete[List[Arrival]],
      SourceQueueWithComplete[List[Arrival]],
      SourceQueueWithComplete[List[RedListUpdateCommand]])
    ] = {

    val liveArrivalsSource = Source.queue[List[Arrival]](1, OverflowStrategy.backpressure)
    val liveBaseArrivalsSource = Source.queue[List[Arrival]](1, OverflowStrategy.backpressure)
    val forecastArrivalsSource = Source.queue[List[Arrival]](1, OverflowStrategy.backpressure)
    val forecastBaseArrivalsSource = Source.queue[List[Arrival]](1, OverflowStrategy.backpressure)
    val redListSource = Source.queue[List[RedListUpdateCommand]](1, OverflowStrategy.backpressure)
    import akka.stream.scaladsl.GraphDSL.Implicits._

    val graph = GraphDSL.create(
      forecastBaseArrivalsSource.async,
      forecastArrivalsSource.async,
      liveBaseArrivalsSource.async,
      liveArrivalsSource.async,
      redListSource.async,
    )((_, _, _, _, _)) {

      implicit builder =>
        (forecastBase, forecast, liveBase, live, redList) =>

          val arrivals = builder.add(arrivalsGraphStage.async)
          val sink = builder.add(Sink.actorRef(testProbe.ref, StreamCompleted))

          forecastBase ~> arrivals.in0
          forecast ~> arrivals.in1
          liveBase ~> arrivals.in2
          live ~> arrivals.in3
          redList ~> arrivals.in4
          arrivals.out ~> sink

          ClosedShape
    }

    RunnableGraph.fromGraph(graph)
  }

  def buildArrivalsGraphStage(pcpCalcFn: (Arrival, RedListUpdates) => MilliDate,
                              now: SDateLike,
                              sanitiser: ArrivalDataSanitiser = ArrivalDataSanitiser(None, None)
                             ) = new ArrivalsGraphStage(
    name = "",
    initialRedListUpdates = RedListUpdates.empty,
    initialForecastBaseArrivals = SortedMap[UniqueArrival, Arrival](),
    initialForecastArrivals = SortedMap[UniqueArrival, Arrival](),
    initialLiveBaseArrivals = SortedMap[UniqueArrival, Arrival](),
    initialLiveArrivals = SortedMap[UniqueArrival, Arrival](),
    initialMergedArrivals = SortedMap[UniqueArrival, Arrival](),
    pcpArrivalTime = pcpCalcFn,
    validPortTerminals = Set(T1),
    arrivalDataSanitiser = sanitiser,
    arrivalsAdjustments = ArrivalsAdjustmentsNoop,
    expireAfterMillis = oneDayMillis,
    now = () => now
  )
}

class ArrivalsGraphStageLiveBaseArrivalsSpec extends CrunchTestLike with AfterEach {
  sequential
  isolated

  val scheduled: SDateLike = SDate(2019, 10, 1, 16, 0)

  override def after: Unit = TestKit.shutdownActorSystem(system)

  val probe: TestProbe = TestProbe("arrivals")

  private def offerAndCheck(source: SourceQueueWithComplete[List[Arrival]], arrivalsToOffer: List[Arrival], checkArrival: Arrival => Boolean) = {
    source.offer(arrivalsToOffer)

    probe.fishForMessage(3.seconds) {
      case ArrivalsDiff(toUpdate, _) =>
        toUpdate.exists {
          case (_, a) => checkArrival(a)
        }
    }
  }

  "Given a live arrival and a base live arrival I should get a merged arrival" >> {
    val (_, _, liveBaseSource, liveSource, _) = TestableArrivalsGraphStage(probe, TestableArrivalsGraphStage.buildArrivalsGraphStage(pcpTimeCalc, scheduled)).run
    val liveEstimated = Option(scheduled.millisSinceEpoch)
    val liveBaseActual = Option(scheduled.millisSinceEpoch)

    liveSource.offer(List(arrival(estimated = liveBaseActual)))
    offerAndCheck(liveBaseSource, List(arrival(actual = liveBaseActual)),
      (a: Arrival) => a.Estimated == liveEstimated && a.Actual == liveBaseActual)

    success
  }

  "Given a live arrival with a status of UNK and a base live arrival with a status of Scheduled " +
    "Then I should get the base live arrival status" >> {
    val (_, _, liveBaseSource, liveSource, _) = TestableArrivalsGraphStage(probe, TestableArrivalsGraphStage.buildArrivalsGraphStage(pcpTimeCalc, scheduled)).run
    val liveBaseActual = Option(scheduled.millisSinceEpoch)

    liveSource.offer(List(arrival(estimated = liveBaseActual, status = ArrivalStatus("UNK"))))

    offerAndCheck(liveBaseSource, List(arrival(actual = liveBaseActual, status = ArrivalStatus("Scheduled"))),
      (a: Arrival) => a.Status.description == "Scheduled")

    success
  }

  "Given a live arrival with a scheduled arrival time and a base live arrival within 60 minutes scheduled arrival time" +
    "Then they should get merged and have scheduled Departure" >> {
    val (_, _, liveBaseSource, liveSource, _) = TestableArrivalsGraphStage(probe, TestableArrivalsGraphStage.buildArrivalsGraphStage(pcpTimeCalc, scheduled)).run

    val scheduledDeparture = scheduled.addHours(-4).millisSinceEpoch
    offerAndCheck(liveSource, List(arrival()), (_: Arrival) => true)

    val ciriumArrivals = List(arrival(scheduledDate = scheduled.addMinutes(59).millisSinceEpoch, scheduledDepartureDate = Some(scheduledDeparture)))
    offerAndCheck(liveBaseSource, ciriumArrivals,
      (a: Arrival) => a.ScheduledDeparture.get == scheduledDeparture && a.Scheduled == scheduled.millisSinceEpoch)

    success
  }

  "Given a forecast Base arrival with a scheduled arrival time and a base live arrival with in 60 minutes scheduled arrival time " +
    "Then they should get merged and have scheduled Departure" >> {
    val (forecastBaseArrivalsSource, _, liveBaseSource, _, _) = TestableArrivalsGraphStage(probe, TestableArrivalsGraphStage.buildArrivalsGraphStage(pcpTimeCalc, scheduled)).run

    val scheduledDeparture = scheduled.addHours(-4).millisSinceEpoch

    offerAndCheck(forecastBaseArrivalsSource, List(arrival()), (_: Arrival) => true)

    val ciriumArrivals = List(arrival(scheduledDate = scheduled.addMinutes(59).millisSinceEpoch, scheduledDepartureDate = Some(scheduledDeparture)))
    offerAndCheck(liveBaseSource, ciriumArrivals,
      (a: Arrival) => a.ScheduledDeparture.get == scheduledDeparture && a.Scheduled == scheduled.millisSinceEpoch)

    success
  }

  "Given a forecast arrival with a scheduled arrival time and a live arrival with in 60 minutes scheduled arrival time" +
    "Then they should not merged and scheduled Departure not present" >> {
    val (forecastBaseArrivalsSource, _, _, liveSource, _) = TestableArrivalsGraphStage(probe, TestableArrivalsGraphStage.buildArrivalsGraphStage(pcpTimeCalc, scheduled)).run

    val scheduledDeparture = scheduled.addHours(-4).millisSinceEpoch

    val aclArrivals = List(arrival(scheduledDate = scheduled.addMinutes(59).millisSinceEpoch, scheduledDepartureDate = Some(scheduledDeparture)))
    offerAndCheck(forecastBaseArrivalsSource, aclArrivals, (_: Arrival) => true)

    offerAndCheck(liveSource, List(arrival()), (a: Arrival) => a.ScheduledDeparture.isEmpty)

    success
  }

  "Given a forecast base arrival with a scheduled arrival time and a live base arrival with in 60 minutes scheduled arrival time and forecast arrival with scheduled same as forecast base" +
    "Then they should merged and scheduled Departure present and actual pax count should exists" >> {
    val (forecastBaseArrivalsSource, forecastArrivalSource, liveBaseSource, _, _) = TestableArrivalsGraphStage(probe, TestableArrivalsGraphStage.buildArrivalsGraphStage(pcpTimeCalc, scheduled)).run

    val scheduledDeparture = scheduled.addHours(-4).millisSinceEpoch

    offerAndCheck(forecastBaseArrivalsSource, List(arrival()), (_: Arrival) => true)

    val ciriumArrivals = List(arrival(scheduledDate = scheduled.addMinutes(59).millisSinceEpoch, scheduledDepartureDate = Some(scheduledDeparture)))
    offerAndCheck(liveBaseSource, ciriumArrivals,
      (a: Arrival) => a.ScheduledDeparture.get == scheduledDeparture && a.Scheduled == scheduled.millisSinceEpoch)

    offerAndCheck(forecastArrivalSource, List(arrival(actPax=Some(90))),
      (a: Arrival) => a.ScheduledDeparture.get == scheduledDeparture && a.ActPax.get == 90)

    success
  }

  "Given a live arrival with a scheduled arrival time and a live base arrival with in 60 minutes scheduled arrival time and forecast arrival with scheduled same as forecast base" +
    "Then they should merged and scheduled Departure present" >> {
    val (_, forecastArrivalSource, liveBaseSource, liveArrivalSource, _) = TestableArrivalsGraphStage(probe, TestableArrivalsGraphStage.buildArrivalsGraphStage(pcpTimeCalc, scheduled)).run

    val scheduledDeparture = scheduled.addHours(-4).millisSinceEpoch

    offerAndCheck(liveArrivalSource, List(arrival()), (_: Arrival) => true)

    val ciriumArrivals = List(arrival(scheduledDate = scheduled.addMinutes(59).millisSinceEpoch, scheduledDepartureDate = Some(scheduledDeparture)))
    offerAndCheck(liveBaseSource, ciriumArrivals,
      (a: Arrival) => a.ScheduledDeparture.get == scheduledDeparture && a.Scheduled == scheduled.millisSinceEpoch)

    offerAndCheck(forecastArrivalSource, List(arrival(actPax=Some(90))),
      (a: Arrival) => a.ScheduledDeparture.get == scheduledDeparture &&
        a.FeedSources.contains(ForecastFeedSource) &&
        a.Scheduled == scheduled.millisSinceEpoch)

    success
  }

  "Given a base live arrival with an estimated time that is outside the defined acceptable data threshold " +
    "Then the estimated times should be ignored" >> {
    val (forecastBaseSource, _, liveBaseSource, _, _) = TestableArrivalsGraphStage(
      probe, TestableArrivalsGraphStage.buildArrivalsGraphStage(pcpTimeCalc, scheduled, ArrivalDataSanitiser(Option(4), None))
    ).run

    forecastBaseSource.offer(List(arrival()))
    val ciriumArrivals = List(arrival(estimated = Option(scheduled.addHours(5).millisSinceEpoch)))
    offerAndCheck(liveBaseSource, ciriumArrivals, (a: Arrival) => a.Estimated.isEmpty)

    success
  }

  "Given a port live arrival with an estimated time that is outside the defined acceptable data threshold " +
    "Then the estimated times should still be used" >> {
    val (forecastBaseSource, _, _, liveSource, _) = TestableArrivalsGraphStage(
      probe, TestableArrivalsGraphStage.buildArrivalsGraphStage(pcpTimeCalc, scheduled, ArrivalDataSanitiser(Option(4), None))
    ).run

    forecastBaseSource.offer(List(arrival()))
    val lateEstimatedTime = scheduled.addHours(5)
    val liveArrivals = List(arrival(estimated = Option(lateEstimatedTime.millisSinceEpoch)))

    offerAndCheck(liveSource, liveArrivals, (a: Arrival) => a.Estimated == Option(lateEstimatedTime.millisSinceEpoch))

    success
  }

  "Given a port live arrival with an estimated time and no est chox " +
    "And a Base Live arrival with an estimated chox that is before the port live est time " +
    "Then the EstChox field should be ignored" >> {
    val (forecastBaseSource, _, liveBaseSource, liveSource, _) = TestableArrivalsGraphStage(
      probe, TestableArrivalsGraphStage.buildArrivalsGraphStage(pcpTimeCalc, scheduled, ArrivalDataSanitiser(Option(4), None))
    ).run

    forecastBaseSource.offer(List(arrival()))
    val estTime = scheduled.addHours(5)
    liveSource.offer(List(arrival(estimated = Option(estTime.millisSinceEpoch))))
    val ciriumArrivals = List(arrival(estChox = Option(estTime.addHours(-1).millisSinceEpoch)))

    offerAndCheck(liveBaseSource, ciriumArrivals, (a: Arrival) => a.EstimatedChox.isEmpty)

    success
  }

  "Given a port live arrival with an estimated time and no est chox " +
    "And a Base Live arrival with an estimated chox that is the same as the live est time " +
    "Then the EstChox field should be ignored" >> {
    val (forecastBaseSource, _, liveBaseSource, liveSource, _) = TestableArrivalsGraphStage(
      probe, TestableArrivalsGraphStage.buildArrivalsGraphStage(pcpTimeCalc, scheduled, ArrivalDataSanitiser(Option(4), None))
    ).run

    forecastBaseSource.offer(List(arrival()))
    val estTime = scheduled.addHours(5)
    liveSource.offer(List(arrival(estimated = Option(estTime.millisSinceEpoch))))
    val ciriumArrivals = List(arrival(estChox = Option(estTime.millisSinceEpoch)))

    offerAndCheck(liveBaseSource, ciriumArrivals, (a: Arrival) => a.EstimatedChox.isEmpty)

    success
  }

  "Given a base live arrival only we should not get anything back" >> {
    val (_, _, liveBaseSource, _, _) = TestableArrivalsGraphStage(probe, TestableArrivalsGraphStage.buildArrivalsGraphStage(pcpTimeCalc, scheduled)).run
    val liveBaseActual = Option(scheduled.millisSinceEpoch)

    liveBaseSource.offer(List(arrival(actual = liveBaseActual)))

    val updateCounts: Seq[Int] = probe.receiveWhile(5.seconds) {
      case ad: ArrivalsDiff => ad.toUpdate.size
    }

    updateCounts.sum == 0
  }

  "Given a base live arrival with an estimated time and a base forecast arrival we should get the arrival with estimated time on it" >> {
    val (forecastBaseSource, _, liveBaseSource, _, _) = TestableArrivalsGraphStage(probe, TestableArrivalsGraphStage.buildArrivalsGraphStage(pcpTimeCalc, scheduled)).run
    val baseLiveEstimated = Option(scheduled.millisSinceEpoch)

    liveBaseSource.offer(List(arrival(estimated = baseLiveEstimated)))

    offerAndCheck(forecastBaseSource, List(arrival()), (a: Arrival) => a.Estimated == baseLiveEstimated)

    success
  }

  def pcpTimeCalc(a: Arrival, r: RedListUpdates): MilliDate = PcpArrival.pcpFrom(0, 0, (_, _) => 0)(a, r)

  "Given a base live arrival with an estimated time and a port live arrival with only scheduled time " +
    "Then PCP time should be calculated from the base live arrival time." >> {
    val (_, _, liveBaseSource, liveSource, _) = TestableArrivalsGraphStage(probe, TestableArrivalsGraphStage.buildArrivalsGraphStage(pcpTimeCalc, scheduled)).run
    val baseLiveEstimated = Option(SDate("2019-10-22T14:00:00Z").millisSinceEpoch)

    liveBaseSource.offer(List(arrival(estimated = baseLiveEstimated)))

    offerAndCheck(liveSource, List(arrival()), (a: Arrival) => a.PcpTime == baseLiveEstimated)

    success
  }

  "Given a base live arrival with an estimated time and an ACL arrival" +
    " Then we should calculate PCP time from the base live arrival." >> {
    val (aclSource, _, liveBaseSource, _, _) = TestableArrivalsGraphStage(probe, TestableArrivalsGraphStage.buildArrivalsGraphStage(pcpTimeCalc, scheduled)).run
    val baseLiveEstimated = Option(SDate("2019-10-22T14:00:00Z").millisSinceEpoch)

    liveBaseSource.offer(List(arrival(estimated = baseLiveEstimated)))

    offerAndCheck(aclSource, List(arrival()), (a: Arrival) => a.PcpTime == baseLiveEstimated)

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
