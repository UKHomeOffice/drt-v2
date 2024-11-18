package services.exports.flights.templates

import actors.PartitionedPortStateActor.{FlightsRequest, GetFlightsForTerminals}
import drt.shared._
import drt.shared.redlist.{LhrRedListDatesImpl, LhrTerminalTypes}
import services.AirportInfoService
import uk.gov.homeoffice.drt.arrivals.ApiFlightWithSplits
import uk.gov.homeoffice.drt.ports.FeedSource
import uk.gov.homeoffice.drt.ports.Terminals._
import uk.gov.homeoffice.drt.redlist.RedListUpdates
import uk.gov.homeoffice.drt.time.{LocalDate, SDate}


trait LHRFlightsWithSplitsExportWithDiversions extends FlightsExport {
  val terminal: Terminal
  val start: LocalDate
  val end: LocalDate

  val terminalsToQuery: Seq[Terminal] = terminal match {
    case T2 => Seq(T2)
    case T3 => Seq(T2, T3, T5)
    case T4 => Seq(T2, T3, T4, T5)
    case T5 => Seq(T5)
  }

  val redListUpdates: RedListUpdates

  val directRedListFilter: LhrFlightDisplayFilter =
    LhrFlightDisplayFilter(redListUpdates, AirportInfoService.isRedListed, LhrTerminalTypes(LhrRedListDatesImpl))

  override val flightsFilter: (ApiFlightWithSplits, Terminal) => Boolean =
    directRedListFilter.filterReflectingDivertedRedListFlights

  override val request: FlightsRequest = GetFlightsForTerminals(SDate(start).millisSinceEpoch, SDate(end).addDays(1).addMinutes(-1).millisSinceEpoch, terminalsToQuery)
}

case class LHRFlightsWithSplitsWithoutActualApiExportWithRedListDiversions(start: LocalDate,
                                                                           end: LocalDate,
                                                                           terminal: Terminal,
                                                                           redListUpdates: RedListUpdates,
                                                                           paxFeedSourceOrder: List[FeedSource],
                                                                          )
  extends FlightsWithSplitsWithoutActualApiExport with LHRFlightsWithSplitsExportWithDiversions

case class LHRFlightsWithSplitsWithActualApiExportWithRedListDiversions(start: LocalDate,
                                                                        end: LocalDate,
                                                                        terminal: Terminal,
                                                                        redListUpdates: RedListUpdates,
                                                                        paxFeedSourceOrder: List[FeedSource],
                                                                       )
  extends FlightsWithSplitsWithActualApiExport with LHRFlightsWithSplitsExportWithDiversions
