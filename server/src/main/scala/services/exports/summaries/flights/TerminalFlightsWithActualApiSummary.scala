package services.exports.summaries.flights

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared._
import services.exports.Exports


case class TerminalFlightsWithActualApiSummary(flights: Seq[ApiFlightWithSplits],
                                               millisToDateOnly: MillisSinceEpoch => String,
                                               millisToHoursAndMinutes: MillisSinceEpoch => String) extends TerminalFlightsSummaryLike {
  import TerminalFlightsWithActualApiSummary._

  lazy val actualApiHeadings: Seq[String] = actualApiHeadingsForFlights(flights)

  def actualAPISplitsForFlightInHeadingOrder(fws: ApiFlightWithSplits, headings: Seq[String]): Seq[Double] =
    actualAPISplitsForFlightInHeadingOrder(fws, actualApiHeadings)

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

object TerminalFlightsWithActualApiSummary {
  def actualApiHeadingsForFlights(flights: Seq[ApiFlightWithSplits]): Seq[String] =
    flights.flatMap(f => Exports.actualAPISplitsAndHeadingsFromFlight(f).map(_._1)).distinct.sorted

  def actualAPISplitsForFlightInHeadingOrder(flight: ApiFlightWithSplits, headings: Seq[String]): Seq[Double] =
    headings.map(h => Exports.actualAPISplitsAndHeadingsFromFlight(flight).toMap.getOrElse(h, 0.0))
      .map(n => Math.round(n).toDouble)
}
