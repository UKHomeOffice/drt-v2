package services.crunch

import actors.DrtStaticParameters
import akka.actor.{ActorRef, Props}
import drt.shared.api.Arrival
import services.arrivals.{ArrivalsAdjustmentsLike, ArrivalsAdjustmentsNoop}
import services.{Simulator, SplitsProvider, TryCrunch}
import drt.shared._
import services.graphstages.CrunchMocks
import services.{Simulator, SplitsProvider, TryCrunch}

import scala.collection.immutable.SortedMap

case class TestConfig(initialForecastBaseArrivals: SortedMap[UniqueArrivalWithOrigin, Arrival] = SortedMap(),
                      initialForecastArrivals: SortedMap[UniqueArrivalWithOrigin, Arrival] = SortedMap(),
                      initialLiveBaseArrivals: SortedMap[UniqueArrivalWithOrigin, Arrival] = SortedMap(),
                      initialLiveArrivals: SortedMap[UniqueArrivalWithOrigin, Arrival] = SortedMap(),
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
                      refreshArrivalsOnStart: Boolean = false,
                      refreshManifestsOnStart: Boolean = false,
                      recrunchOnStart: Boolean = false,
                      flexDesks: Boolean = false,
                      maybePassengersActorProps: Option[Props] = None,
                      pcpPaxFn: Arrival => Int = TestDefaults.pcpPaxFn,
                      arrivalsAdjustments: ArrivalsAdjustmentsLike = ArrivalsAdjustmentsNoop
                     )
