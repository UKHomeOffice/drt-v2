package services.exports.flights.templates

import actors.PartitionedPortStateActor.{FlightsRequest, GetFlightsForTerminals}
import drt.shared.Terminals._
import drt.shared.api.Arrival
import drt.shared._
import services.{AirportToCountry, SDate}

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

  val flightsFilter: (Terminal, ApiFlightWithSplits) => Boolean = terminal match {
    case T2 => directRedListFilter.filterExcludingOutgoingDivertedRedListPaxFlight
    case T3 => directRedListFilter.filterIncludingIncomingDivertedRedListPaxFlight
    case T4 => directRedListFilter.filterIncludingIncomingDivertedRedListPaxFlight
    case T5 => directRedListFilter.filterExcludingOutgoingDivertedRedListPaxFlight
  }

  val requestForDiversions: FlightsRequest = GetFlightsForTerminals(start.millisSinceEpoch, end.millisSinceEpoch, terminalsToQuery)

  val uniqueArrivalsWithCodeShares: Seq[ApiFlightWithSplits] => List[(ApiFlightWithSplits, Set[Arrival])]
}

case class LHRFlightsWithSplitsWithoutActualApiExportImpl(start: SDateLike, end: SDateLike, terminal: Terminal) extends FlightsWithSplitsWithoutActualApiExport with LHRFlightsWithSplitsExportWithDiversions

case class LHRFlightsWithSplitsWithActualApiExportImpl(start: SDateLike, end: SDateLike, terminal: Terminal) extends FlightsWithSplitsWithActualApiExport with LHRFlightsWithSplitsExportWithDiversions
