package services.exports.flights.templates

import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, ArrivalExportHeadings}
import uk.gov.homeoffice.drt.ports.FeedSource
import uk.gov.homeoffice.drt.ports.Terminals._
import uk.gov.homeoffice.drt.time.SDateLike


trait AdminExport extends FlightsWithSplitsWithActualApiExport {
  val paxFeedSourceOrderWithPredictedPreferred: List[FeedSource]

  override val headings: String = ArrivalExportHeadings
    .arrivalWithSplitsAndRawApiHeadings
    .replace("PCP Pax", "PCP Pax,Predicted PCP Pax,Predicted PCP Pax With Fallback")

  private def predictedPcpPaxWithFallback(fws: ApiFlightWithSplits): String =
    if (fws.apiFlight.Origin.isDomesticOrCta) "-"
    else fws.bestPaxSource(paxFeedSourceOrderWithPredictedPreferred).getPcpPax.map(_.toString).getOrElse("0")

  override def flightWithSplitsToCsvRow(fws: ApiFlightWithSplits): List[String] = {
    flightWithSplitsToCsvFields(fws, millisToLocalDateTimeStringFn) ++
      List(pcpPax(fws), predictedPcpPax(fws), predictedPcpPaxWithFallback(fws), apiIsInvalid(fws)) ++
      splitsForSources(fws)
  }
}

case class AdminExportImpl(start: SDateLike,
                           end: SDateLike,
                           terminal: Terminal,
                           paxFeedSourceOrder: List[FeedSource],
                           paxFeedSourceOrderWithPredictedPreferred: List[FeedSource]) extends AdminExport {
  override val flightsFilter: (ApiFlightWithSplits, Terminal) => Boolean = standardFilter
}
