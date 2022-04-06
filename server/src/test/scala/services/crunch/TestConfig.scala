package services.crunch

import actors.DrtStaticParameters
import akka.actor.{ActorRef, Props}
import drt.shared._
import services.arrivals.{ArrivalsAdjustmentsLike, ArrivalsAdjustmentsNoop}
import services.graphstages.CrunchMocks
import services.{TryCrunch, TrySimulator}
import uk.gov.homeoffice.drt.arrivals.{Arrival, UniqueArrival}
import uk.gov.homeoffice.drt.egates.PortEgateBanksUpdates
import uk.gov.homeoffice.drt.ports.AirportConfig
import uk.gov.homeoffice.drt.redlist.RedListUpdates
import uk.gov.homeoffice.drt.time.SDateLike

import scala.collection.immutable.SortedMap
import scala.concurrent.Future

case class TestConfig(initialForecastBaseArrivals: SortedMap[UniqueArrival, Arrival] = SortedMap(),
                      initialForecastArrivals: SortedMap[UniqueArrival, Arrival] = SortedMap(),
                      initialLiveBaseArrivals: SortedMap[UniqueArrival, Arrival] = SortedMap(),
                      initialLiveArrivals: SortedMap[UniqueArrival, Arrival] = SortedMap(),
                      initialPortState: Option[PortState] = None,
                      airportConfig: AirportConfig = TestDefaults.airportConfig,
                      expireAfterMillis: Int = DrtStaticParameters.expireAfterMillis,
                      now: () => SDateLike,
                      initialShifts: ShiftAssignments = ShiftAssignments.empty,
                      initialFixedPoints: FixedPointAssignments = FixedPointAssignments.empty,
                      initialStaffMovements: Seq[StaffMovement] = Seq(),
                      logLabel: String = "",
                      cruncher: TryCrunch = CrunchMocks.mockCrunch,
                      simulator: TrySimulator = CrunchMocks.mockSimulator,
                      maybeAggregatedArrivalsActor: Option[ActorRef] = None,
                      useLegacyManifests: Boolean = false,
                      maxDaysToCrunch: Int = 2,
                      refreshArrivalsOnStart: Boolean = false,
                      refreshManifestsOnStart: Boolean = false,
                      recrunchOnStart: Boolean = false,
                      flexDesks: Boolean = false,
                      maybePassengersActorProps: Option[Props] = None,
                      arrivalsAdjustments: ArrivalsAdjustmentsLike = ArrivalsAdjustmentsNoop,
                      maybeEgatesProvider: Option[() => Future[PortEgateBanksUpdates]] = None,
                      setPcpTimes: ArrivalsDiff => Future[ArrivalsDiff] = TestDefaults.setPcpFromSch,
                      addTouchdownPredictions: ArrivalsDiff => Future[ArrivalsDiff] = diff => Future.successful(diff),
                     )
