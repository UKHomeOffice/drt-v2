package services.crunch

import actors.DrtStaticParameters
import akka.actor.{ActorRef, Props}
import drt.shared.{AirportConfig, FixedPointAssignments, MilliDate, PortState, SDateLike, ShiftAssignments, StaffMovement, UniqueArrival}
import drt.shared.api.Arrival
import services.{Simulator, SplitsProvider, TryCrunch}
import services.graphstages.CrunchMocks

import scala.collection.mutable

case class TestConfig(initialForecastBaseArrivals: mutable.SortedMap[UniqueArrival, Arrival] = mutable.SortedMap(),
                      initialForecastArrivals: mutable.SortedMap[UniqueArrival, Arrival] = mutable.SortedMap(),
                      initialLiveBaseArrivals: mutable.SortedMap[UniqueArrival, Arrival] = mutable.SortedMap(),
                      initialLiveArrivals: mutable.SortedMap[UniqueArrival, Arrival] = mutable.SortedMap(),
                      initialPortState: Option[PortState] = None,
                      airportConfig: AirportConfig = TestDefaults.airportConfig,
                      csvSplitsProvider: SplitsProvider.SplitProvider = (_, _) => None,
                      pcpArrivalTime: Arrival => MilliDate = TestDefaults.pcpForFlightFromSch,
                      expireAfterMillis: Int = DrtStaticParameters.expireAfterMillis,
                      now: () => SDateLike,
                      initialShifts: ShiftAssignments = ShiftAssignments.empty,
                      initialFixedPoints: FixedPointAssignments = FixedPointAssignments.empty,
                      initialStaffMovements: Seq[StaffMovement] = Seq(),
                      logLabel: String = "",
                      cruncher: TryCrunch = CrunchMocks.mockCrunch,
                      simulator: Simulator = CrunchMocks.mockSimulator,
                      maybeAggregatedArrivalsActor: Option[ActorRef] = None,
                      useLegacyManifests: Boolean = false,
                      maxDaysToCrunch: Int = 2,
                      checkRequiredStaffUpdatesOnStartup: Boolean = false,
                      refreshArrivalsOnStart: Boolean = false,
                      recrunchOnStart: Boolean = false,
                      flexDesks: Boolean = false,
                      maybePassengersActorProps: Option[Props] = None,
                      pcpPaxFn: Arrival => Int = TestDefaults.pcpPaxFn
                     )
