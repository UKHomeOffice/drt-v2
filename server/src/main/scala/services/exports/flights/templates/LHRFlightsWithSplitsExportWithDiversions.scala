package services.exports.flights.templates

import actors.PartitionedPortStateActor.{FlightsRequest, GetFlightsForTerminals}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.Terminals._
import drt.shared.{redlist, _}
import drt.shared.redlist.{LhrRedListDatesImpl, LhrTerminalTypes}
import services.{AirportToCountry, SDate}

object RedList {
  def ports(date: SDateLike): Iterable[PortCode] = AirportToCountry.airportInfoByIataPortCode.values.collect {
    case AirportInfo(_, _, country, portCode) if redlist.RedList.countryCodesByName(date.millisSinceEpoch).contains(country) =>
      PortCode(portCode)
  }
}

trait LHRFlightsWithSplitsExportWithDiversions extends FlightsExport {
  val terminal: Terminal
  val start: SDateLike
  val end: SDateLike

  val terminalsToQuery: Seq[Terminal] = terminal match {
    case T2 => Seq(T2)
    case T3 => Seq(T2, T3, T5)
    case T4 => Seq(T2, T3, T4, T5)
    case T5 => Seq(T5)
  }

  val isRedListedForDate: (PortCode, MillisSinceEpoch) => Boolean =
    (origin, scheduled) => RedList.ports(SDate(scheduled)).toList.contains(origin)

  val directRedListFilter: LhrFlightDisplayFilter =
    LhrFlightDisplayFilter(isRedListedForDate, LhrTerminalTypes(LhrRedListDatesImpl))

  override val flightsFilter: (ApiFlightWithSplits, Terminal) => Boolean =
    directRedListFilter.filterReflectingDivertedRedListFlights

  override val request: FlightsRequest = GetFlightsForTerminals(start.millisSinceEpoch, end.millisSinceEpoch, terminalsToQuery)
}

case class LHRFlightsWithSplitsWithoutActualApiExportWithRedListDiversions(start: SDateLike, end: SDateLike, terminal: Terminal) extends FlightsWithSplitsWithoutActualApiExport with LHRFlightsWithSplitsExportWithDiversions

case class LHRFlightsWithSplitsWithActualApiExportWithRedListDiversions(start: SDateLike, end: SDateLike, terminal: Terminal) extends FlightsWithSplitsWithActualApiExport with LHRFlightsWithSplitsExportWithDiversions
