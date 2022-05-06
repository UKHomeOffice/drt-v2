package server.feeds

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.Flights
import manifests.passengers.BestAvailableManifest
import passengersplits.parsing.VoyageManifestParser.VoyageManifest
import services.SDate
import uk.gov.homeoffice.drt.arrivals.TotalPaxSource
import uk.gov.homeoffice.drt.ports.FeedSource
import uk.gov.homeoffice.drt.time.SDateLike

sealed trait FeedResponse {
  val createdAt: SDateLike

  def length: Int

}

sealed trait ArrivalsFeedResponse extends FeedResponse

sealed trait ManifestsFeedResponse extends FeedResponse

case class StoreFeedImportArrivals(arrivals: Flights)

case object GetFeedImportArrivals

case class ArrivalsFeedSuccess(arrivals: Flights, feedSource: FeedSource, createdAt: SDateLike) extends ArrivalsFeedResponse {
  override val length: Int = arrivals.flights.size
}

case object ArrivalsFeedSuccessAck

object ArrivalsFeedSuccess {
  def apply(arrivals: Flights, feedSource: FeedSource): ArrivalsFeedResponse = ArrivalsFeedSuccess(
    Flights(arrivals.flights
      .map { arrival =>
        arrival.copy(TotalPax = arrival.TotalPax ++ Set(TotalPaxSource(arrival.ActPax.getOrElse(0)-arrival.TranPax.getOrElse(0), feedSource, None)))
      }), feedSource,
    SDate.now())
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

case class DqManifests(lastProcessedMarker: MillisSinceEpoch, manifests: Iterable[VoyageManifest]) {
  def isEmpty: Boolean = manifests.isEmpty

  def nonEmpty: Boolean = !isEmpty

  def length: Int = manifests.size

  def update(newLastProcessedMarker: MillisSinceEpoch, newManifests: Set[VoyageManifest]): DqManifests = {
    val mergedManifests = manifests ++ newManifests
    DqManifests(lastProcessedMarker, mergedManifests)
  }
}
