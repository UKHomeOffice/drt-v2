package services.exports

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.Queues.Queue
import drt.shared.SplitRatiosNs.SplitSource
import drt.shared.SplitRatiosNs.SplitSources.{ApiSplitsWithHistoricalEGateAndFTPercentages, Historical, TerminalAverage}
import drt.shared.splits.ApiSplitsToSplitRatio
import drt.shared.{ApiFlightWithSplits, PaxTypesAndQueues, Queues}
import org.joda.time.DateTimeZone
import services.SDate
import services.exports.flights.ArrivalToCsv

trait FlightExportTemplate {

  val timeZone: DateTimeZone

  val queueNames: Seq[Queue] = ApiSplitsToSplitRatio.queuesFromPaxTypeAndQueue(PaxTypesAndQueues.inOrder)

  def headings: String

  def rowValues(fws: ApiFlightWithSplits): Seq[String]

  def row(fws: ApiFlightWithSplits): String = rowValues(fws).mkString(",")

  def millisToDateStringFn: MillisSinceEpoch => String = SDate.millisToLocalIsoDateOnly(timeZone)

  def millisToTimeStringFn: MillisSinceEpoch => String = SDate.millisToLocalHoursAndMinutes(timeZone)

  def headingsForSplitSource(queueNames: Seq[Queue], source: String): String = queueNames
    .map(q => {
      val queueName = Queues.queueDisplayNames(q)
      s"$source $queueName"
    })
    .mkString(",")

  val csvHeader: String =
    ArrivalToCsv.rawArrivalHeadings + ",PCP Pax," +
      headingsForSplitSource(queueNames, "API") + "," +
      headingsForSplitSource(queueNames, "Historical") + "," +
      headingsForSplitSource(queueNames, "Terminal Average")

  val splitSources = List(ApiSplitsWithHistoricalEGateAndFTPercentages, Historical, TerminalAverage)

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

}

case class DefaultFlightExportTemplate(override val timeZone: DateTimeZone) extends FlightExportTemplate {

  def headings: String =
    ArrivalToCsv.rawArrivalHeadings + ",PCP Pax," +
      headingsForSplitSource(queueNames, "API") + "," +
      headingsForSplitSource(queueNames, "Historical") + "," +
      headingsForSplitSource(queueNames, "Terminal Average")

  def rowValues(fws: ApiFlightWithSplits): Seq[String] = {
    val splitsForSources = splitSources.flatMap((ss: SplitSource) => queueSplits(queueNames, fws, ss))
    ArrivalToCsv.arrivalAsRawCsvValues(
      fws.apiFlight,
      millisToDateStringFn,
      millisToTimeStringFn
    ) ++
      List(fws.apiFlight.bestPaxEstimate.toString) ++ splitsForSources
  }

}

case class ActualApiFlightExportTemplate(override val timeZone: DateTimeZone) extends FlightExportTemplate {

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

  val headings: String = csvHeader + "," + actualApiHeadingsForFlights.mkString(",")

  def flightWithSplitsToCsvRow(queueNames: Seq[Queue], fws: ApiFlightWithSplits): List[String] = {
    val splitsForSources = splitSources.flatMap((ss: SplitSource) => queueSplits(queueNames, fws, ss))
    ArrivalToCsv.arrivalAsRawCsvValues(
      fws.apiFlight,
      millisToDateStringFn,
      millisToTimeStringFn
    ) ++
      List(fws.apiFlight.bestPaxEstimate.toString) ++ splitsForSources
  }

  def actualAPISplitsForFlightInHeadingOrder(flight: ApiFlightWithSplits, headings: Seq[String]): Seq[Double] =
    headings.map(h => Exports.actualAPISplitsAndHeadingsFromFlight(flight).toMap.getOrElse(h, 0.0))
      .map(n => Math.round(n).toDouble)

  def rowValues(fws: ApiFlightWithSplits): Seq[String] = (flightWithSplitsToCsvRow(queueNames, fws) :::
    actualAPISplitsForFlightInHeadingOrder(fws, actualApiHeadingsForFlights).toList).map(s => s"$s")


}

case class CedatFlightExportTemplate(override val timeZone: DateTimeZone) extends FlightExportTemplate {

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

  val headings: String = csvHeader + "," + actualApiHeadingsForFlights.mkString(",")

  def flightWithSplitsToCsvRow(queueNames: Seq[Queue], fws: ApiFlightWithSplits): List[String] = {
    val splitsForSources = splitSources.flatMap((ss: SplitSource) => queueSplits(queueNames, fws, ss))
    ArrivalToCsv.arrivalAsRawCsvValues(
      fws.apiFlight,
      millisToDateStringFn,
      millisToTimeStringFn
    ) ++
      List(fws.apiFlight.bestPaxEstimate.toString) ++ splitsForSources
  }

  def actualAPISplitsForFlightInHeadingOrder(flight: ApiFlightWithSplits, headings: Seq[String]): Seq[Double] =
    headings.map(h => Exports.actualAPISplitsAndHeadingsFromFlight(flight).toMap.getOrElse(h, 0.0))
      .map(n => Math.round(n).toDouble)

  def rowValues(fws: ApiFlightWithSplits): Seq[String] = (flightWithSplitsToCsvRow(queueNames, fws) :::
    actualAPISplitsForFlightInHeadingOrder(fws, actualApiHeadingsForFlights).toList).map(s => s"$s")

}
