package services.exports.flights

import drt.shared.ApiFlightWithSplits
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.api.Arrival

object ArrivalToCsv {

  val rawArrivalHeadings = "IATA,ICAO,Origin,Gate/Stand,Status,Scheduled Date,Scheduled Time,Est Arrival,Act Arrival,Est Chox,Act Chox,Est PCP,Total Pax"
  val rawArrivalHeadingsWithTransfer: String = rawArrivalHeadings + ",Transfer Pax"

  def arrivalAsRawCsvValues(arrival: Arrival, millisToDateOnly: MillisSinceEpoch => String,
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
      arrival.ActPax.getOrElse("").toString)

  def flightWithSplitsAsRawCsvValues(fws: ApiFlightWithSplits, millisToDateOnly: MillisSinceEpoch => String,
                                     millisToHoursAndMinutes: MillisSinceEpoch => String): List[String] =
    List(fws.apiFlight.flightCodeString,
      fws.apiFlight.flightCodeString,
      fws.apiFlight.Origin.toString,
      fws.apiFlight.Gate.getOrElse("") + "/" + fws.apiFlight.Stand.getOrElse(""),
      fws.apiFlight.displayStatus.description,
      millisToDateOnly(fws.apiFlight.Scheduled),
      millisToHoursAndMinutes(fws.apiFlight.Scheduled),
      fws.apiFlight.Estimated.map(millisToHoursAndMinutes(_)).getOrElse(""),
      fws.apiFlight.Actual.map(millisToHoursAndMinutes(_)).getOrElse(""),
      fws.apiFlight.EstimatedChox.map(millisToHoursAndMinutes(_)).getOrElse(""),
      fws.apiFlight.ActualChox.map(millisToHoursAndMinutes(_)).getOrElse(""),
      fws.apiFlight.PcpTime.map(millisToHoursAndMinutes(_)).getOrElse(""),
      fws.totalPax.getOrElse("").toString)


  def arrivalAsRawCsvValuesWithTransfer(arrival: Arrival, millisToDateOnly: MillisSinceEpoch => String,
                                        millisToHoursAndMinutes: MillisSinceEpoch => String): List[String] =
    arrivalAsRawCsvValues(arrival, millisToDateOnly, millisToHoursAndMinutes) :+ arrival.TranPax.getOrElse(0).toString

}
