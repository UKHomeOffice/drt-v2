package services.exports.flights.templates

import actors.PartitionedPortStateActor.{FlightsRequest, GetFlightsForTerminals}
import drt.shared.Terminals._
import drt.shared._
import drt.shared.redlist.{LhrRedListDatesImpl, LhrTerminalTypes}
import services.AirportToCountry

object RedList {
  val ports: Iterable[PortCode] = AirportToCountry.airportInfoByIataPortCode.values.collect {
    case AirportInfo(_, _, country, portCode) if drt.shared.RedList.countryToCode.contains(country) => PortCode(portCode)
  }
}

trait LHRFlightsWithSplitsExportWithDiversions {
  val terminal: Terminal
  val start: SDateLike
  val end: SDateLike

  val terminalsToQuery: Seq[Terminal] = terminal match {
    case T2 => Seq(T2)
    case T3 => Seq(T2, T3, T5)
    case T4 => Seq(T2, T3, T4, T5)
    case T5 => Seq(T5)
  }

  val directRedListFilter: LhrFlightDisplayFilter = LhrFlightDisplayFilter(
    RedList.ports.toList.contains, LhrTerminalTypes(LhrRedListDatesImpl))

  val flightsFilter: (Terminal, ApiFlightWithSplits) => Boolean = terminal match {
    case T2 => directRedListFilter.filterExcludingOutgoingDivertedRedListPaxFlight
    case T3 => directRedListFilter.filterIncludingIncomingDivertedRedListPaxFlight
    case T4 => directRedListFilter.filterIncludingIncomingDivertedRedListPaxFlight
    case T5 => directRedListFilter.filterExcludingOutgoingDivertedRedListPaxFlight
  }

  val requestForDiversions: FlightsRequest = GetFlightsForTerminals(start.millisSinceEpoch, end.millisSinceEpoch, terminalsToQuery)
}

case class LHRFlightsWithSplitsWithoutActualApiExportImpl(start: SDateLike, end: SDateLike, terminal: Terminal) extends FlightsWithSplitsWithoutActualApiExport with LHRFlightsWithSplitsExportWithDiversions

case class LHRFlightsWithSplitsWithActualApiExportImpl(start: SDateLike, end: SDateLike, terminal: Terminal) extends FlightsWithSplitsWithActualApiExport with LHRFlightsWithSplitsExportWithDiversions
