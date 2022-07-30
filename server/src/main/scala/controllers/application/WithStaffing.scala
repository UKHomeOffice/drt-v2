package controllers.application

import actors._
import actors.persistent.staffing._
import akka.NotUsed
import akka.actor.{ActorRef, PoisonPill, Props}
import akka.pattern._
import akka.stream.scaladsl.Source
import controllers.Application
import controllers.application.exports.CsvFileStreaming
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared._
import drt.staff.ImportStaff
import play.api.mvc.{Action, AnyContent, Request}
import services.SDate
import services.exports.StaffMovementsExport
import uk.gov.homeoffice.drt.auth.Roles.{BorderForceStaff, FixedPointsEdit, FixedPointsView, StaffEdit, StaffMovementsEdit, StaffMovementsExport => StaffMovementsExportRole}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.SDateLike
import upickle.default.{read, write}

import java.util.UUID
import scala.concurrent.Future


trait WithStaffing {
  self: Application =>

  import services.SDate.implicits.sdateFromMillisLocal

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


  def removeStaffMovements(movementsToRemove: String): Action[AnyContent] = authByRole(StaffMovementsEdit) {
    Action {
      ctrl.staffMovementsActor ! RemoveStaffMovements(movementsToRemove)
      Accepted
    }
  }

  def getStaffMovements(maybePointInTime: Option[MillisSinceEpoch]): Action[AnyContent] = authByRole(BorderForceStaff) {
    Action.async {
      val eventualStaffMovements = maybePointInTime match {
        case None =>
          ctrl.staffMovementsActor.ask(GetState)
            .map { case StaffMovements(movements) => movements }
            .recoverWith { case _ => Future(Seq()) }

        case Some(millis) =>
          val date = SDate(millis)

          staffMovementsForDay(date)
      }

      eventualStaffMovements.map(sms => Ok(write(sms)))
    }
  }

  def exportStaffMovements(terminalString: String, pointInTime: MillisSinceEpoch): Action[AnyContent] =
    authByRole(StaffMovementsExportRole) {
      Action {
        val terminal = Terminal(terminalString)
        val date = SDate(pointInTime)
        val eventualStaffMovements = staffMovementsForDay(date)

        val csvSource: Source[String, NotUsed] = Source
          .fromFuture(eventualStaffMovements
            .map(sm => {
              StaffMovementsExport.toCSVWithHeader(sm, terminal)
            }))

        CsvFileStreaming.sourceToCsvResponse(
          csvSource,
          CsvFileStreaming.makeFileName(
            "staff-movements",
            terminal,
            date,
            date,
            portCode
          ))
      }
    }

  def staffMovementsForDay(date: SDateLike): Future[Seq[StaffMovement]] = {
    val actorName = "staff-movements-read-actor-" + UUID.randomUUID().toString
    val staffMovementsReadActor: ActorRef = system.actorOf(Props(classOf[StaffMovementsReadActor], date, DrtStaticParameters.time48HoursAgo(() => date)), actorName)

    val eventualStaffMovements: Future[Seq[StaffMovement]] = staffMovementsReadActor.ask(GetState)
      .map {
        case movements: StaffMovements =>
          staffMovementsReadActor ! PoisonPill
          movements.forDay(date)
      }
      .recoverWith {
        case _ =>
          staffMovementsReadActor ! PoisonPill
          Future(Seq())
      }
    eventualStaffMovements
  }
}
