package controllers.application

import actors.pointInTime.CrunchStateReadActor
import actors.{DrtStaticParameters, GetPortState, GetUpdatesSince}
import akka.actor.PoisonPill
import controllers.Application
import controllers.model.ActorDataRequest
import drt.shared.CrunchApi.{MillisSinceEpoch, PortStateError, PortStateUpdates}
import drt.shared.{DesksAndQueuesView, PortState}
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
          val future = futureCrunchState[PortState](maybePointInTime, startMillis, endMillis, GetPortState(startMillis, endMillis))
          future.map { updates => Ok(write(updates)) }

        case Some(sinceMillis) =>
          val future = futureCrunchState[PortStateUpdates](maybePointInTime, startMillis, endMillis, GetUpdatesSince(sinceMillis, startMillis, endMillis))
          future.map { updates => Ok(write(updates)) }
      }
    }
  }

  def getCrunchSnapshot(pointInTime: MillisSinceEpoch): Action[AnyContent] = authByRole(DesksAndQueuesView) {
    Action.async { request: Request[AnyContent] =>
      val startMillis = request.queryString.get("start").flatMap(_.headOption.map(_.toLong)).getOrElse(0L)
      val endMillis = request.queryString.get("end").flatMap(_.headOption.map(_.toLong)).getOrElse(0L)

      val message = GetPortState(startMillis, endMillis)
      val futureState = futureCrunchState[PortState](Option(pointInTime), startMillis, endMillis, message)

      futureState.map { updates => Ok(write(updates)) }
    }
  }

  def futureCrunchState[X](maybePointInTime: Option[MillisSinceEpoch], startMillis: MillisSinceEpoch, endMillis: MillisSinceEpoch, request: Any): Future[Either[PortStateError, Option[X]]] = {
    maybePointInTime match {
      case Some(pit) =>
        log.debug(s"Snapshot crunch state query ${SDate(pit).toISOString()}")
        val tempActor = system.actorOf(CrunchStateReadActor.props(airportConfig.portStateSnapshotInterval, SDate(pit), DrtStaticParameters.expireAfterMillis, airportConfig.queues, startMillis, endMillis))
        val futureResult = ActorDataRequest.portState[X](tempActor, request)
        futureResult.foreach(_ => tempActor ! PoisonPill)
        futureResult
      case _ => ActorDataRequest.portState[X](ctrl.portStateActor, request)
    }
  }

}
