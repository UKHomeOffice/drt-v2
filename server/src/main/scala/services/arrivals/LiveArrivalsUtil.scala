package services.arrivals

import services.SDate
import uk.gov.homeoffice.drt.arrivals.Arrival

object LiveArrivalsUtil {

  def mergePortFeedWithLiveBase(portFeedArrival: Arrival, baseLiveArrival: Arrival): Arrival = {
    portFeedArrival.copy(
      ActualChox = if (portFeedArrival.ActualChox.isEmpty) baseLiveArrival.ActualChox else portFeedArrival.ActualChox,
      Actual = if (portFeedArrival.Actual.isEmpty) baseLiveArrival.Actual else portFeedArrival.Actual,
      EstimatedChox = if (portFeedArrival.EstimatedChox.isEmpty) baseLiveArrival.EstimatedChox else portFeedArrival.EstimatedChox,
      Estimated = if (portFeedArrival.Estimated.isEmpty) baseLiveArrival.Estimated else portFeedArrival.Estimated,
      Gate = if (portFeedArrival.Gate.isEmpty) baseLiveArrival.Gate else portFeedArrival.Gate,
      Status = if (portFeedArrival.Status.description == "UNK") baseLiveArrival.Status else portFeedArrival.Status,
      ScheduledDeparture = if (portFeedArrival.ScheduledDeparture.isEmpty) baseLiveArrival.ScheduledDeparture else portFeedArrival.ScheduledDeparture,
      TotalPax = portFeedArrival.TotalPax ++ baseLiveArrival.TotalPax
    )
  }

  def printArrival(a: Arrival): String = {
    s"""
       |flightCode: ${a.flightCodeString}
       |terminal: ${a.Terminal}
       |scheduled: ${SDate(a.Scheduled).toISOString()}
       |Est: ${a.Estimated.map(d => SDate(d).toISOString())}
       |EstChox: ${a.EstimatedChox.map(d => SDate(d).toISOString())}
       |Act: ${a.Actual.map(d => SDate(d).toISOString())}
       |ActChox: ${a.ActualChox.map(d => SDate(d).toISOString())}
       |Status: ${a.Status.description}
       |Gate: ${a.Gate}
       |PCP: ${a.PcpTime.map(d => SDate(d).toISOString())}
       |scheduledDeparture: ${a.ScheduledDeparture.map(d => SDate(d).toISOString())}
       |""".stripMargin
  }

}

