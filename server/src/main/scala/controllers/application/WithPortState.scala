package controllers.application

import actors.pointInTime.CrunchStateReadActor
import actors.{DrtStaticParameters, GetPortState, GetUpdatesSince}
import akka.actor.{PoisonPill, Props}
import controllers.Application
import controllers.model.ActorDataRequest
import drt.auth.DesksAndQueuesView
import drt.shared.CrunchApi.{MillisSinceEpoch, PortStateError, PortStateUpdates}
import drt.shared.PortState
import play.api.mvc.{Action, AnyContent, Request}
import services.SDate
import upickle.default.write

import scala.concurrent.Future


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

      maybeSinceMillis match {
        case None =>
          val future = futurePortState(maybePointInTime, startMillis, endMillis, GetPortState(startMillis, endMillis))
          future
            .map { updates => Ok(write(updates)) }
            .recover {
              case t =>
                log.error("Failed to get PortState", t)
                ServiceUnavailable
            }

        case Some(sinceMillis) =>
          log.info(s"Requesting updates from update end point")
          val future = futurePortStateUpdates(maybePointInTime, startMillis, endMillis, GetUpdatesSince(sinceMillis, startMillis, endMillis))
          future.map { updates => Ok(write(updates)) }
      }
    }
  }

  def getCrunchSnapshot(pointInTime: MillisSinceEpoch): Action[AnyContent] = authByRole(DesksAndQueuesView) {
    Action.async { request: Request[AnyContent] =>
      val startMillis = request.queryString.get("start").flatMap(_.headOption.map(_.toLong)).getOrElse(0L)
      val endMillis = request.queryString.get("end").flatMap(_.headOption.map(_.toLong)).getOrElse(0L)

      val message = GetPortState(startMillis, endMillis)
      val futureState = futurePortState(Option(pointInTime), startMillis, endMillis, message)

      futureState.map { updates => Ok(write(updates)) }
    }
  }

  def futurePortStateUpdates(maybePointInTime: Option[MillisSinceEpoch],
                             startMillis: MillisSinceEpoch,
                             endMillis: MillisSinceEpoch,
                             request: GetUpdatesSince): Future[Either[PortStateError, Option[PortStateUpdates]]] =
    maybePointInTime match {
      case Some(pit) =>
        log.debug(s"Snapshot crunch state query ${SDate(pit).toISOString()}")
        val tempActor = system.actorOf(Props(new CrunchStateReadActor(airportConfig.portStateSnapshotInterval, SDate(pit), DrtStaticParameters.expireAfterMillis, airportConfig.queuesByTerminal, startMillis, endMillis)))
        val futureResult = ActorDataRequest.portStateUpdates(tempActor, request)
        futureResult.foreach(_ => tempActor ! PoisonPill)
        futureResult
      case _ => ActorDataRequest.portStateUpdates(ctrl.portStateActor, request)
    }

  def futurePortState(maybePointInTime: Option[MillisSinceEpoch],
                      startMillis: MillisSinceEpoch,
                      endMillis: MillisSinceEpoch,
                      request: GetPortState): Future[PortState] =
    maybePointInTime match {
      case Some(pit) =>
        log.debug(s"Snapshot crunch state query ${SDate(pit).toISOString()}")
        val tempActor = system.actorOf(Props(new CrunchStateReadActor(airportConfig.portStateSnapshotInterval, SDate(pit), DrtStaticParameters.expireAfterMillis, airportConfig.queuesByTerminal, startMillis, endMillis)))
        val futureResult = ActorDataRequest.portState(tempActor, request)
        futureResult.foreach(_ => tempActor ! PoisonPill)
        futureResult
      case _ => ActorDataRequest.portState(ctrl.portStateActor, request)
    }

}
