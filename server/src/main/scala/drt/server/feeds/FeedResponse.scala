package drt.server.feeds

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.Flights
import passengersplits.parsing.VoyageManifestParser.VoyageManifest
import uk.gov.homeoffice.drt.arrivals.{UniqueArrival, _}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{FeedSource, PortCode}
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

sealed trait FeedResponse {
  val createdAt: SDateLike

  def length: Int
}

sealed trait ArrivalsFeedResponse extends FeedResponse

sealed trait ManifestsFeedResponse extends FeedResponse

case class StoreFeedImportArrivals(arrivals: Flights)

case object GetFeedImportArrivals

case class ArrivalsFeedSuccess(arrivals: Seq[FeedArrival], createdAt: SDateLike) extends ArrivalsFeedResponse {
  override val length: Int = arrivals.size
}

object ArrivalsFeedSuccess {
  def apply(arrivals: Seq[FeedArrival]): ArrivalsFeedResponse = ArrivalsFeedSuccess(arrivals, SDate.now())
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

case class DqManifests(lastProcessedMarker: MillisSinceEpoch, manifests: Iterable[VoyageManifest]) {
  def length: Int = manifests.size
}
