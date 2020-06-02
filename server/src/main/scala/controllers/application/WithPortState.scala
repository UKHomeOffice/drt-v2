package controllers.application

import actors.pointInTime.CrunchStateReadActor
import actors.{DrtStaticParameters, GetPortState, GetUpdatesSince}
import akka.actor.PoisonPill
import akka.pattern.ask
import controllers.Application
import drt.auth.DesksAndQueuesView
import drt.shared.CrunchApi.{MillisSinceEpoch, PortStateUpdates}
import drt.shared.PortState
import play.api.mvc.{Action, AnyContent, Request}
import services.SDate
import upickle.default.write

import scala.concurrent.Future
import scala.concurrent.duration._


trait WithPortState {
  self: Application =>

  def getCrunch: Action[AnyContent] = authByRole(DesksAndQueuesView) {
    Action.async { request: Request[AnyContent] =>
      val startMillis = request.queryString.get("start").flatMap(_.headOption.map(_.toLong)).getOrElse(0L)
      val endMillis = request.queryString.get("end").flatMap(_.headOption.map(_.toLong)).getOrElse(0L)

      val maybeSinceMillis = request.queryString.get("since").flatMap(_.headOption.map(_.toLong))

      val maybePointInTime = if (endMillis < SDate.now().millisSinceEpoch) {
        val oneHourMillis = 1000 * 60 * 60
        Option(endMillis + oneHourMillis * 2)
      } else None

      val eventualUpdates = maybeSinceMillis match {
        case None =>
          futureCrunchState[PortState](maybePointInTime, startMillis, endMillis, GetPortState(startMillis, endMillis))
            .map { updates => Ok(write(updates)) }
        case Some(sinceMillis) =>
          futureCrunchState[PortStateUpdates](maybePointInTime, startMillis, endMillis, GetUpdatesSince(sinceMillis, startMillis, endMillis))
            .map { updates => Ok(write(updates)) }
      }

      eventualUpdates
        .recoverWith {
          case t =>
            log.error(t, "Error processing request for port state or port state updates")
            Future(InternalServerError)
        }
    }
  }

  def getCrunchSnapshot(pointInTime: MillisSinceEpoch): Action[AnyContent] = authByRole(DesksAndQueuesView) {
    Action.async { request: Request[AnyContent] =>
      val startMillis = request.queryString.get("start").flatMap(_.headOption.map(_.toLong)).getOrElse(0L)
      val endMillis = request.queryString.get("end").flatMap(_.headOption.map(_.toLong)).getOrElse(0L)

      val message = GetPortState(startMillis, endMillis)
      val futureState = futureCrunchState[PortState](Option(pointInTime), startMillis, endMillis, message)

      futureState
        .map { updates => Ok(write(updates)) }
        .recoverWith {
          case t =>
            log.error("Error processing request for port state", t)
            Future(InternalServerError)
        }
    }
  }

  def futureCrunchState[X](maybePointInTime: Option[MillisSinceEpoch], startMillis: MillisSinceEpoch, endMillis: MillisSinceEpoch, request: Any): Future[Option[X]] = {
    maybePointInTime match {
      case Some(pit) =>
        log.debug(s"Snapshot crunch state query ${SDate(pit).toISOString()}")
        val tempActor = system.actorOf(CrunchStateReadActor.props(airportConfig.portStateSnapshotInterval, SDate(pit), DrtStaticParameters.expireAfterMillis, airportConfig.queuesByTerminal, startMillis, endMillis))
        val futureResult = tempActor.ask(request)(30 seconds).mapTo[Option[X]]
        futureResult.foreach(_ => tempActor ! PoisonPill)
        futureResult
      case _ =>
        ctrl.portStateActor.ask(request)(30 seconds).mapTo[Option[X]]
    }
  }

}
