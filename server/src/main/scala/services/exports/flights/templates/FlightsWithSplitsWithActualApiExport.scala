package services.exports.flights.templates

import actors.PartitionedPortStateActor.{FlightsRequest, GetFlightsForTerminalDateRange}
import akka.NotUsed
import akka.stream.scaladsl.Source
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.Queues.Queue
import drt.shared.Terminals._
import drt.shared._


trait FlightsWithSplitsWithActualApiExport extends FlightsWithSplitsExport {
  val request: FlightsRequest = GetFlightsForTerminalDateRange(start.millisSinceEpoch, end.millisSinceEpoch, terminal)

  def flightWithSplitsHeadingsPlusActualApi(queueNames: Seq[Queue]): String = arrivalWithSplitsHeadings(queueNames) + "," + actualApiHeadings.mkString(",")

  override val headings: String = flightWithSplitsHeadingsPlusActualApi(queueNames)

  override def rowValues(fws: ApiFlightWithSplits): Seq[String] = (flightWithSplitsToCsvRow(fws) :::
    actualAPISplitsForFlightInHeadingOrder(fws, actualApiHeadings).toList).map(s => s"$s")
}

case class FlightsWithSplitsWithActualApiExportImpl(start: SDateLike, end: SDateLike, terminal: Terminal) extends FlightsWithSplitsWithActualApiExport

case class LHRFlightsWithSplitsWithActualApiExportImpl(start: SDateLike, end: SDateLike, terminal: Terminal) extends FlightsWithSplitsWithActualApiExport with LHRFlightsWithSplitsExportWithDiversions {
  override def filterAndSort(flightsStream: Source[FlightsWithSplits, NotUsed]): Source[ApiFlightWithSplits, NotUsed] =
    flightsStream.mapConcat { flights =>
      uniqueArrivalsWithCodeShares(flights.flights.values.toSeq)
        .map(_._1)
        .filter(fws => filter(terminal, fws))
        .sortBy { fws =>
          val arrival = fws.apiFlight
          (arrival.PcpTime, arrival.VoyageNumber.numeric, arrival.Origin.iata)
        }
    }
}
