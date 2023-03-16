package services.arrivals

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
}

