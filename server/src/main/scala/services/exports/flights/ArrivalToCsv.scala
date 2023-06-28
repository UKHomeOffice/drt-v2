package services.exports.flights

import drt.shared.CrunchApi.MillisSinceEpoch
import uk.gov.homeoffice.drt.arrivals.Arrival
import uk.gov.homeoffice.drt.ports.FeedSource

object ArrivalToCsv {

  private val arrivalHeadings: String = "IATA,ICAO,Origin,Gate/Stand,Status,Scheduled Date,Scheduled Time,Est Arrival,Act Arrival,Est Chox,Act Chox,Est PCP,Total Pax"
  val arrivalHeadingsWithTransfer: String = arrivalHeadings + ",Transfer Pax"

  def arrivalToCsvFields(arrival: Arrival,
                         millisToDateOnly: MillisSinceEpoch => String,
                         millisToLocalDateTimeString: MillisSinceEpoch => String,
                         paxFeedSourceOrder: List[FeedSource],
                        ): List[String] =
    List(
      arrival.flightCodeString,
      arrival.flightCodeString,
      arrival.Origin.toString,
      arrival.Gate.getOrElse("") + "/" + arrival.Stand.getOrElse(""),
      arrival.displayStatus.description,
      millisToDateOnly(arrival.Scheduled),
      millisToLocalDateTimeString(arrival.Scheduled),
      arrival.Estimated.map(millisToLocalDateTimeString(_)).getOrElse(""),
      arrival.Actual.map(millisToLocalDateTimeString(_)).getOrElse(""),
      arrival.EstimatedChox.map(millisToLocalDateTimeString(_)).getOrElse(""),
      arrival.ActualChox.map(millisToLocalDateTimeString(_)).getOrElse(""),
      arrival.PcpTime.map(millisToLocalDateTimeString(_)).getOrElse(""),
      arrival.bestPaxEstimate(paxFeedSourceOrder).getPcpPax.map(_.toString).getOrElse("")
    )

  def arrivalWithTransferToCsvFields(arrival: Arrival,
                                     millisToDateOnly: MillisSinceEpoch => String,
                                     millisToLocalDateTimeString: MillisSinceEpoch => String,
                                     paxFeedSourceOrder: List[FeedSource],
                                    ): List[String] =
    arrivalToCsvFields(arrival, millisToDateOnly, millisToLocalDateTimeString, paxFeedSourceOrder) :+
      arrival.bestPaxEstimate(paxFeedSourceOrder).passengers.transit.getOrElse(0).toString
}
