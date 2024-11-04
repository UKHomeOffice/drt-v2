package services.crunch.deskrecs

import akka.stream.Materializer
import drt.shared.CrunchApi.{DeskRecMinutes, MillisSinceEpoch, PassengersMinute}
import drt.shared.SimulationMinutes
import services.crunch.desklimits.TerminalDeskLimitsLike
import uk.gov.homeoffice.drt.arrivals.{FlightsWithSplits, Splits}
import uk.gov.homeoffice.drt.model.TQM
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
                     terminalQueueStatuses: Terminal => (Queue, MillisSinceEpoch) => QueueStatus,
                     terminalSplits: Terminal => Option[Splits],
                    )
                    (implicit ec: ExecutionContext, mat: Materializer): Map[TQM, PassengersMinute]

  def terminalLoadsToDesks(minuteMillis: NumericRange[MillisSinceEpoch],
                           loads: Map[TQM, PassengersMinute],
                           deskLimitProviders: TerminalDeskLimitsLike,
                           description: String,
                           terminal: Terminal,
                          )
                          (implicit ec: ExecutionContext, mat: Materializer): Future[DeskRecMinutes]

  def loadsToSimulations(minuteMillis: NumericRange[MillisSinceEpoch],
                         passengersByQueue: Map[TQM, PassengersMinute],
                         deskLimitProviders: TerminalDeskLimitsLike,
                         description: String,
                         terminal: Terminal,
                        )
                        (implicit ec: ExecutionContext, mat: Materializer): Future[SimulationMinutes]
}
