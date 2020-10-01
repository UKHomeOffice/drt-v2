package services.exports

import akka.NotUsed
import akka.stream.scaladsl.Source
import drt.shared.Queues.Queue
import drt.shared.SplitRatiosNs.SplitSource
import drt.shared.SplitRatiosNs.SplitSources.{ApiSplitsWithHistoricalEGateAndFTPercentages, Historical, TerminalAverage}
import drt.shared.api.Arrival
import drt.shared.splits.ApiSplitsToSplitRatio
import drt.shared._
import services.SDate
import services.exports.summaries.flights.TerminalFlightsSummary
import services.exports.summaries.flights.TerminalFlightsSummary.rawArrivalHeadings
import services.exports.summaries.flights.TerminalFlightsWithActualApiSummary.actualAPISplitsForFlightInHeadingOrder
import services.graphstages.Crunch

case class StreamingFlightsExport(pcpPaxFn: Arrival => Int) {

  val queueNames: Seq[Queue] = ApiSplitsToSplitRatio.queuesFromPaxTypeAndQueue(PaxTypesAndQueues.inOrder)

  def toCsvStream(flightsStream: Source[FlightsApi.FlightsWithSplits, NotUsed]): Source[String, NotUsed] = {
    flightsStream.map(fws => {
      uniqueArrivalsWithCodeShares(fws.flights.values.toSeq).map {
        case (fws, _) => flightWithSplitsToCsvRow(queueNames, fws).mkString(",") + "\n"
      }.mkString
    }).prepend(Source(List(csvHeader)))
  }

  def toCsvStreamWithActualApi(flightsStream: Source[FlightsApi.FlightsWithSplits, NotUsed]): Source[String, NotUsed] = {
    flightsStream.map(fws => {
      uniqueArrivalsWithCodeShares(fws.flights.values.toSeq).map {
        case (fws, _) =>
         val rowFields = flightWithSplitsToCsvRow(queueNames, fws) :::
            actualAPISplitsForFlightInHeadingOrder(fws, actualApiHeadingsForFlights).toList
          rowFields.mkString(",") + "\n"
      }.mkString
    }).prepend(Source(List(csvHeader + "," + actualApiHeadingsForFlights.mkString(","))))
  }

  val csvHeader: String =
    rawArrivalHeadings + ",PCP Pax," +
      headingsForSplitSource(queueNames, "API") + "," +
      headingsForSplitSource(queueNames, "Historical") + "," +
      headingsForSplitSource(queueNames, "Terminal Average")

  def headingsForSplitSource(queueNames: Seq[Queue], source: String): String = queueNames
    .map(q => {
      val queueName = Queues.queueDisplayNames(q)
      s"$source $queueName"
    })
    .mkString(",")

  val splitSources = List(ApiSplitsWithHistoricalEGateAndFTPercentages, Historical, TerminalAverage)

  def flightWithSplitsToCsvRow(queueNames: Seq[Queue], fws: ApiFlightWithSplits): List[String] = {
    val splitsForSources = splitSources.flatMap((ss: SplitSource) => queueSplits(queueNames, fws, ss))
    TerminalFlightsSummary.arrivalAsRawCsvValues(
      fws.apiFlight,
      SDate.millisToLocalIsoDateOnly(Crunch.europeLondonTimeZone),
      SDate.millisToLocalHoursAndMinutes(Crunch.europeLondonTimeZone)
    ) ++
      List(pcpPaxFn(fws.apiFlight).toString) ++ splitsForSources
  }

  def queueSplits(queueNames: Seq[Queue],
                  fws: ApiFlightWithSplits,
                  splitSource: SplitSource): Seq[String] =
    queueNames.map(q => s"${queuePaxForFlightUsingSplits(fws, splitSource).getOrElse(q, "")}")

  def queuePaxForFlightUsingSplits(fws: ApiFlightWithSplits, splitSource: SplitSource): Map[Queue, Int] =
    fws
      .splits
      .find(_.source == splitSource)
      .map(splits => ApiSplitsToSplitRatio.flightPaxPerQueueUsingSplitsAsRatio(splits, fws.apiFlight, pcpPaxFn))
      .getOrElse(Map())

  val uniqueArrivalsWithCodeShares: Seq[ApiFlightWithSplits] => List[(ApiFlightWithSplits, Set[Arrival])] = CodeShares
    .uniqueArrivalsWithCodeShares((f: ApiFlightWithSplits) => identity(f.apiFlight))


  val actualApiHeadingsForFlights: Seq[String] = Seq(
    "API Actual - B5JSSK to Desk",
    "API Actual - B5JSSK to eGates",
    "API Actual - EEA (Machine Readable)",
    "API Actual - EEA (Non Machine Readable)",
    "API Actual - Fast Track (Non Visa)",
    "API Actual - Fast Track (Visa)",
    "API Actual - Non EEA (Non Visa)",
    "API Actual - Non EEA (Visa)",
    "API Actual - Transfer",
    "API Actual - eGates"
  )

  def actualAPISplitsForFlightInHeadingOrder(flight: ApiFlightWithSplits, headings: Seq[String]): Seq[Double] =
    headings.map(h => Exports.actualAPISplitsAndHeadingsFromFlight(flight).toMap.getOrElse(h, 0.0))
      .map(n => Math.round(n).toDouble)

}
