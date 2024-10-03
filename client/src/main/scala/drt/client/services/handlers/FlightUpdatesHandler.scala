package drt.client.services.handlers

import diode._
import diode.data._
import drt.client.actions.Actions._
import drt.client.services._
import drt.shared._
import uk.gov.homeoffice.drt.arrivals.{ArrivalsDiff, FlightsWithSplits, FlightsWithSplitsDiff, SplitsForArrivals}
import uk.gov.homeoffice.drt.ports.FeedSource

import scala.language.postfixOps

class FlightUpdatesHandler[M](getCurrentViewMode: () => ViewMode,
                              portStateModel: ModelRW[M, Pot[PortState]],
                              paxFeedSourceOrder: ModelR[M, List[FeedSource]],
                             ) extends LoggingActionHandler(portStateModel) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case ApplyFlightUpdates(updates) =>
      val applyTo = updates match {
        case diff: FlightsWithSplitsDiff => diff.applyTo _
        case diff: ArrivalsDiff => diff.applyTo _
        case diff: SplitsForArrivals => diff.applyTo _
      }
      val updatedPs: Pot[PortState] = portStateModel.value match {
        case Ready(ps) =>
          val (updatedFlights, _) = applyTo(FlightsWithSplits(ps.flights), System.currentTimeMillis(), paxFeedSourceOrder.value)
          Ready(ps.copy(flights = updatedFlights.flights))
        case other => other
      }

      updated(updatedPs)
  }
}
