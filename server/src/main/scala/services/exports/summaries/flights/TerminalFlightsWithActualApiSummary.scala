package services.exports.summaries.flights

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared._


case class TerminalFlightsWithActualApiSummary(flights: Seq[ApiFlightWithSplits],
                                               millisToDateOnly: MillisSinceEpoch => String,
                                               millisToHoursAndMinutes: MillisSinceEpoch => String) extends TerminalFlightsSummaryLike {
  override lazy val csvHeader: String = standardCsvHeader + "," + actualAPIHeadings.mkString(",")

  override def toCsv: String =  {
    val csvData = flights.sortBy(_.apiFlight.PcpTime).map(fws => {
      flightToCsvRow(queueNames, fws) ::: actualAPISplitsForFlightInHeadingOrder(fws, actualAPIHeadings).toList
    })
    asCSV(csvData)
  }

  lazy val standardCsvHeader: String =
    "IATA,ICAO,Origin,Gate/Stand,Status,Scheduled Date,Scheduled Time,Est Arrival,Act Arrival,Est Chox,Act Chox,Est PCP,Total Pax,PCP Pax," +
      headingsForSplitSource(queueNames, "API") + "," +
      headingsForSplitSource(queueNames, "Historical") + "," +
      headingsForSplitSource(queueNames, "Terminal Average")

  lazy val actualAPIHeadings: Seq[String] =
    flights.flatMap(f => actualAPISplitsAndHeadingsFromFlight(f).map(_._1)).distinct.sorted

  def actualAPISplitsAndHeadingsFromFlight(flightWithSplits: ApiFlightWithSplits): Set[(String, Double)] = flightWithSplits
    .splits
    .collect {
      case s: Splits if s.source == SplitRatiosNs.SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages =>
        s.splits.map(s => {
          val ptaq = PaxTypeAndQueue(s.passengerType, s.queueType)
          (s"API Actual - ${PaxTypesAndQueues.displayName(ptaq)}", s.paxCount)
        })
    }
    .flatten

  def actualAPISplitsForFlightInHeadingOrder(flight: ApiFlightWithSplits, headings: Seq[String]): Seq[Double] =
    headings.map(h => actualAPISplitsAndHeadingsFromFlight(flight).toMap.getOrElse(h, 0.0))
      .map(n => Math.round(n).toDouble)

}
