package server.feeds

import drt.shared.FlightsApi.Flights
import drt.shared.SDateLike
import services.graphstages.ActualDeskStats

sealed trait FeedResponse {
  val createdAt: SDateLike
}

case class ArrivalsFeedSuccess(arrivals: Flights, createdAt: SDateLike) extends FeedResponse

case class ArrivalsFeedFailure(responseMessage: String, createdAt: SDateLike) extends FeedResponse

case class DeskStatsFeedSuccess(deskStats: ActualDeskStats, createdAt: SDateLike) extends FeedResponse

case class DeskStatsFeedFailure(responseMessage: String, createdAt: SDateLike) extends FeedResponse