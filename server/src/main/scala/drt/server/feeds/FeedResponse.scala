package server.feeds

import drt.shared.FlightsApi.Flights
import drt.shared.SDateLike
import services.SDate
import services.graphstages.{ActualDeskStats, DqManifests}

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

case class ManifestsFeedSuccess(manifests: DqManifests, createdAt: SDateLike) extends FeedResponse

object ManifestsFeedSuccess {
  def apply(manifests: DqManifests): FeedResponse = ManifestsFeedSuccess(manifests, SDate.now())
}

case class ManifestsFeedFailure(responseMessage: String, createdAt: SDateLike) extends FeedResponse

object ManifestsFeedFailure {
  def apply(responseMessage: String): FeedResponse = ManifestsFeedFailure(responseMessage, SDate.now())
}

case class DeskStatsFeedSuccess(deskStats: ActualDeskStats, createdAt: SDateLike) extends FeedResponse

case class DeskStatsFeedFailure(responseMessage: String, createdAt: SDateLike) extends FeedResponse