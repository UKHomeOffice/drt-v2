package services.crunch

import actors.DrtStaticParameters
import akka.actor.ActorRef
import drt.shared._
import manifests.ManifestLookupLike
import services.arrivals.{ArrivalsAdjustmentsLike, ArrivalsAdjustmentsNoop}
import services.graphstages.CrunchMocks
import services.{TryCrunchWholePax, TrySimulator}
import uk.gov.homeoffice.drt.arrivals.{Arrival, ArrivalsDiff, FeedArrival, ForecastArrival, LiveArrival}
import uk.gov.homeoffice.drt.egates.PortEgateBanksUpdates
import uk.gov.homeoffice.drt.ports.AirportConfig
import uk.gov.homeoffice.drt.time.SDate.implicits.sdateFromMillisLocal
import uk.gov.homeoffice.drt.time.SDateLike

import scala.concurrent.Future

case class TestConfig(initialForecastBaseArrivals: Seq[ForecastArrival] = Seq.empty,
                      initialLiveArrivals: Seq[LiveArrival] = Seq.empty,
                      initialPortState: Option[PortState] = None,
                      airportConfig: AirportConfig = TestDefaults.airportConfig,
                      expireAfterMillis: Int = DrtStaticParameters.expireAfterMillis,
                      now: () => SDateLike,
                      initialShifts: ShiftAssignments = ShiftAssignments.empty,
                      initialFixedPoints: FixedPointAssignments = FixedPointAssignments.empty,
                      initialStaffMovements: Seq[StaffMovement] = Seq(),
                      logLabel: String = "",
                      cruncher: TryCrunchWholePax = CrunchMocks.mockCrunchWholePax,
                      simulator: TrySimulator = CrunchMocks.mockSimulator,
                      maybeAggregatedArrivalsActor: Option[ActorRef] = None,
                      useLegacyManifests: Boolean = false,
                      forecastMaxDays: Int = 2,
                      flexDesks: Boolean = false,
                      arrivalsAdjustments: ArrivalsAdjustmentsLike = ArrivalsAdjustmentsNoop,
                      maybeEgatesProvider: Option[() => Future[PortEgateBanksUpdates]] = None,
                      setPcpTimes: Seq[Arrival] => Future[Seq[Arrival]] = TestDefaults.setPcpFromSch,
                      addArrivalPredictions: ArrivalsDiff => Future[ArrivalsDiff] = diff => Future.successful(diff),
                      passengerAdjustments: List[FeedArrival] => Future[List[FeedArrival]] = arrivals => Future.successful(arrivals),
                      historicManifestLookup: Option[ManifestLookupLike] = None,
                     )
