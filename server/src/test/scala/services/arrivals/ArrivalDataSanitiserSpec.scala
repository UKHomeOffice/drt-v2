package services.arrivals

import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.time.SDate
import uk.gov.homeoffice.drt.arrivals.{Arrival, ArrivalStatus, Prediction, Predictions}
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.time.SDateLike

class ArrivalDataSanitiserSpec extends Specification {

  val scheduled: SDateLike = SDate(2019, 9, 30, 16, 0)

  def arrival(estimated: Option[Long] = None,
              predTouchdown: Option[Prediction[Long]] = None,
              actual: Option[Long] = None,
              estChox: Option[Long] = None,
              actChox: Option[Long] = None,
              gate: Option[String] = None,
              status: ArrivalStatus = ArrivalStatus("test")): Arrival =
    Arrival(
      Operator = None,
      Status = status,
      Estimated = estimated,
      Predictions = Predictions(0L, Map()),
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
      Scheduled = scheduled.millisSinceEpoch,
      PcpTime = None,
      FeedSources = Set()
      )

  "Given a base live arrival with an estimated time that is outside the threshold " +
    "Then the estimated time should be ignored" >> {
    val arrivalWithIrrationalEstimation = arrival(estimated = Option(scheduled.addHours(5).millisSinceEpoch))
    val sanitiser = ArrivalDataSanitiser(Option(4), None)
    val saneArrival = sanitiser.withSaneEstimates(arrivalWithIrrationalEstimation)

    saneArrival.Estimated === None
  }

  "Given a base live arrival with an estimated chox time that is outside the threshold " +
    "Then the estimated time should be ignored" >> {
    val arrivalWithIrrationalEstimation = arrival(estChox = Option(scheduled.addHours(5).millisSinceEpoch))
    val sanitiser = ArrivalDataSanitiser(Option(4), None)
    val saneArrival = sanitiser.withSaneEstimates(arrivalWithIrrationalEstimation)

    saneArrival.EstimatedChox === None
  }

  "Given a base live arrival with an estimated chox time that is before the estimated arrival time " +
    "Then the estimated chox time should be ignored" >> {
    val arrivalWithIrrationalEstimation = arrival(
      estChox = Option(scheduled.addHours(-1).millisSinceEpoch),
      estimated = Option(scheduled.millisSinceEpoch)
      )
    val sanitiser = ArrivalDataSanitiser(Option(4), None)
    val saneArrival = sanitiser.withSaneEstimates(arrivalWithIrrationalEstimation)

    saneArrival.EstimatedChox === None
  }

  "Given a base live arrival with an estimated chox time that is outside the taxi threshold " +
    "Then the estimated chox time should be ignored" >> {
    val arrivalWithIrrationalEstimation = arrival(
      estChox = Option(scheduled.addMinutes(25).millisSinceEpoch),
      estimated = Option(scheduled.millisSinceEpoch)
      )
    val sanitiser = ArrivalDataSanitiser(Option(4), Option(20))
    val saneArrival = sanitiser.withSaneEstimates(arrivalWithIrrationalEstimation)

    saneArrival.EstimatedChox === None
  }

  "Given a base live arrival with an estimated chox time that is the same as the estimated touch down time " +
    "Then the estimated chox time should be ignored" >> {
    val arrivalWithIrrationalEstimation = arrival(
      estChox = Option(scheduled.millisSinceEpoch),
      estimated = Option(scheduled.millisSinceEpoch)
      )

    val sanitiser = ArrivalDataSanitiser(Option(4), Option(20))
    val saneArrival = sanitiser.withSaneEstimates(arrivalWithIrrationalEstimation)

    saneArrival.EstimatedChox === None
  }
}
