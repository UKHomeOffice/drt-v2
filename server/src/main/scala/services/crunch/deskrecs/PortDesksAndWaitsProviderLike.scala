package services.crunch.deskrecs

import akka.stream.Materializer
import drt.shared.CrunchApi.{DeskRecMinutes, MillisSinceEpoch}
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.{SimulationMinutes, TQM}
import services.crunch.desklimits.TerminalDeskLimitsLike
import services.graphstages.Crunch.LoadMinute
import services.graphstages.QueueStatusProviders.DynamicQueueStatusProvider
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.redlist.RedListUpdates

import scala.collection.immutable.{Map, NumericRange}
import scala.concurrent.{ExecutionContext, Future}

trait PortDesksAndWaitsProviderLike {
  val minutesToCrunch: Int
  val crunchOffsetMinutes: Int

  def flightsToLoads(flights: FlightsWithSplits, redListUpdates: RedListUpdates, dynamicQueueStatusProvider: DynamicQueueStatusProvider)
                    (implicit ec: ExecutionContext, mat: Materializer): Future[Map[TQM, LoadMinute]]

  def loadsToDesks(minuteMillis: NumericRange[MillisSinceEpoch],
                   loads: Map[TQM, LoadMinute],
                   deskLimitProviders: Map[Terminal, TerminalDeskLimitsLike],
                   dynamicQueueStatusProvider: DynamicQueueStatusProvider)
                  (implicit ec: ExecutionContext, mat: Materializer): Future[DeskRecMinutes]

  def loadsToSimulations(minuteMillis: NumericRange[MillisSinceEpoch],
                         loadsByQueue: Map[TQM, LoadMinute],
                         deskLimitProviders: Map[Terminal, TerminalDeskLimitsLike])
                        (implicit ec: ExecutionContext, mat: Materializer): Future[SimulationMinutes]
}
