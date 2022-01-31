package services.exports.flights

import drt.shared.CrunchApi.MillisSinceEpoch
import uk.gov.homeoffice.drt.arrivals.Arrival

object ArrivalToCsv {

  val arrivalHeadings = "IATA,ICAO,Origin,Gate/Stand,Status,Scheduled Date,Scheduled Time,Est Arrival,Act Arrival,Est Chox,Act Chox,Est PCP,Total Pax"
  val arrivalHeadingsWithTransfer: String = arrivalHeadings + ",Transfer Pax"

  def arrivalToCsvFields(arrival: Arrival, millisToDateOnly: MillisSinceEpoch => String,
                         millisToHoursAndMinutes: MillisSinceEpoch => String): List[String] =
    List(arrival.flightCodeString,
      arrival.flightCodeString,
      arrival.Origin.toString,
      arrival.Gate.getOrElse("") + "/" + arrival.Stand.getOrElse(""),
      arrival.displayStatus.description,
      millisToDateOnly(arrival.Scheduled),
      millisToHoursAndMinutes(arrival.Scheduled),
      arrival.Estimated.map(millisToHoursAndMinutes(_)).getOrElse(""),
      arrival.Actual.map(millisToHoursAndMinutes(_)).getOrElse(""),
      arrival.EstimatedChox.map(millisToHoursAndMinutes(_)).getOrElse(""),
      arrival.ActualChox.map(millisToHoursAndMinutes(_)).getOrElse(""),
      arrival.PcpTime.map(millisToHoursAndMinutes(_)).getOrElse(""),
      arrival.ActPax.map(_.toString).getOrElse(""),
    )

  def arrivalWithTransferToCsvFields(arrival: Arrival, millisToDateOnly: MillisSinceEpoch => String,
                                     millisToHoursAndMinutes: MillisSinceEpoch => String): List[String] =
    arrivalToCsvFields(arrival, millisToDateOnly, millisToHoursAndMinutes) :+ arrival.TranPax.getOrElse(0).toString
}
