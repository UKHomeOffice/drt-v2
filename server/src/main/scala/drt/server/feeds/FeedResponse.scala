package server.feeds

import drt.shared.FlightsApi.Flights
import drt.shared.SDateLike
import manifests.passengers.BestAvailableManifest
import services.SDate
import services.graphstages.DqManifests

sealed trait FeedResponse {
  val createdAt: SDateLike
  def length: Int
}

sealed trait ArrivalsFeedResponse extends FeedResponse
sealed trait ManifestsFeedResponse extends FeedResponse

case class StoreFeedImportArrivals(arrivals: Flights)
case object GetFeedImportArrivals

case class ArrivalsFeedSuccess(arrivals: Flights, createdAt: SDateLike) extends ArrivalsFeedResponse {
  override val length: Int = arrivals.flights.length
}
case object ArrivalsFeedSuccessAck

object ArrivalsFeedSuccess {
  def apply(arrivals: Flights): ArrivalsFeedResponse = ArrivalsFeedSuccess(arrivals, SDate.now())
}

case class ArrivalsFeedFailure(responseMessage: String, createdAt: SDateLike) extends ArrivalsFeedResponse {
  override val length: Int = 0
}

object ArrivalsFeedFailure {
  def apply(responseMessage: String): ArrivalsFeedResponse = ArrivalsFeedFailure(responseMessage, SDate.now())
}

case class ManifestsFeedSuccess(manifests: DqManifests, createdAt: SDateLike) extends ManifestsFeedResponse {
  override val length: Int = manifests.length
}

object ManifestsFeedSuccess {
  def apply(manifests: DqManifests): ManifestsFeedResponse = ManifestsFeedSuccess(manifests, SDate.now())
}

case class ManifestsFeedFailure(responseMessage: String, createdAt: SDateLike) extends ManifestsFeedResponse {
  override val length: Int = 0
}

object ManifestsFeedFailure {
  def apply(responseMessage: String): ManifestsFeedResponse = ManifestsFeedFailure(responseMessage, SDate.now())
}

case class BestManifestsFeedSuccess(manifests: Seq[BestAvailableManifest], createdAt: SDateLike) extends ManifestsFeedResponse {
  override val length: Int = manifests.length
}
