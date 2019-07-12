package server.feeds

import drt.shared.FlightsApi.Flights
import drt.shared.{Arrival, ArrivalKey, SDateLike}
import manifests.passengers.BestAvailableManifest
import services.SDate
import services.graphstages.DqManifests

import scala.collection.immutable.SortedMap

sealed trait FeedResponse {
  val createdAt: SDateLike
}

sealed trait ArrivalsFeedResponse extends FeedResponse
sealed trait ManifestsFeedResponse extends FeedResponse

case class StoreFeedImportArrivals(arrivals: Flights)
case object GetFeedImportArrivals

case class ArrivalsFeedSuccess(arrivals: Flights, createdAt: SDateLike) extends ArrivalsFeedResponse
case object ArrivalsFeedSuccessAck

object ArrivalsFeedSuccess {
  def apply(arrivals: Flights): ArrivalsFeedResponse = ArrivalsFeedSuccess(arrivals, SDate.now())
}

case class ArrivalsFeedFailure(responseMessage: String, createdAt: SDateLike) extends ArrivalsFeedResponse

object ArrivalsFeedFailure {
  def apply(responseMessage: String): ArrivalsFeedResponse = ArrivalsFeedFailure(responseMessage, SDate.now())
}

case class ManifestsFeedSuccess(manifests: DqManifests, createdAt: SDateLike) extends ManifestsFeedResponse

object ManifestsFeedSuccess {
  def apply(manifests: DqManifests): ManifestsFeedResponse = ManifestsFeedSuccess(manifests, SDate.now())
}

case class ManifestsFeedFailure(responseMessage: String, createdAt: SDateLike) extends ManifestsFeedResponse

object ManifestsFeedFailure {
  def apply(responseMessage: String): ManifestsFeedResponse = ManifestsFeedFailure(responseMessage, SDate.now())
}

case class BestManifestsFeedSuccess(manifests: Seq[BestAvailableManifest], createdAt: SDateLike) extends ManifestsFeedResponse
