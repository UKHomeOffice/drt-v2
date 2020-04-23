package services.exports.summaries.flights

import drt.shared.ApiFlightWithSplits
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.api.Arrival
import services.exports.summaries.flights.TerminalFlightsSummary._

case class TerminalFlightsSummary(flights: Seq[ApiFlightWithSplits],
                                  millisToDateOnly: MillisSinceEpoch => String,
                                  millisToHoursAndMinutes: MillisSinceEpoch => String,
                                  pcpPaxFn: Arrival => Int
                                 ) extends TerminalFlightsSummaryLike {

  override lazy val csvHeader: String =
    rawArrivalHeadings + ",PCP Pax," +
      headingsForSplitSource(queueNames, "API") + "," +
      headingsForSplitSource(queueNames, "Historical") + "," +
      headingsForSplitSource(queueNames, "Terminal Average")

  override def toCsv: String = {
    val uniqueApiFlightWithSplits: Seq[(ApiFlightWithSplits, Set[Arrival])] = uniqueArrivalsWithCodeShares(flights)
    val csvData = uniqueApiFlightWithSplits.sortBy(_._1.apiFlight.PcpTime).map(fws =>
      flightWithSplitsToCsvRow(queueNames, fws._1)
    )
    asCSV(csvData)
  }
}


case object TerminalFlightsSummary {

  val rawArrivalHeadings = "IATA,ICAO,Origin,Gate/Stand,Status,Scheduled Date,Scheduled Time,Est Arrival,Act Arrival,Est Chox,Act Chox,Est PCP,Total Pax"
  val rawArrivalHeadingsWithTransfer = rawArrivalHeadings + ",Transfer Pax"

  def arrivalAsRawCsvValues(arrival: Arrival, millisToDateOnly: MillisSinceEpoch => String,
                            millisToHoursAndMinutes: MillisSinceEpoch => String) = {
    List(arrival.flightCode,
      arrival.flightCode,
      arrival.Origin.toString,
      arrival.Gate.getOrElse("") + "/" + arrival.Stand.getOrElse(""),
      arrival.Status.description,
      millisToDateOnly(arrival.Scheduled),
      millisToHoursAndMinutes(arrival.Scheduled),
      arrival.Estimated.map(millisToHoursAndMinutes(_)).getOrElse(""),
      arrival.Actual.map(millisToHoursAndMinutes(_)).getOrElse(""),
      arrival.EstimatedChox.map(millisToHoursAndMinutes(_)).getOrElse(""),
      arrival.ActualChox.map(millisToHoursAndMinutes(_)).getOrElse(""),
      arrival.PcpTime.map(millisToHoursAndMinutes(_)).getOrElse(""),
      arrival.ActPax.getOrElse(0).toString)
  }

  def arrivalAsRawCsvValuesWithTransfer(arrival: Arrival, millisToDateOnly: MillisSinceEpoch => String,
                                        millisToHoursAndMinutes: MillisSinceEpoch => String): List[String] =
    arrivalAsRawCsvValues(arrival, millisToDateOnly, millisToHoursAndMinutes) :+ arrival.TranPax.getOrElse(0).toString

}
