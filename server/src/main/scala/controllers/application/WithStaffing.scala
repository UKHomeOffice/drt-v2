package controllers.application

import java.util.UUID

import actors._
import actors.pointInTime.{FixedPointsReadActor, StaffMovementsReadActor}
import akka.actor.{ActorRef, PoisonPill, Props}
import akka.pattern._
import controllers.Application
import drt.auth.{BorderForceStaff, FixedPointsEdit, FixedPointsView, StaffEdit, StaffMovementsEdit}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared._
import drt.staff.ImportStaff
import play.api.mvc.{Action, AnyContent, Request}
import services.SDate
import upickle.default.{read, write}

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
          val fixedPointsReadActor: ActorRef = system.actorOf(Props(classOf[FixedPointsReadActor], date, self.now), actorName)

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
      fps.map((fp: FixedPointAssignments) => Ok(write(fp)))
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

  def addStaffMovements(): Action[AnyContent] = authByRole(StaffMovementsEdit) {
    Action {
      request =>
        request.body.asText match {
          case Some(text) =>
            val movementsToAdd: List[StaffMovement] = read[List[StaffMovement]](text)
            ctrl.staffMovementsActor ! AddStaffMovements(movementsToAdd)
            Accepted
          case None =>
            BadRequest
        }
    }
  }


  def removeStaffMovements(movementsToRemove: UUID): Action[AnyContent] = authByRole(StaffMovementsEdit) {
    Action {

      ctrl.staffMovementsActor ! RemoveStaffMovements(movementsToRemove)
      Accepted
    }
  }

  def getStaffMovements(maybePointInTime: Option[MillisSinceEpoch]): Action[AnyContent] = authByRole(BorderForceStaff) {
    Action.async {
      val staffMovementsFuture = maybePointInTime match {
        case None =>
          ctrl.staffMovementsActor.ask(GetState)
            .map { case StaffMovements(movements) => movements }
            .recoverWith { case _ => Future(Seq()) }

        case Some(millis) =>
          val date = SDate(millis)

          val actorName = "staff-movements-read-actor-" + UUID.randomUUID().toString
          val staffMovementsReadActor: ActorRef = system.actorOf(Props(classOf[StaffMovementsReadActor], date, DrtStaticParameters.time48HoursAgo(() => date)), actorName)

          staffMovementsReadActor.ask(GetState)
            .map { case StaffMovements(movements) =>
              staffMovementsReadActor ! PoisonPill
              movements
            }
            .recoverWith {
              case _ =>
                staffMovementsReadActor ! PoisonPill
                Future(Seq())
            }
      }

      val eventualStaffMovements = staffMovementsFuture.collect {
        case Nil =>
          log.debug(s"Got no movements")
          List()
        case sm: Seq[StaffMovement] => sm
      }

      eventualStaffMovements.map(sms => Ok(write(sms)))
    }
  }

}
