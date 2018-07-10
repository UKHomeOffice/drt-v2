package server.feeds

import drt.shared.FlightsApi.Flights
import drt.shared.SDateLike
import services.SDate
import services.graphstages.ActualDeskStats

sealed trait FeedResponse {
  val createdAt: SDateLike
}

case class ArrivalsFeedSuccess(arrivals: Flights, createdAt: SDateLike) extends FeedResponse

object ArrivalsFeedSuccess {
  def apply(arrivals: Flights): FeedResponse = ArrivalsFeedSuccess(arrivals, SDate.now())
}

case class ArrivalsFeedFailure(responseMessage: String, createdAt: SDateLike) extends FeedResponse

object ArrivalsFeedFailure {
  def apply(responseMessage: String): FeedResponse = ArrivalsFeedFailure(responseMessage, SDate.now())
}

case class DeskStatsFeedSuccess(deskStats: ActualDeskStats, createdAt: SDateLike) extends FeedResponse

case class DeskStatsFeedFailure(responseMessage: String, createdAt: SDateLike) extends FeedResponse