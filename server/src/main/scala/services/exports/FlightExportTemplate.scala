package services.exports

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.Queues.Queue
import drt.shared.SplitRatiosNs.SplitSource
import drt.shared.SplitRatiosNs.SplitSources.{ApiSplitsWithHistoricalEGateAndFTPercentages, Historical, TerminalAverage}
import drt.shared.splits.ApiSplitsToSplitRatio
import drt.shared.{ApiFlightWithSplits, PaxTypesAndQueues}
import org.joda.time.DateTimeZone
import services.SDate
import services.exports.flights.{CedatArrivalToCsv, FlightWithSplitsToCsv}

trait FlightExportTemplate {

  val timeZone: DateTimeZone

  val queueNames: Seq[Queue] = ApiSplitsToSplitRatio.queuesFromPaxTypeAndQueue(PaxTypesAndQueues.inOrder)

  def headings: String

  def rowValues(fws: ApiFlightWithSplits): Seq[String]

  def row(fws: ApiFlightWithSplits): String = rowValues(fws).mkString(",")

  def millisToDateStringFn: MillisSinceEpoch => String = SDate.millisToLocalIsoDateOnly(timeZone)

  def millisToTimeStringFn: MillisSinceEpoch => String = SDate.millisToLocalHoursAndMinutes(timeZone)

  val splitSources = List(ApiSplitsWithHistoricalEGateAndFTPercentages, Historical, TerminalAverage)

  def queueSplits(queueNames: Seq[Queue],
                  fws: ApiFlightWithSplits,
                  splitSource: SplitSource): Seq[String] =
    queueNames.map(q => s"${queuePaxForFlightUsingSplits(fws, splitSource).getOrElse(q, "")}")

  def queuePaxForFlightUsingSplits(fws: ApiFlightWithSplits, splitSource: SplitSource): Map[Queue, Int] =
    fws
      .splits
      .find(_.source == splitSource)
      .map(splits => ApiSplitsToSplitRatio.flightPaxPerQueueUsingSplitsAsRatio(splits, fws))
      .getOrElse(Map())

  def flightWithSplitsToCsvRow(fws: ApiFlightWithSplits): List[String] = {
    val splitsForSources = splitSources.flatMap((ss: SplitSource) => queueSplits(queueNames, fws, ss))
    FlightWithSplitsToCsv.flightWithSplitsToCsvFields(
      fws,
      millisToDateStringFn,
      millisToTimeStringFn
    ) ++
      List(fws.pcpPaxEstimate.toString) ++ splitsForSources
  }

  def actualAPISplitsForFlightInHeadingOrder(flight: ApiFlightWithSplits, headings: Seq[String]): Seq[Double] =
    headings.map(h => Exports.actualAPISplitsAndHeadingsFromFlight(flight).toMap.getOrElse(h, 0.0))
      .map(n => Math.round(n).toDouble)

}

case class DefaultFlightExportTemplate(override val timeZone: DateTimeZone) extends FlightExportTemplate {
  val headings: String = FlightWithSplitsToCsv.arrivalWithSplitsHeadings(queueNames)

  def rowValues(fws: ApiFlightWithSplits): Seq[String] = flightWithSplitsToCsvRow(fws)
}

case class ActualApiFlightExportTemplate(override val timeZone: DateTimeZone) extends FlightExportTemplate {

  override val headings: String = FlightWithSplitsToCsv.flightWithSplitsHeadingsPlusActualApi(queueNames)

  override def rowValues(fws: ApiFlightWithSplits): Seq[String] = (flightWithSplitsToCsvRow(fws) :::
    actualAPISplitsForFlightInHeadingOrder(fws, FlightWithSplitsToCsv.actualApiHeadings).toList).map(s => s"$s")
}

case class CedatFlightExportTemplate(override val timeZone: DateTimeZone) extends FlightExportTemplate {
  override val headings: String = CedatArrivalToCsv.allHeadings(queueNames)

  override def flightWithSplitsToCsvRow(fws: ApiFlightWithSplits): List[String] = {
    val splitsForSources = splitSources.flatMap((ss: SplitSource) => queueSplits(queueNames, fws, ss))
    CedatArrivalToCsv.arrivalAsRawCsvValues(
      fws.apiFlight,
      millisToDateStringFn,
      millisToTimeStringFn
    ) ++ List(fws.apiFlight.bestPcpPaxEstimate.toString) ++ splitsForSources
  }

  override def rowValues(fws: ApiFlightWithSplits): Seq[String] = (flightWithSplitsToCsvRow(fws) :::
    actualAPISplitsForFlightInHeadingOrder(fws, CedatArrivalToCsv.actualApiHeadings).toList).map(s => s"$s")
}
