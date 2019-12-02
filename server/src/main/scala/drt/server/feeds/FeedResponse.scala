package server.feeds

import drt.shared.FlightsApi.Flights
import drt.shared.SDateLike
import manifests.passengers.BestAvailableManifest
import passengersplits.parsing.VoyageManifestParser.VoyageManifest
import services.SDate

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

case class DqManifests(lastSeenFileName: String, manifests: Set[VoyageManifest]) {
  def isEmpty: Boolean = manifests.isEmpty
  def nonEmpty: Boolean = !isEmpty
  def length: Int = manifests.size
  def update(newLastSeenFileName: String, newManifests: Set[VoyageManifest]): DqManifests = {
    val mergedManifests = manifests ++ newManifests
    DqManifests(newLastSeenFileName, mergedManifests)
  }
}
