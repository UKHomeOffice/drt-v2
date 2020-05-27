package controllers.application

import actors.pointInTime.CrunchStateReadActor
import actors.{DrtStaticParameters, GetPortState, GetUpdatesSince}
import akka.actor.{PoisonPill, Props}
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
          futureCrunchState(maybePointInTime, startMillis, endMillis, GetPortState(startMillis, endMillis))
            .mapTo[PortState]
            .map { updates => Ok(write(updates)) }
        case Some(sinceMillis) =>
          futureCrunchState(maybePointInTime, startMillis, endMillis, GetUpdatesSince(sinceMillis, startMillis, endMillis))
            .mapTo[Option[PortStateUpdates]]
            .map { updates => Ok(write(updates)) }
      }

      eventualUpdates
        .recoverWith {
          case t =>
            log.error("Error processing request for port state or port state updates", t.getMessage)
            Future(InternalServerError)
        }
    }
  }

  def getCrunchSnapshot(pointInTime: MillisSinceEpoch): Action[AnyContent] = authByRole(DesksAndQueuesView) {
    Action.async { request: Request[AnyContent] =>
      val startMillis = request.queryString.get("start").flatMap(_.headOption.map(_.toLong)).getOrElse(0L)
      val endMillis = request.queryString.get("end").flatMap(_.headOption.map(_.toLong)).getOrElse(0L)

      val message = GetPortState(startMillis, endMillis)
      val futureState = futureCrunchState(Option(pointInTime), startMillis, endMillis, message).mapTo[PortState]

      futureState
        .map { updates => Ok(write(updates)) }
        .recoverWith {
          case t =>
            log.error("Error processing request for port state", t)
            Future(InternalServerError)
        }
    }
  }

  def futureCrunchState(maybePointInTime: Option[MillisSinceEpoch],
                        startMillis: MillisSinceEpoch,
                        endMillis: MillisSinceEpoch,
                        request: Any): Future[Any] = {
    maybePointInTime match {
      case Some(pit) =>
        log.debug(s"Snapshot crunch state query ${SDate(pit).toISOString()}")
        val tempActor = system.actorOf(Props(new CrunchStateReadActor(airportConfig.portStateSnapshotInterval, SDate(pit), DrtStaticParameters.expireAfterMillis, airportConfig.queuesByTerminal, startMillis, endMillis)))
        val futureResult = tempActor.ask(request)(30 seconds)
        futureResult.foreach(_ => tempActor ! PoisonPill)
        futureResult
      case _ =>
        ctrl.portStateActor.ask(request)
    }
  }
}
