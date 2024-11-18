package services.exports.flights.templates

import services.exports.FlightExports
import services.exports.FlightExports.{apiIsInvalid, splitsForSources}
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, ArrivalExportHeadings}
import uk.gov.homeoffice.drt.ports.Terminals._
import uk.gov.homeoffice.drt.ports._
import uk.gov.homeoffice.drt.time.{LocalDate, SDateLike}


trait AdminExport extends FlightsWithSplitsWithActualApiExport {
  private val oldForecastFeedOrder: List[FeedSource] = List(
    ForecastFeedSource,
    HistoricApiFeedSource,
    AclFeedSource,
  )

  override val headings: String = ArrivalExportHeadings
    .arrivalWithSplitsAndRawApiHeadings
    .replace("PCP Pax", "PCP Pax,Predicted PCP Pax,Old Forecast PCP Pax")

  def predictedPcpPax(fws: ApiFlightWithSplits): String =
    if (fws.apiFlight.Origin.isDomesticOrCta) "-"
    else fws.apiFlight.PassengerSources.get(MlFeedSource).flatMap(p => p.getPcpPax.map(_.toString)).getOrElse("-")

  private def oldForecastPcpPax(fws: ApiFlightWithSplits): String =
    if (fws.apiFlight.Origin.isDomesticOrCta) "-"
    else fws.apiFlight.bestPcpPaxEstimate(oldForecastFeedOrder).map(_.toString).getOrElse("0")

  override def flightWithSplitsToCsvRow(fws: ApiFlightWithSplits): List[String] = {
    FlightExports.flightWithSplitsToCsvFields(paxFeedSourceOrder)(fws.apiFlight) ++
      List(predictedPcpPax(fws), oldForecastPcpPax(fws), apiIsInvalid(fws)) ++
      splitsForSources(fws, paxFeedSourceOrder)
  }
}

case class AdminExportImpl(start: LocalDate,
                           end: LocalDate,
                           terminal: Terminal,
                           paxFeedSourceOrder: List[FeedSource],
                          ) extends AdminExport {
  override val flightsFilter: (ApiFlightWithSplits, Terminal) => Boolean = standardFilter
}
