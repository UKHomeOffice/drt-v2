package services.exports.summaries.flights

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared._
import services.exports.Exports


case class TerminalFlightsWithActualApiSummary(flights: Seq[ApiFlightWithSplits],
                                               millisToDateOnly: MillisSinceEpoch => String,
                                               millisToHoursAndMinutes: MillisSinceEpoch => String) extends TerminalFlightsSummaryLike {
  lazy val actualApiHeadings: Seq[String] = Exports.actualApiHeadings(flights)

  def actualAPISplitsForFlightInHeadingOrder(fws: ApiFlightWithSplits, headings: Seq[String]): Seq[Double] =
    Exports.actualAPISplitsForFlightInHeadingOrder(fws, actualApiHeadings)

  override lazy val csvHeader: String = standardCsvHeader + "," + actualApiHeadings.mkString(",")

  override def toCsv: String =  {
    val csvData = flights.sortBy(_.apiFlight.PcpTime).map(fws => {
      flightToCsvRow(queueNames, fws) ::: actualAPISplitsForFlightInHeadingOrder(fws, actualApiHeadings).toList
    })
    asCSV(csvData)
  }

  lazy val standardCsvHeader: String =
    "IATA,ICAO,Origin,Gate/Stand,Status,Scheduled Date,Scheduled Time,Est Arrival,Act Arrival,Est Chox,Act Chox,Est PCP,Total Pax,PCP Pax," +
      headingsForSplitSource(queueNames, "API") + "," +
      headingsForSplitSource(queueNames, "Historical") + "," +
      headingsForSplitSource(queueNames, "Terminal Average")
}
