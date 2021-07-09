package services.exports.flights.templates

import actors.PartitionedPortStateActor.{FlightsRequest, GetFlightsForTerminalDateRange, GetFlightsForTerminals}
import akka.NotUsed
import akka.stream.scaladsl.Source
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.Queues.Queue
import drt.shared.{AirportInfo, ApiFlightWithSplits, LhrFlightDisplayFilter, PortCode, RedList, SDateLike}
import drt.shared.Terminals.{T2, T3, T4, T5, Terminal}
import services.{AirportToCountry, SDate}

trait FlightsWithSplitsWithoutActualApiExport extends FlightsWithSplitsExport {
  val request: FlightsRequest = GetFlightsForTerminalDateRange(start.millisSinceEpoch, end.millisSinceEpoch, terminal)
}

case class FlightsWithSplitsWithoutActualApiExportImpl(start: SDateLike, end: SDateLike, terminal: Terminal) extends FlightsWithSplitsWithoutActualApiExport

trait LHRFlightsWithSplitsExportWithDiversions {
  val terminal: Terminal
  val start: SDateLike
  val end: SDateLike

  val terminalsToQuery: Seq[Terminal] = terminal match {
    case T2 => Seq(T2)
    case T3 => Seq(T2, T3, T5)
    case T4 => Seq(T2, T4, T5)
    case T5 => Seq(T5)
  }

  val redListPorts: Iterable[PortCode] = AirportToCountry.airportInfoByIataPortCode.values.collect {
    case AirportInfo(_, _, country, portCode) if RedList.countryToCode.contains(country) => PortCode(portCode)
  }

  val directRedListFilter: LhrFlightDisplayFilter = LhrFlightDisplayFilter(redListPorts.toList.contains, SDate("2021-06-01T00:00").millisSinceEpoch, SDate("2021-06-29T00:00").millisSinceEpoch)

  val filter: (Terminal, ApiFlightWithSplits) => Boolean = terminal match {
    case T2 => directRedListFilter.filterExcludingOutgoingDivertedRedListPaxFlight
    case T3 => directRedListFilter.filterIncludingIncomingDivertedRedListPaxFlight
    case T4 => directRedListFilter.filterIncludingIncomingDivertedRedListPaxFlight
    case T5 => directRedListFilter.filterExcludingOutgoingDivertedRedListPaxFlight
  }

  val requestForDiversions: FlightsRequest = GetFlightsForTerminals(start.millisSinceEpoch, end.millisSinceEpoch, terminalsToQuery)

}

case class LHRFlightsWithSplitsWithoutActualApiExportImpl(start: SDateLike, end: SDateLike, terminal: Terminal) extends FlightsWithSplitsWithoutActualApiExport with LHRFlightsWithSplitsExportWithDiversions {
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

