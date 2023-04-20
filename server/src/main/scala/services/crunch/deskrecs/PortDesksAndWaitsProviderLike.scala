package services.crunch.deskrecs

import akka.stream.Materializer
import drt.shared.CrunchApi.{DeskRecMinutes, MillisSinceEpoch, PassengersMinute}
import drt.shared.{SimulationMinutes, TQM}
import services.crunch.desklimits.TerminalDeskLimitsLike
import uk.gov.homeoffice.drt.arrivals.FlightsWithSplits
import uk.gov.homeoffice.drt.ports.Queues.{Queue, QueueStatus}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.redlist.RedListUpdates

import scala.collection.immutable.{Map, NumericRange}
import scala.concurrent.{ExecutionContext, Future}

trait PortDesksAndWaitsProviderLike {
  val minutesToCrunch: Int
  val crunchOffsetMinutes: Int

  def flightsToLoads(minuteMillis: NumericRange[MillisSinceEpoch],
                     flights: FlightsWithSplits,
                     redListUpdates: RedListUpdates,
                     terminalQueueStatuses: Terminal => (Queue, MillisSinceEpoch) => QueueStatus)
                    (implicit ec: ExecutionContext, mat: Materializer): Map[TQM, PassengersMinute]

  def loadsToDesks(minuteMillis: NumericRange[MillisSinceEpoch],
                   loads: Map[TQM, PassengersMinute],
                   deskLimitProviders: Map[Terminal, TerminalDeskLimitsLike])
                  (implicit ec: ExecutionContext, mat: Materializer): Future[DeskRecMinutes]

  def loadsToSimulations(minuteMillis: NumericRange[MillisSinceEpoch],
                         passengersByQueue: Map[TQM, PassengersMinute],
                         deskLimitProviders: Map[Terminal, TerminalDeskLimitsLike])
                        (implicit ec: ExecutionContext, mat: Materializer): Future[SimulationMinutes]
}
