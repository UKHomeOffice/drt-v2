package controllers.application

import actors.PartitionedPortStateActor.{GetStateForDateRange, GetUpdatesSince, PointInTimeQuery}
import akka.pattern.ask
import controllers.Application
import drt.auth.DesksAndQueuesView
import drt.shared.CrunchApi.{MillisSinceEpoch, PortStateUpdates}
import drt.shared.PortState
import play.api.mvc.{Action, AnyContent, Request}
import upickle.default.write

import scala.concurrent.Future


trait WithPortState {
  self: Application =>

  def getCrunch: Action[AnyContent] = authByRole(DesksAndQueuesView) {
    Action.async { request: Request[AnyContent] =>
      val startMillis = request.queryString.get("start").flatMap(_.headOption.map(_.toLong)).getOrElse(0L)
      val endMillis = request.queryString.get("end").flatMap(_.headOption.map(_.toLong)).getOrElse(0L)

      val maybeSinceMillis = request.queryString.get("since").flatMap(_.headOption.map(_.toLong))

      val eventualUpdates = maybeSinceMillis match {
        case None =>
          portStateForDates(startMillis, endMillis).map(r => Ok(write(r)))
        case Some(sinceMillis) =>
          portStateUpdatesForRange(startMillis, endMillis, sinceMillis).map(r => Ok(write(r)))
      }

      eventualUpdates
        .recoverWith {
          case t =>
            log.error(t, "Error processing request for port state or port state updates")
            Future(InternalServerError)
        }
    }
  }

  private def portStateUpdatesForRange(startMillis: MillisSinceEpoch,
                                       endMillis: MillisSinceEpoch,
                                       sinceMillis: MillisSinceEpoch): Future[Option[PortStateUpdates]] =
    ctrl.portStateActor
      .ask(GetUpdatesSince(sinceMillis, startMillis, endMillis))
      .mapTo[Option[PortStateUpdates]]

  private def portStateForDates(startMillis: MillisSinceEpoch, endMillis: MillisSinceEpoch): Future[PortState] =
    ctrl.portStateActor
      .ask(GetStateForDateRange(startMillis, endMillis))
      .mapTo[PortState]

  def getCrunchSnapshot(pointInTime: MillisSinceEpoch): Action[AnyContent] = authByRole(DesksAndQueuesView) {
    Action.async { request: Request[AnyContent] =>
      val startMillis = request.queryString.get("start").flatMap(_.headOption.map(_.toLong)).getOrElse(0L)
      val endMillis = request.queryString.get("end").flatMap(_.headOption.map(_.toLong)).getOrElse(0L)

      val futureState = ctrl.portStateActor
        .ask(PointInTimeQuery(pointInTime, GetStateForDateRange(startMillis, endMillis)))
        .mapTo[PortState]

      futureState
        .map { updates => Ok(write(updates)) }
        .recoverWith {
          case t =>
            log.error(t, "Error processing request for port state")
            Future(InternalServerError)
        }
    }
  }
}
