package services.exports

import akka.NotUsed
import akka.stream.scaladsl.Source
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.Queues.Queue
import drt.shared.SplitRatiosNs.SplitSource
import drt.shared.SplitRatiosNs.SplitSources.{ApiSplitsWithHistoricalEGateAndFTPercentages, Historical, TerminalAverage}
import drt.shared._
import drt.shared.api.Arrival
import drt.shared.splits.ApiSplitsToSplitRatio
import services.exports.flights.ArrivalToCsv

case class StreamingFlightsExport(millisToDateStringFn: MillisSinceEpoch => String,
                                  millisToTimeStringFn: MillisSinceEpoch => String) {

  val queueNames: Seq[Queue] = ApiSplitsToSplitRatio.queuesFromPaxTypeAndQueue(PaxTypesAndQueues.inOrder)

  def withoutActualApiCsvRow(fws: ApiFlightWithSplits): String =
    flightWithSplitsToCsvRow(queueNames, fws).mkString(",")

  def withActualApiCsvRow(fws: ApiFlightWithSplits): String = (flightWithSplitsToCsvRow(queueNames, fws) :::
    actualAPISplitsForFlightInHeadingOrder(fws, actualApiHeadingsForFlights).toList)
    .mkString(",")

  def toCsvStreamWithoutActualApi(flightsStream: Source[FlightsWithSplits, NotUsed]): Source[String, NotUsed] =
    toCsvStream(flightsStream, withoutActualApiCsvRow, csvHeader)

  def toCsvWithActualApi(flights: List[FlightsWithSplits]): String =
    csvHeaderWithActualApi + "\n" +
      flights
        .map(fws => fwsToCsv(fws, withActualApiCsvRow))
        .mkString("\n")

  def toCsvStream(
                   flightsStream: Source[FlightsWithSplits, NotUsed],
                   csvRowFn: ApiFlightWithSplits => String,
                   headers: String
                 ): Source[String, NotUsed] =
    flightsStream
      .map(fws => fwsToCsv(fws, csvRowFn))
      .prepend(Source(List(headers + "\n")))


  def fwsToCsv(fws: FlightsWithSplits, csvRowFn: ApiFlightWithSplits => String): String =
    uniqueArrivalsWithCodeShares(fws.flights.values.toSeq)
      .sortBy {
        case (fws, _) => fws.apiFlight.PcpTime
      }
      .map {
        case (fws, _) => csvRowFn(fws) + "\n"
      }
      .mkString

  def toCsvStreamWithActualApi(flightsStream: Source[FlightsWithSplits, NotUsed]): Source[String, NotUsed] = {
    toCsvStream(flightsStream, withActualApiCsvRow, csvHeaderWithActualApi)
  }

  val csvHeader: String =
    ArrivalToCsv.rawArrivalHeadings + ",PCP Pax," +
      headingsForSplitSource(queueNames, "API") + "," +
      headingsForSplitSource(queueNames, "Historical") + "," +
      headingsForSplitSource(queueNames, "Terminal Average")

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

  val csvHeaderWithActualApi: String = csvHeader + "," + actualApiHeadingsForFlights.mkString(",")

  def headingsForSplitSource(queueNames: Seq[Queue], source: String): String = queueNames
    .map(q => {
      val queueName = Queues.queueDisplayNames(q)
      s"$source $queueName"
    })
    .mkString(",")

  val splitSources = List(ApiSplitsWithHistoricalEGateAndFTPercentages, Historical, TerminalAverage)

  def flightWithSplitsToCsvRow(queueNames: Seq[Queue], fws: ApiFlightWithSplits): List[String] = {
    val splitsForSources = splitSources.flatMap((ss: SplitSource) => queueSplits(queueNames, fws, ss))
    ArrivalToCsv.arrivalAsRawCsvValues(
      fws.apiFlight,
      millisToDateStringFn,
      millisToTimeStringFn
    ) ++
      List(fws.apiFlight.bestPaxEstimate.toString) ++ splitsForSources
  }

  def queueSplits(queueNames: Seq[Queue],
                  fws: ApiFlightWithSplits,
                  splitSource: SplitSource): Seq[String] =
    queueNames.map(q => s"${queuePaxForFlightUsingSplits(fws, splitSource).getOrElse(q, "")}")

  def queuePaxForFlightUsingSplits(fws: ApiFlightWithSplits, splitSource: SplitSource): Map[Queue, Int] =
    fws
      .splits
      .find(_.source == splitSource)
      .map(splits => ApiSplitsToSplitRatio.flightPaxPerQueueUsingSplitsAsRatio(splits, fws.apiFlight))
      .getOrElse(Map())

  val uniqueArrivalsWithCodeShares: Seq[ApiFlightWithSplits] => List[(ApiFlightWithSplits, Set[Arrival])] = CodeShares
    .uniqueArrivalsWithCodeShares((f: ApiFlightWithSplits) => identity(f.apiFlight))

  def actualAPISplitsForFlightInHeadingOrder(flight: ApiFlightWithSplits, headings: Seq[String]): Seq[Double] =
    headings.map(h => Exports.actualAPISplitsAndHeadingsFromFlight(flight).toMap.getOrElse(h, 0.0))
      .map(n => Math.round(n).toDouble)

}
