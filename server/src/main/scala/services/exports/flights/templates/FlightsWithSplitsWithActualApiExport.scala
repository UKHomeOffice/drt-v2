package services.exports.flights.templates

import actors.PartitionedPortStateActor.{FlightsRequest, GetFlightsForTerminalDateRange, GetFlightsForTerminals}
import drt.shared.Queues.Queue
import drt.shared.Terminals.{T2, T3, T4, T5, Terminal}
import drt.shared.{ApiFlightWithSplits, SDateLike}


trait FlightsWithSplitsWithActualApiExport extends FlightsWithSplitsExport {
  val request: FlightsRequest = GetFlightsForTerminalDateRange(start.millisSinceEpoch, end.millisSinceEpoch, terminal)

  def flightWithSplitsHeadingsPlusActualApi(queueNames: Seq[Queue]): String = arrivalWithSplitsHeadings(queueNames) + "," + actualApiHeadings.mkString(",")

  override val headings: String = flightWithSplitsHeadingsPlusActualApi(queueNames)

  override def rowValues(fws: ApiFlightWithSplits): Seq[String] = (flightWithSplitsToCsvRow(fws) :::
    actualAPISplitsForFlightInHeadingOrder(fws, actualApiHeadings).toList).map(s => s"$s")
}

case class FlightsWithSplitsWithActualApiExportImpl(start: SDateLike, end: SDateLike, terminal: Terminal) extends FlightsWithSplitsWithActualApiExport

case class LHRFlightsWithSplitsWithActualApiExportImpl(start: SDateLike, end: SDateLike, terminal: Terminal) extends FlightsWithSplitsWithActualApiExport {
  val terminalsToQuery: Seq[Terminal] = terminal match {
    case T2 => Seq(T2)
    case T3 => Seq(T2, T3, T5)
    case T4 => Seq(T2, T4, T5)
    case T5 => Seq(T5)
  }
  override val request: FlightsRequest = GetFlightsForTerminals(start.millisSinceEpoch, end.millisSinceEpoch, terminalsToQuery)
}

