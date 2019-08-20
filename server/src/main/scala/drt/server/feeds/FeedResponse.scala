package server.feeds

import drt.shared.FlightsApi.Flights
import drt.shared.SDateLike
import manifests.passengers.BestAvailableManifest
import services.SDate
import services.graphstages.DqManifests

sealed trait FeedResponse {
  val createdAt: SDateLike
  val isEmpty: Boolean
  val nonEmpty: Boolean = !isEmpty
}

sealed trait ArrivalsFeedResponse extends FeedResponse
sealed trait ManifestsFeedResponse extends FeedResponse

case class StoreFeedImportArrivals(arrivals: Flights)
case object GetFeedImportArrivals

case class ArrivalsFeedSuccess(arrivals: Flights, createdAt: SDateLike) extends ArrivalsFeedResponse {
  override val isEmpty: Boolean = arrivals.flights.isEmpty
}
case object ArrivalsFeedSuccessAck

object ArrivalsFeedSuccess {
  def apply(arrivals: Flights): ArrivalsFeedResponse = ArrivalsFeedSuccess(arrivals, SDate.now())
}

case class ArrivalsFeedFailure(responseMessage: String, createdAt: SDateLike) extends ArrivalsFeedResponse {
  override val isEmpty: Boolean = true
}

object ArrivalsFeedFailure {
  def apply(responseMessage: String): ArrivalsFeedResponse = ArrivalsFeedFailure(responseMessage, SDate.now())
}

case class ManifestsFeedSuccess(manifests: DqManifests, createdAt: SDateLike) extends ManifestsFeedResponse {
  override val isEmpty: Boolean = manifests.isEmpty
}

object ManifestsFeedSuccess {
  def apply(manifests: DqManifests): ManifestsFeedResponse = ManifestsFeedSuccess(manifests, SDate.now())
}

case class ManifestsFeedFailure(responseMessage: String, createdAt: SDateLike) extends ManifestsFeedResponse {
  override val isEmpty: Boolean = true
}

object ManifestsFeedFailure {
  def apply(responseMessage: String): ManifestsFeedResponse = ManifestsFeedFailure(responseMessage, SDate.now())
}

case class BestManifestsFeedSuccess(manifests: Seq[BestAvailableManifest], createdAt: SDateLike) extends ManifestsFeedResponse {
  override val isEmpty: Boolean = manifests.isEmpty
}
