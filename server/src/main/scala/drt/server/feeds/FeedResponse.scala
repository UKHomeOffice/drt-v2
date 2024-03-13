package drt.server.feeds

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.Flights
import passengersplits.parsing.VoyageManifestParser.VoyageManifest
import uk.gov.homeoffice.drt.arrivals._
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

sealed trait FeedArrival {
  val operator: String
  val maxPax: Int
  val totalPax: Int
  val terminal: Terminal
  val voyageNumber: Int
  val carrierCode: String
  val flightCodeSuffix: Option[String]
  val origin: String
  val scheduled: Long
  lazy val unique: UniqueArrival = UniqueArrival(voyageNumber, terminal, scheduled, PortCode(origin))

  def toArrival(feedSource: FeedSource): Arrival
}

case class ForecastArrival(operator: String,
                           maxPax: Int,
                           totalPax: Int,
                           terminal: Terminal,
                           voyageNumber: Int,
                           carrierCode: String,
                           flightCodeSuffix: Option[String],
                           origin: String,
                           scheduled: Long,
                          ) extends FeedArrival {
  override def toArrival(feedSource: FeedSource): Arrival = Arrival(
    Operator = Option(Operator(operator)),
    CarrierCode = CarrierCode(carrierCode),
    VoyageNumber = VoyageNumber(voyageNumber),
    FlightCodeSuffix = flightCodeSuffix.map(FlightCodeSuffix),
    Status = ArrivalStatus("Scheduled"),
    Estimated = None,
    Predictions = Predictions(0L, Map()),
    Actual = None,
    EstimatedChox = None,
    ActualChox = None,
    Gate = None,
    Stand = None,
    MaxPax = Option(maxPax),
    RunwayID = None,
    BaggageReclaimId = None,
    AirportID = PortCode(""),
    Terminal = terminal,
    Origin = PortCode(origin),
    Scheduled = scheduled,
    PcpTime = None,
    FeedSources = Set(feedSource),
    CarrierScheduled = None,
    ScheduledDeparture = None,
    RedListPax = None,
    PassengerSources = Map(feedSource -> Passengers(Option(totalPax), None))
  )
}

case class LiveArrival(operator: String,
                       maxPax: Int,
                       totalPax: Int,
                       terminal: Terminal,
                       voyageNumber: Int,
                       carrierCode: String,
                       flightCodeSuffix: Option[String],
                       origin: String,
                       scheduled: Long,
                       estimated: Option[Long],
                       touchdown: Option[Long],
                       estChox: Option[Long],
                       actChox: Option[Long],
                       status: String,
                       gate: Option[String],
                       stand: Option[String],
                       runway: Option[String],
                       baggageReclaim: Option[String],
                      ) extends FeedArrival {
  override def toArrival(feedSource: FeedSource): Arrival = Arrival(
    Operator = Option(Operator(operator)),
    CarrierCode = CarrierCode(carrierCode),
    VoyageNumber = VoyageNumber(voyageNumber),
    FlightCodeSuffix = flightCodeSuffix.map(FlightCodeSuffix),
    Status = ArrivalStatus(status),
    Estimated = estimated,
    Predictions = Predictions(0L, Map()),
    Actual = touchdown,
    EstimatedChox = estChox,
    ActualChox = actChox,
    Gate = gate,
    Stand = stand,
    MaxPax = Option(maxPax),
    RunwayID = runway,
    BaggageReclaimId = baggageReclaim,
    AirportID = PortCode(""),
    Terminal = terminal,
    Origin = PortCode(origin),
    Scheduled = scheduled,
    PcpTime = None,
    FeedSources = Set(feedSource),
    CarrierScheduled = None,
    ScheduledDeparture = None,
    RedListPax = None,
    PassengerSources = Map(feedSource -> Passengers(Option(totalPax), None))
  )
}

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
