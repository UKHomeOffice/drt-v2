package services.arrivals

import org.specs2.mutable.Specification
import services.SDate
import uk.gov.homeoffice.drt.arrivals.{Arrival, ArrivalStatus}
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.time.SDateLike

class ArrivalDataSanitiserSpec extends Specification {

  val scheduled: SDateLike = SDate(2019, 9, 30, 16, 0)

  def arrival(estimated: Option[Long] = None,
              actual: Option[Long] = None,
              estChox: Option[Long] = None,
              actChox: Option[Long] = None,
              gate: Option[String] = None,
              status: ArrivalStatus = ArrivalStatus("test")): Arrival =
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
