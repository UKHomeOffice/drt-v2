package services.exports.flights.templates

import actors.PartitionedPortStateActor.{FlightsRequest, GetFlightsForTerminalDateRange}
import drt.shared.Queues.Queue
import drt.shared.Terminals.Terminal
import drt.shared.{ApiFlightWithSplits, SDateLike}


case class FlightsWithSplitsWithActualApiExport(start: SDateLike, end: SDateLike, terminal: Terminal) extends FlightsWithSplitsExport {

  val request: FlightsRequest = GetFlightsForTerminalDateRange(start.millisSinceEpoch, end.millisSinceEpoch, terminal)

  def flightWithSplitsHeadingsPlusActualApi(queueNames: Seq[Queue]): String = arrivalWithSplitsHeadings(queueNames) + "," + actualApiHeadings.mkString(",")

  override val headings: String = flightWithSplitsHeadingsPlusActualApi(queueNames)

  override def rowValues(fws: ApiFlightWithSplits): Seq[String] = (flightWithSplitsToCsvRow(fws) :::
    actualAPISplitsForFlightInHeadingOrder(fws, actualApiHeadings).toList).map(s => s"$s")
}
