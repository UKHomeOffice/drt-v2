package controllers.application

import actors.PartitionedPortStateActor.{GetStateForDateRange, GetUpdatesSince, PointInTimeQuery}
import akka.pattern.ask
import akka.util.Timeout
import controllers.Application
import drt.shared.CrunchApi.{MillisSinceEpoch, PortStateUpdates}
import drt.shared.PortState
import play.api.mvc.{Action, AnyContent, Request}
import services.crunch.CrunchManager.{queueDaysToReCrunch, queueDaysToReCrunchWithUpdatedSplits}
import uk.gov.homeoffice.drt.auth.Roles.{DesksAndQueuesView, SuperAdmin}
import upickle.default.write

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt


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
        .ask(PointInTimeQuery(pointInTime, GetStateForDateRange(startMillis, endMillis)))(new Timeout(90.seconds))
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

  def reCrunch: Action[AnyContent] = authByRole(SuperAdmin) {
    Action.async { request: Request[AnyContent] =>
      request.body.asText match {
        case Some("true") =>
          queueDaysToReCrunchWithUpdatedSplits(ctrl.flightsRouterActor, ctrl.crunchManagerActor, airportConfig.crunchOffsetMinutes, ctrl.params.forecastMaxDays, ctrl.now)
          Future.successful(Ok("Re-crunching with updated splits"))
        case _ =>
          queueDaysToReCrunch(ctrl.crunchManagerActor, airportConfig.crunchOffsetMinutes, ctrl.params.forecastMaxDays, ctrl.now)
          Future.successful(Ok("Re-crunching without updating splits"))
      }
    }
  }
}
