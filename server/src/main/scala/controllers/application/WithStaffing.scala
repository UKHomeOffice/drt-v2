package controllers.application

import java.util.UUID

import actors.pointInTime.FixedPointsReadActor
import actors.{GetState, SetFixedPoints, SetShifts}
import akka.actor.{ActorRef, PoisonPill, Props}
import akka.pattern._
import controllers.Application
import drt.shared._
import drt.staff.ImportStaff
import play.api.mvc.{Action, AnyContent, Request}
import services.SDate
import upickle.default.{read, write}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future


trait WithStaffing {
  self: Application =>
  def getFixedPoints: Action[AnyContent] = authByRole(FixedPointsView) {
    Action.async { request: Request[AnyContent] =>

      val fps: Future[FixedPointAssignments] = request.queryString.get("sinceMillis").flatMap(_.headOption.map(_.toLong)) match {

        case None =>
          ctrl.fixedPointsActor.ask(GetState)
            .map { case sa: FixedPointAssignments => sa }
            .recoverWith { case _ => Future(FixedPointAssignments.empty) }

        case Some(millis) =>
          val date = SDate(millis)

          val actorName = "fixed-points-read-actor-" + UUID.randomUUID().toString
          val fixedPointsReadActor: ActorRef = system.actorOf(Props(classOf[FixedPointsReadActor], date), actorName)

          fixedPointsReadActor.ask(GetState)
            .map { case sa: FixedPointAssignments =>
              fixedPointsReadActor ! PoisonPill
              sa
            }
            .recoverWith {
              case _ =>
                fixedPointsReadActor ! PoisonPill
                Future(FixedPointAssignments.empty)
            }
      }
      fps.map(fp => Ok(write(fp)))
    }
  }

  def saveFixedPoints(): Action[AnyContent] = authByRole(FixedPointsEdit) {
    Action { request =>

      request.body.asText match {
        case Some(text) =>
          val fixedPoints: FixedPointAssignments = read[FixedPointAssignments](text)
          ctrl.fixedPointsActor ! SetFixedPoints(fixedPoints.assignments)
          Accepted
        case None =>
          BadRequest
      }
    }
  }

  def saveStaff(): Action[AnyContent] = authByRole(StaffEdit) {
    Action {
      implicit request =>
        val maybeShifts: Option[ShiftAssignments] = request.body.asJson.flatMap(ImportStaff.staffJsonToShifts)

        maybeShifts match {
          case Some(shifts) =>
            log.info(s"Received ${shifts.assignments.length} shifts. Sending to actor")
            ctrl.shiftsActor ! SetShifts(shifts.assignments)
            Created
          case _ =>
            BadRequest("{\"error\": \"Unable to parse data\"}")
        }
    }
  }

}
