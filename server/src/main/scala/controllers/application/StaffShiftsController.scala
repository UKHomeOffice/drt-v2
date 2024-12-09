package controllers.application

import drt.shared.StaffShift

import javax.inject._
import play.api.mvc._
import spray.json.DefaultJsonProtocol._
import spray.json.{DeserializationException, RootJsonFormat}
import spray.json._
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface

import scala.concurrent.{ExecutionContext, Future}

class StaffShiftsController @Inject()(cc: ControllerComponents, ctrl: DrtSystemInterface)(implicit ec: ExecutionContext) extends AuthController(cc, ctrl) {
  implicit val format: RootJsonFormat[StaffShift] = jsonFormat6(StaffShift.apply)

  private def convertTo(text: String) = {
    val json = text.parseJson
    json.convertTo[Seq[StaffShift]]
  }

  def getShift(port: String, terminal: String, shiftName: String): Action[AnyContent] = Action.async {
    ctrl.staffShiftsService.getShift(port, terminal, shiftName).map {
      case Some(shift) => Ok(shift.toString)
      case None => NotFound
    }
  }

  def getShifts(port: String, terminal: String): Action[AnyContent] = Action.async {
    ctrl.staffShiftsService.getShifts(port, terminal).map { shifts =>
      Ok(shifts.toString)
    }
  }

  def saveShift: Action[AnyContent] = Action.async { request =>
    request.body.asText.map { text =>
      try {
        val shifts = convertTo(text)
        ctrl.staffShiftsService.saveShift(shifts).map { result =>
          Ok(s"Inserted $result shift(s)")
        }
      } catch {
        case e: DeserializationException => Future.successful(BadRequest("Invalid shift format"))
      }
    }.getOrElse(Future.successful(BadRequest("Expecting JSON data")))
  }

  def removeShift(port: String, terminal: String, shiftName: String): Action[AnyContent] = Action.async {
    ctrl.staffShiftsService.deleteShift(port, terminal, shiftName).map { result =>
      Ok(s"Deleted $result shift(s)")
    }
  }
}
