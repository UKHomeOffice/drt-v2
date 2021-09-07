package services.exports.flights.templates

import actors.PartitionedPortStateActor.{FlightsRequest, GetFlightsForTerminals}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.Terminals._
import drt.shared.{redlist, _}
import drt.shared.redlist.{LhrRedListDatesImpl, LhrTerminalTypes, RedListUpdates}
import services.{AirportToCountry, SDate}


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

  val redListUpdates: RedListUpdates

  val directRedListFilter: LhrFlightDisplayFilter =
    LhrFlightDisplayFilter(redListUpdates, AirportToCountry.isRedListed, LhrTerminalTypes(LhrRedListDatesImpl))

  override val flightsFilter: (ApiFlightWithSplits, Terminal) => Boolean =
    directRedListFilter.filterReflectingDivertedRedListFlights

  override val request: FlightsRequest = GetFlightsForTerminals(start.millisSinceEpoch, end.millisSinceEpoch, terminalsToQuery)
}

case class LHRFlightsWithSplitsWithoutActualApiExportWithRedListDiversions(start: SDateLike,
                                                                           end: SDateLike,
                                                                           terminal: Terminal,
                                                                           redListUpdates: RedListUpdates)
  extends FlightsWithSplitsWithoutActualApiExport with LHRFlightsWithSplitsExportWithDiversions

case class LHRFlightsWithSplitsWithActualApiExportWithRedListDiversions(start: SDateLike,
                                                                        end: SDateLike,
                                                                        terminal: Terminal,
                                                                        redListUpdates: RedListUpdates)
  extends FlightsWithSplitsWithActualApiExport with LHRFlightsWithSplitsExportWithDiversions
