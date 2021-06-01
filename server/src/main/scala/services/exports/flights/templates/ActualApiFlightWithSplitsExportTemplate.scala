package services.exports.flights.templates

import drt.shared.ApiFlightWithSplits
import drt.shared.Queues.Queue
import org.joda.time.DateTimeZone

case class ActualApiFlightWithSplitsExportTemplate(override val timeZone: DateTimeZone) extends FlightWithSplitsExportTemplateLike {
  def flightWithSplitsHeadingsPlusActualApi(queueNames: Seq[Queue]): String = arrivalWithSplitsHeadings(queueNames) + "," + actualApiHeadings.mkString(",")

  override val headings: String = flightWithSplitsHeadingsPlusActualApi(queueNames)

  override def rowValues(fws: ApiFlightWithSplits): Seq[String] = (flightWithSplitsToCsvRow(fws) :::
    actualAPISplitsForFlightInHeadingOrder(fws, actualApiHeadings).toList).map(s => s"$s")
}
