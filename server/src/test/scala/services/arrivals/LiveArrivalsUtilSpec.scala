package services.arrivals

import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.time.SDate
import uk.gov.homeoffice.drt.arrivals.{Arrival, ArrivalStatus, Prediction}
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.ports.Terminals.T1

class LiveArrivalsUtilSpec extends Specification {

  def arrival(estimated: Option[Long] = None,
              predTouchdown: Option[Prediction[Long]] = None,
              actual: Option[Long] = None,
              estChox: Option[Long] = None,
              actChox: Option[Long] = None,
              gate: Option[String] = None,
              status: ArrivalStatus = ArrivalStatus("test"),
              scheduledDeparture: Option[Long] = None
             ): Arrival =
    Arrival(
      Operator = None,
      Status = status,
      Estimated = estimated,
      PredictedTouchdown = predTouchdown,
      Actual = actual,
      EstimatedChox = estChox,
      ActualChox = actChox,
      Gate = gate,
      Stand = None,
      MaxPax = None,
      ActPax = None,
      TranPax = None,
      RunwayID = None,
      BaggageReclaimId = None,
      AirportID = PortCode("STN"),
      Terminal = T1,
      rawICAO = "TST100",
      rawIATA = "TST100",
      Origin = PortCode("TST"),
      Scheduled = SDate(2019,
        9,
        30,
        16, 0).millisSinceEpoch,
      PcpTime = None,
      FeedSources = Set()
    )

  "Given a BaseLiveArrival with all landing times set and port arrival with none then I should get the BaseArrival times" >> {
    val baseArrival = arrival(
      estimated = Option(SDate(2019, 9, 30, 16, 1).millisSinceEpoch),
      actual = Option(SDate(2019, 9, 30, 16, 2).millisSinceEpoch),
      estChox = Option(SDate(2019, 9, 30, 16, 3).millisSinceEpoch),
      actChox = Option(SDate(2019, 9, 30, 16, 4).millisSinceEpoch),
      gate = None,
      status = ArrivalStatus("Test"))
    val liveArrival = arrival(gate = None)

    val expected = liveArrival.copy(
      Estimated = baseArrival.Estimated,
      EstimatedChox = baseArrival.EstimatedChox,
      Actual = baseArrival.Actual,
      ActualChox = baseArrival.ActualChox
    )

    val result = LiveArrivalsUtil.mergePortFeedWithLiveBase(liveArrival, baseArrival)

    result === expected
  }

  "Given a BaseLiveArrival with no gate and a port arrival with a gate then I should use the gate from port arrival" >> {
    val baseArrival = arrival(status = ArrivalStatus("Test"))
    val liveArrival = arrival(gate =
      Option("Gate"))
    val expected = liveArrival.copy()

    val result = LiveArrivalsUtil.mergePortFeedWithLiveBase(liveArrival, baseArrival)

    result === expected
  }

  "Given a BaseLiveArrival with all landing times set and port arrival all times set then I should get the port times" >> {
    val baseArrival = arrival(
      estimated = Option(SDate(2019, 9, 30, 16, 1).millisSinceEpoch),
      actual = Option(SDate(2019, 9, 30, 16, 2).millisSinceEpoch),
      estChox = Option(SDate(2019, 9, 30, 16, 3).millisSinceEpoch),
      actChox = Option(SDate(2019, 9, 30, 16, 4).millisSinceEpoch))

    val liveArrival = arrival(
      estimated = Option(SDate(2019, 9, 30, 16, 5).millisSinceEpoch),
      actual = Option(SDate(2019, 9, 30, 16, 6).millisSinceEpoch),
      estChox = Option(SDate(2019, 9, 30, 16, 7).millisSinceEpoch),
      actChox = Option(SDate(2019, 9, 30, 16, 8).millisSinceEpoch),
      scheduledDeparture =
        Option(SDate(2019, 9, 30, 13, 8).millisSinceEpoch))
    val expected = liveArrival.copy()

    val result = LiveArrivalsUtil.mergePortFeedWithLiveBase(liveArrival, baseArrival)

    result === expected
  }

  "Given a BaseLiveArrival with a gate and a port arrival with no gate then I should use the gate from base arrival" >> {
    val baseArrival = arrival(gate = Option("Gate"))
    val liveArrival = arrival(status = ArrivalStatus("Test"))

    val expected = liveArrival.copy(Gate = baseArrival.Gate)

    val result = LiveArrivalsUtil.mergePortFeedWithLiveBase(liveArrival, baseArrival)

    result === expected
  }

  "Given a BaseLiveArrival with a gate and a port arrival with a gate then I should use the gate from port arrival" >> {
    val baseArrival = arrival(gate = Option("Base Gate"))
    val liveArrival = arrival(gate = Option("Port Gate"))

    val expected = liveArrival.copy()

    val result = LiveArrivalsUtil.mergePortFeedWithLiveBase(liveArrival, baseArrival)

    result === expected
  }

  "Given a BaseLiveArrival with a status and a port arrival with a status of UNK then I should use the base status" >> {
    val baseArrival = arrival(status = ArrivalStatus("Landed"))
    val liveArrival = arrival(status = ArrivalStatus("UNK"))

    val expected = liveArrival.copy(Status = baseArrival.Status)

    val result = LiveArrivalsUtil.mergePortFeedWithLiveBase(liveArrival, baseArrival)

    result === expected
  }

}
