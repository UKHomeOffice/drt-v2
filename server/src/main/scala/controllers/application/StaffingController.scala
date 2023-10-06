package controllers.application

import actors.DrtSystemInterface
import actors.persistent.staffing._
import akka.NotUsed
import akka.actor.{ActorRef, PoisonPill, Props}
import akka.pattern._
import akka.stream.scaladsl.Source
import com.google.inject.Inject
import controllers.application.exports.CsvFileStreaming
import drt.shared._
import drt.staff.ImportStaff
import play.api.mvc.{Action, AnyContent, ControllerComponents, Request}
import services.exports.StaffMovementsExport
import uk.gov.homeoffice.drt.auth.Roles.{BorderForceStaff, FixedPointsEdit, FixedPointsView, StaffEdit, StaffMovementsEdit, StaffMovementsExport => StaffMovementsExportRole}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{LocalDate, SDate}
import upickle.default.{read, write}

import java.util.UUID
import scala.concurrent.Future


class StaffingController @Inject()(cc: ControllerComponents,
                                   ctrl: DrtSystemInterface) extends AuthController(cc, ctrl) {

  import uk.gov.homeoffice.drt.time.SDate.implicits.sdateFromMillisLocal

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
          val fixedPointsReadActor: ActorRef = actorSystem.actorOf(Props(classOf[FixedPointsReadActor], date, ctrl.now), actorName)

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

  def saveFixedPoints: Action[AnyContent] = authByRole(FixedPointsEdit) {
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

  def saveStaff: Action[AnyContent] = authByRole(StaffEdit) {
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

  def addStaffMovements: Action[AnyContent] = authByRole(StaffMovementsEdit) {
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

  def getStaffMovements(date: String): Action[AnyContent] = authByRole(BorderForceStaff) {
    Action.async {
      val localDate = SDate(date).toLocalDate
      val eventualStaffMovements = staffMovementsForDay(localDate)

      eventualStaffMovements.map(sms => Ok(write(sms)))
    }
  }

  def exportStaffMovements(terminalString: String, date: String): Action[AnyContent] =
    authByRole(StaffMovementsExportRole) {
      Action {
        val terminal = Terminal(terminalString)
        val localDate = SDate(date).toLocalDate
        val eventualStaffMovements = staffMovementsForDay(localDate)

        val csvSource: Source[String, NotUsed] =
          Source.future(
            eventualStaffMovements.map { sm =>
              StaffMovementsExport.toCSVWithHeader(sm, terminal)
            }
          )

        CsvFileStreaming.sourceToCsvResponse(
          csvSource,
          CsvFileStreaming.makeFileName(
            "staff-movements",
            terminal,
            localDate,
            localDate,
            airportConfig.portCode
          ))
      }
    }

  def staffMovementsForDay(date: LocalDate): Future[Seq[StaffMovement]] = {
    val actorName = "staff-movements-read-actor-" + UUID.randomUUID().toString
    val pointInTime = SDate(date).addDays(1)
    val expireBefore = () => SDate(date).addDays(-1)
    val staffMovementsReadActor: ActorRef = actorSystem.actorOf(Props(classOf[StaffMovementsReadActor], pointInTime, expireBefore), actorName)

    staffMovementsReadActor.ask(GetState)
      .map {
        case movements: StaffMovements =>
          staffMovementsReadActor ! PoisonPill
          movements.forDay(date)(ld => SDate(ld))
      }
      .recoverWith {
        case _ =>
          staffMovementsReadActor ! PoisonPill
          Future.successful(Seq.empty[StaffMovement])
      }
  }
}
