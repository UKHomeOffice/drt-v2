package drt.client.services.handlers

import diode._
import diode.data._
import drt.client.actions.Actions._
import drt.client.services.JSDateConversions.SDate
import drt.client.services._
import drt.shared.CrunchApi.{CrunchMinutes, StaffMinutes}
import drt.shared._
import uk.gov.homeoffice.drt.arrivals.{ArrivalsDiff, FlightsWithSplits, FlightsWithSplitsDiff, SplitsForArrivals}
import uk.gov.homeoffice.drt.ports.FeedSource

import scala.language.postfixOps

class MinuteUpdatesHandler[M](getCurrentViewMode: () => ViewMode,
                              portStateModel: ModelRW[M, Pot[PortState]],
                             ) extends LoggingActionHandler(portStateModel) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case ApplyMinuteUpdates(updates) =>
      val updatedPs: Pot[PortState] = portStateModel.value match {
        case Ready(ps) =>
          updates match {
            case sms: StaffMinutes =>
              val updatedMinutes = sms.minutes
                .map { incoming =>
                  ps.staffMinutes.get(incoming.key) match {
                    case Some(existing) => incoming.maybeUpdated(existing, SDate.now().millisSinceEpoch)
                    case None => Some(incoming)
                  }
                }
                .collect { case Some(cm) => cm }
              val newStaffMinutes = ps.staffMinutes ++ updatedMinutes.map(cm => cm.key -> cm)

              Ready(ps.copy(staffMinutes = newStaffMinutes))

            case cm: CrunchMinutes =>
              val updatedMinutes = cm.minutes
                .map { incoming =>
                  ps.crunchMinutes.get(incoming.key) match {
                    case Some(existing) => incoming.maybeUpdated(existing, SDate.now().millisSinceEpoch)
                    case None => Some(incoming)
                  }
                }
                .collect { case Some(cm) => cm }
              val newCrunchMinutes = ps.crunchMinutes ++ updatedMinutes.map(cm => cm.key -> cm)

              Ready(ps.copy(crunchMinutes = newCrunchMinutes))

            case _ => Ready(ps)
          }
        case other => other
      }

      updated(updatedPs)
  }
}
