package drt.shared

import uk.gov.homeoffice.drt.arrivals.{ForecastArrival, LiveArrival}
import uk.gov.homeoffice.drt.ports.Terminals.{T1, Terminal}

object FeedArrivalGenerator {
  def live(operator: Option[String] = None,
           maxPax: Option[Int] = None,
           totalPax: Option[Int] = None,
           transPax: Option[Int] = None,
           terminal: Terminal = T1,
           voyageNumber: Int = 1,
           carrierCode: String = "BA",
           flightCodeSuffix: Option[String] = None,
           origin: String = "JFK",
           scheduled: Long = 0L,
           estimated: Option[Long] = None,
           touchdown: Option[Long] = None,
           estimatedChox: Option[Long] = None,
           actualChox: Option[Long] = None,
           status: String = "",
           gate: Option[String] = None,
           stand: Option[String] = None,
           runway: Option[String] = None,
           baggageReclaim: Option[String] = None,
          ): LiveArrival =
    LiveArrival(
      operator = operator,
      maxPax = maxPax,
      totalPax = totalPax,
      transPax = transPax,
      terminal = terminal,
      voyageNumber = voyageNumber,
      carrierCode = carrierCode,
      flightCodeSuffix = flightCodeSuffix,
      origin = origin,
      scheduled = scheduled,
      estimated = estimated,
      touchdown = touchdown,
      estimatedChox = estimatedChox,
      actualChox = actualChox,
      status = status,
      gate = gate,
      stand = stand,
      runway = runway,
      baggageReclaim = baggageReclaim,
    )

  def forecast(operator: Option[String] = None,
               maxPax: Option[Int] = None,
               totalPax: Option[Int] = None,
               transPax: Option[Int] = None,
               terminal: Terminal = T1,
               voyageNumber: Int = 1,
               carrierCode: String = "BA",
               flightCodeSuffix: Option[String] = None,
               origin: String = "JFK",
               scheduled: Long = 0L,
              ): ForecastArrival =
    ForecastArrival(
      operator = operator,
      maxPax = maxPax,
      totalPax = totalPax,
      transPax = transPax,
      terminal = terminal,
      voyageNumber = voyageNumber,
      carrierCode = carrierCode,
      flightCodeSuffix = flightCodeSuffix,
      origin = origin,
      scheduled = scheduled,
    )
}
