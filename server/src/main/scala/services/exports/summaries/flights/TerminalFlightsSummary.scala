package services.exports.summaries.flights

import drt.shared.ApiFlightWithSplits
import drt.shared.CrunchApi.MillisSinceEpoch

case class TerminalFlightsSummary(flights: Seq[ApiFlightWithSplits],
                                  millisToDateOnly: MillisSinceEpoch => String,
                                  millisToHoursAndMinutes: MillisSinceEpoch => String) extends TerminalFlightsSummaryLike {
  override lazy val csvHeader: String =
    "IATA,ICAO,Origin,Gate/Stand,Status,Scheduled Date,Scheduled Time,Est Arrival,Act Arrival,Est Chox,Act Chox,Est PCP,Total Pax,PCP Pax," +
      headingsForSplitSource(queueNames, "API") + "," +
      headingsForSplitSource(queueNames, "Historical") + "," +
      headingsForSplitSource(queueNames, "Terminal Average")

  override def toCsv: String = {
    val csvData = flights.sortBy(_.apiFlight.PcpTime).map(fws => {
      flightToCsvRow(queueNames, fws)
    })
    asCSV(csvData)
  }
}
